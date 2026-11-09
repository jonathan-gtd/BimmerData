/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** When points are uploaded. */
enum class ExportMode {
    /** As soon as any network reaches the server (mobile data + public server or VPN). */
    Live,

    /** Only on an unmetered network (home Wi-Fi): points wait on the phone until then. */
    AfterTrip,
}

/** What the phone is connected to right now. */
enum class NetworkKind { None, Metered, Unmetered }

/** Telegraf export settings, with the secret (token or password) already decrypted. */
data class ExportConfig(
    val enabled: Boolean = false,
    /** Telegraf listener URL, e.g. `http://192.168.1.10:8186/api/v2/write`. */
    val url: String = "",
    val auth: TelegrafAuth = TelegrafAuth.None,
    /** Value of the `car` tag (the active profile's car, else the car name of the settings). */
    val car: String = "",
    val mode: ExportMode = ExportMode.AfterTrip,
    /** Free space kept on the phone (fixed 100 MB): the queue stops growing (never deletes) below it. */
    val reserveBytes: Long = LineQueue.DEFAULT_RESERVE_BYTES,
    /** Value of the `profile` tag (active profile name); empty = no tag. */
    val profile: String = "",
) {
    val usable: Boolean get() = enabled && TelegrafClient.isValidUrl(url)
}

enum class ExportPhase { Off, Idle, WaitingForNetwork, WaitingForUnmetered, Sending, Retrying, AuthFailed }

data class ExportState(
    val phase: ExportPhase = ExportPhase.Off,
    val queuedLines: Long = 0,
    /** Size of the queue on disk (compressed). */
    val queuedBytes: Long = 0,
    /** Uncompressed size of the queued points. */
    val queuedRawBytes: Long = 0,
    val sentLines: Long = 0,
    val lastSuccessMs: Long? = null,
    val lastError: String? = null,
    /** Points not queued because the phone reached the free-space reserve (the CSV has them). */
    val refusedLines: Long = 0,
    /** Batches refused for good by the server (HTTP 400), kept aside in `rejected/`. */
    val rejectedBatches: Int = 0,
    val rejectedLines: Long = 0,
    /** The phone reached the reserve: new points are not queued (nothing is deleted). */
    val blocked: Boolean = false,
    val freeBytes: Long = 0,
    val reserveBytes: Long = LineQueue.DEFAULT_RESERVE_BYTES,
    /** Uncompressed bytes queued per second over the last minute (0 when nothing is queued). */
    val queueBytesPerSecond: Double = 0.0,
    /** Measurements exported since the export (re)started. */
    val measurementCount: Int = 0,
) {
    /** Space the queue may still use before reaching the reserve. */
    val roomBytes: Long get() = (freeBytes - reserveBytes).coerceAtLeast(0)
}

/**
 * Exports every live reading to Telegraf, at the read rate: [readings] → [ExportDedup] → [LineEncoder] →
 * [LineQueue] on disk (gzip) → [TelegrafClient] in batches, when [ExportConfig.mode] and the
 * [network] allow it. Times are the original sample times, so an upload after the trip lands
 * at the right place in Grafana.
 *
 * **Nothing is ever lost on the phone side**: a batch is deleted only after a 2xx answer.
 * Network errors, 5xx, 404 and other temporary answers are retried with a growing pause (2 s to
 * 5 min); 401/403 wait for new credentials; a batch refused for good (400) is moved aside to
 * `rejected/` for the user (retry, export). When the storage reaches the reserve, new points are
 * not queued (and counted) instead of deleting old ones.
 */
