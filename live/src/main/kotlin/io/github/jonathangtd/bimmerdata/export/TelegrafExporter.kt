/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.Sample
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
    /** Value of the `car` tag. */
    val car: String = "",
    /** At most this many points per measurement and second (independent of the read rate). */
    val pointsPerSecond: Int = 1,
    val mode: ExportMode = ExportMode.AfterTrip,
) {
    val usable: Boolean get() = enabled && TelegrafClient.isValidUrl(url)

    companion object {
        val RATES = 1..5
    }
}

enum class ExportPhase { Off, Idle, WaitingForNetwork, WaitingForUnmetered, Sending, Retrying, AuthFailed }

data class ExportState(
    val phase: ExportPhase = ExportPhase.Off,
    val queuedLines: Long = 0,
    val queuedBytes: Long = 0,
    /** Points deleted because the queue was full, or refused by the server for good. */
    val droppedLines: Long = 0,
    val sentLines: Long = 0,
    val lastSuccessMs: Long? = null,
    val lastError: String? = null,
)

/**
 * Exports live samples to Telegraf: samples → [ExportSampler] (rate limit) → [LineEncoder] →
 * [LineQueue] on disk → [TelegrafClient] in batches, when [ExportConfig.mode] and the
 * [network] allow it. Times are the original sample times, so an upload after the trip lands
 * at the right place in Grafana. Network errors are retried with a growing pause (2 s to 5 min);
 * nothing is lost unless the queue overflows (oldest data dropped first).
 */
class TelegrafExporter(
    private val scope: CoroutineScope,
    private val samples: StateFlow<Map<String, Sample>>,
    private val metadata: (String) -> Measurement?,
    private val queue: LineQueue,
    private val config: StateFlow<ExportConfig>,
    private val network: StateFlow<NetworkKind>,
    private val clientFactory: (ExportConfig) -> TelegrafClient = { TelegrafClient(it.url, it.auth) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Pause after a batch when the queue is empty, so points are grouped (about one POST per 2 s). */
    private val batchPauseMs: Long = 2_000,
    private val minBackoffMs: Long = 2_000,
    private val maxBackoffMs: Long = 300_000,
) {
    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val sampler = ExportSampler(1, clock())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val sendLock = Mutex()
    private var jobs: List<Job> = emptyList()
    @Volatile private var authFailedFor: ExportConfig? = null
    private var encoder: Pair<String, LineEncoder>? = null
    @Volatile private var rejectedLines = 0L

    @Synchronized
    fun start() {
        if (jobs.isNotEmpty()) return
        refreshQueueState()
        jobs = listOf(
            scope.launch(dispatcher) { collectSamples() },
            scope.launch(dispatcher) { sendLoop() },
            scope.launch { merge(config.drop(1), network.drop(1)).collect { wake.trySend(Unit) } },
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

    /** Deletes every point waiting on the phone. */
    fun clearQueue() {
        queue.clear()
        rejectedLines = 0
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
        samples.collect { snapshot ->
            val cfg = config.value
            if (!cfg.usable) {
                wasUsable = false
                return@collect
            }
            if (!wasUsable) {
                // values already on screen before the export was switched on are not sent
                sampler.reset(clock())
                wasUsable = true
            }
            sampler.pointsPerSecond = cfg.pointsPerSecond.coerceIn(ExportConfig.RATES)
            val enc = encoderFor(cfg.car)
            val lines = sampler.select(snapshot).mapNotNull { (id, s) -> enc.encode(metadata(id) ?: unknown(id), s) }
            if (lines.isEmpty()) return@collect
            queue.append(lines)
            refreshQueueState()
            wake.trySend(Unit)
        }
    }

    private fun encoderFor(car: String): LineEncoder {
        encoder?.let { (c, e) -> if (c == car) return e }
        return LineEncoder(car).also { encoder = car to it }
    }

    private fun unknown(id: String) = Measurement(id, id.substringBefore('/'), "", "", MeasurementSource.JobResult("", "", ""))

    // ---------------------------------------------------------------- sender

    private suspend fun sendLoop() {
        var backoff = minBackoffMs
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
            when (val r = sendOne(cfg)) {
                null -> Unit
                WriteResult.Ok -> {
                    backoff = minBackoffMs
                    if (queue.isEmpty()) delay(batchPauseMs)
                }
                is WriteResult.Rejected -> Unit
                is WriteResult.AuthFailed -> authFailedFor = cfg
                is WriteResult.Retry -> {
                    _state.update { it.copy(phase = ExportPhase.Retrying) }
                    // wait, but wake up early when the settings or the network change
                    withTimeoutOrNull(backoff) { merge(config.drop(1), network.drop(1)).first() }
                    backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
                }
            }
        }
    }

    /** Sends the oldest batch; null when the queue is empty. */
    private suspend fun sendOne(cfg: ExportConfig): WriteResult? = sendLock.withLock {
        val batch = queue.next(includeOpen = true) ?: return@withLock null
        val r = clientFactory(cfg).write(batch.body)
        when (r) {
            WriteResult.Ok -> {
                queue.remove(batch)
                _state.update { it.copy(sentLines = it.sentLines + batch.lines, lastSuccessMs = clock(), lastError = null) }
            }
            is WriteResult.Rejected -> {
                queue.remove(batch)
                rejectedLines += batch.lines
                _state.update { it.copy(lastError = "Rejected by the server: ${r.message}") }
            }
            is WriteResult.AuthFailed -> _state.update { it.copy(lastError = "Authentication failed: ${r.message}") }
            is WriteResult.Retry -> _state.update { it.copy(lastError = r.message) }
        }
        refreshQueueState()
        r
    }

    private fun refreshQueueState() {
        val q = queue.state()
        _state.update { it.copy(queuedLines = q.lines, queuedBytes = q.bytes, droppedLines = q.droppedLines + rejectedLines) }
    }

    companion object {
        fun allowed(mode: ExportMode, network: NetworkKind): Boolean = when (mode) {
            ExportMode.Live -> network != NetworkKind.None
            ExportMode.AfterTrip -> network == NetworkKind.Unmetered
        }
    }
}