class TelegrafExporter(
    private val scope: CoroutineScope,
    /** Every reading, in order ([io.github.jonathangtd.bimmerdata.live.LiveDataSource.readings]). */
    private val readings: Flow<List<Pair<String, Sample>>>,
    private val metadata: (String) -> Measurement?,
    private val queue: LineQueue,
    private val config: StateFlow<ExportConfig>,
    private val network: StateFlow<NetworkKind>,
    private val clientFactory: (ExportConfig) -> TelegrafClient = { TelegrafClient(it.url, it.auth) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Live: at most one request per this time (new points arrive during every send). */
    private val batchPauseMs: Long = 2_000,
    /** Live: points per request; fewer are sent once the oldest waited [maxBatchWaitMs]. */
    private val minBatchLines: Int = 100,
    /** Live: longest a point waits on the phone for a request to fill up. */
    private val maxBatchWaitMs: Long = 30_000,
    private val minBackoffMs: Long = 2_000,
    private val maxBackoffMs: Long = 300_000,
    /** Pause before retrying with a smaller batch. */
    private val shrinkPauseMs: Long = 500,
) {
    /**
     * Lines per batch ([BatchSizer]; Int.MAX_VALUE = whole segments): smaller when the server
     * times out or answers 413 / 502-504, so a slow server gets small batches instead of the same
     * too-big batch failing forever.
     */
    private val sizer = BatchSizer()
    val batchLimit: Int get() = sizer.limit
    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val dedup = ExportDedup(clock())
    private val rate = QueueEstimate.Rate()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val sendLock = Mutex()
    private var jobs: List<Job> = emptyList()
    @Volatile private var authFailedFor: ExportConfig? = null
    private var encoder: Pair<String, LineEncoder>? = null

    @Synchronized
    fun start() {
        if (jobs.isNotEmpty()) return
        queue.reserveBytes = config.value.reserveBytes
        refreshQueueState()
        jobs = listOf(
            scope.launch(dispatcher) { collectSamples() },
            scope.launch(dispatcher) { sendLoop() },
            // no drop(1): a change made before this collector starts must still wake the sender
            // (one extra wake at start is harmless, the sender just looks again)
            scope.launch { merge(config, network).collect { wake.trySend(Unit) } },
        )
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        queue.close()
    }

    /** Asks the sender to try again now (e.g. after "Send now" or a settings change). */
    fun kick() {
        authFailedFor = null
        wake.trySend(Unit)
    }

    /** New free-space reserve; shown (and applied) at once. */
    fun setReserveBytes(bytes: Long) {
        queue.reserveBytes = bytes
        refreshQueueState()
    }

    /** Recomputes the queue figures (free space, queued-bytes rate) for the Settings screen. */
    fun refresh() = refreshQueueState()

    /** Deletes every point waiting on the phone (only on the user's explicit request). */
    fun clearQueue() {
        queue.clear()
        refreshQueueState()
    }

    /** Puts the rejected batches back into the upload queue and tries now. */
    fun retryRejected(): Int = queue.retryRejected().also {
        refreshQueueState()
        kick()
    }

    /** The rejected batches (gzip line protocol files), for an export. */
    fun rejectedFiles(): List<File> = queue.rejectedFiles()

    /** Deletes the rejected batches (only on the user's explicit request). */
    fun deleteRejected() {
        queue.deleteRejected()
        refreshQueueState()
    }

    /** Test with [cfg]: an empty write to the listener. */
    suspend fun test(cfg: ExportConfig): WriteResult = withContext(dispatcher) {
        if (!TelegrafClient.isValidUrl(cfg.url)) WriteResult.Rejected("Invalid URL") else clientFactory(cfg).test()
    }

    /**
     * Uploads the whole queue now, whatever the mode (used by the background job that Android
     * only starts on an unmetered network). Returns true once the queue is empty.
     */
    suspend fun drain(): Boolean = withContext(dispatcher) {
        val cfg = config.value
        if (!cfg.usable) return@withContext false
        while (isActive) {
            when (sendOne(cfg) ?: return@withContext true) {
                WriteResult.Ok, is WriteResult.Rejected -> Unit
                else -> return@withContext false
            }
        }
        false
    }

    // ---------------------------------------------------------------- producer

    private suspend fun collectSamples() {
        var wasUsable = false
        readings.collect { batch ->
            val cfg = config.value
            if (!cfg.usable) {
                wasUsable = false
                return@collect
            }
            if (!wasUsable) {
                // values already on screen before the export was switched on are not sent
                dedup.reset(clock())
                wasUsable = true
            }
            queue.reserveBytes = cfg.reserveBytes
            val enc = encoderFor(cfg.car, cfg.profile)
            val lines = dedup.select(batch).mapNotNull { (id, s) -> enc.encode(metadata(id) ?: unknown(id), s) }
            if (lines.isEmpty()) return@collect
            if (queue.append(lines)) rate.add(clock(), lines.sumOf { it.length + 1L })
            _state.update { it.copy(measurementCount = dedup.measurementCount) }
            refreshQueueState()
            wake.trySend(Unit)
        }
    }

    private fun encoderFor(car: String, profile: String): LineEncoder {
        val key = car + "\u0000" + profile
        encoder?.let { (k, e) -> if (k == key) return e }
        return LineEncoder(car, profile = profile).also { encoder = key to it }
    }

    private fun unknown(id: String) = Measurement(id, id.substringBefore('/'), "", "", MeasurementSource.JobResult("", "", ""))

    // ---------------------------------------------------------------- sender

    /**
     * Live: waits [batchPauseMs], then until [minBatchLines] points wait, a backlog appears or
     * [maxBatchWaitMs] passed: few requests (one per 2 s at most, at least 100 points each when
     * the car gives that many), and a slow source still gets its points out within 30 s.
     */
    private suspend fun gatherBatch() {
        delay(batchPauseMs)
        withTimeoutOrNull((maxBatchWaitMs - batchPauseMs).coerceAtLeast(0)) {
            while (!queue.hasBacklog() && queue.lines() < minBatchLines) delay(GATHER_POLL_MS)
        }
    }

    private suspend fun sendLoop() {
        var backoff = minBackoffMs
        var gathered = false
        while (scope.isActive) {
            val cfg = config.value
            val phase = when {
                !cfg.usable -> ExportPhase.Off
                authFailedFor == cfg -> ExportPhase.AuthFailed
                queue.isEmpty() -> ExportPhase.Idle
                !allowed(cfg.mode, network.value) ->
                    if (cfg.mode == ExportMode.AfterTrip && network.value == NetworkKind.Metered) ExportPhase.WaitingForUnmetered else ExportPhase.WaitingForNetwork
                else -> null
            }
            if (phase != null) {
                _state.update { it.copy(phase = phase) }
                wake.receive()
                continue
            }
            _state.update { it.copy(phase = ExportPhase.Sending) }
            // Live: gather points first (a backlog is sent at once, batch after batch), then look
            // at the settings and the network again before sending.
            if (!gathered && !queue.hasBacklog()) {
                gatherBatch()
                gathered = true
                continue
            }
            gathered = false
            when (val r = sendOne(cfg)) {
                null -> Unit
                WriteResult.Ok -> {
                    backoff = minBackoffMs
                }
                is WriteResult.Rejected -> Unit
                is WriteResult.AuthFailed -> authFailedFor = cfg
                is WriteResult.Retry -> {
                    _state.update { it.copy(phase = ExportPhase.Retrying) }
                    // a smaller batch is worth trying at once (still with a short pause)
                    if (r.smaller && shrunk) {
                        shrunk = false
                        delay(shrinkPauseMs)
                        continue
                    }
                    // wait, but wake up early when the settings or the network change
                    withTimeoutOrNull(backoff) { merge(config.drop(1), network.drop(1)).first() }
                    backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
                }
            }
        }
    }

    /** The last failure lowered [batchLimit] (the next try uses smaller batches). */
    private var shrunk = false

    /** Sends the oldest batch; null when the queue is empty. Deletes it only on 2xx. */
    private suspend fun sendOne(cfg: ExportConfig): WriteResult? = sendLock.withLock {
        val batch = queue.next(includeOpen = true, maxLines = batchLimit) ?: return@withLock null
        val r = clientFactory(cfg).write(batch.body, gzipped = batch.gzip)
        shrunk = false
        when (r) {
            WriteResult.Ok -> {
                queue.remove(batch)
                sizer.ok()
                _state.update { it.copy(sentLines = it.sentLines + batch.lines, lastSuccessMs = clock(), lastError = null) }
            }
            is WriteResult.Rejected -> {
                queue.reject(batch, r.message)
                _state.update { it.copy(lastError = "Refused by the server, kept aside: ${r.message}") }
            }
            is WriteResult.AuthFailed -> _state.update { it.copy(lastError = "Authentication failed: ${r.message}") }
            is WriteResult.Retry -> {
                if (r.smaller) shrunk = sizer.tooBig(batch.lines)
                _state.update { it.copy(lastError = r.message) }
            }
        }
        refreshQueueState()
        r
    }

    private fun refreshQueueState() {
        val q = queue.state()
        val bps = rate.bytesPerSecond(clock())
        _state.update {
            it.copy(
                queuedLines = q.lines, queuedBytes = q.bytes, queuedRawBytes = q.rawBytes,
                refusedLines = q.refusedLines, rejectedBatches = q.rejectedBatches, rejectedLines = q.rejectedLines,
                blocked = q.blocked, freeBytes = q.freeBytes, reserveBytes = q.reserveBytes, queueBytesPerSecond = bps,
            )
        }
    }

    companion object {
        /** How often a gathering batch looks at the queue. */
        const val GATHER_POLL_MS = 200L

        fun allowed(mode: ExportMode, network: NetworkKind): Boolean = when (mode) {
            ExportMode.Live -> network != NetworkKind.None
            ExportMode.AfterTrip -> network == NetworkKind.Unmetered
        }
    }
}
