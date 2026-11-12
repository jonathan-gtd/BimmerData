/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceBase
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * The live acquisition loop: one worker thread owns an [EdiabasNet] interpreter and [iface]
 * (any [EdInterfaceBase]: USB cable, simulated K-line, [io.github.jonathangtd.bimmerdata.ediabas.FakeInterface]).
 * Public methods never block: they only post state; the worker picks it up between telegrams.
 *
 * Planning (redone when the measurement set changes):
 * - MESSWERTETAB measurements are grouped per SGBD into balanced blocks of at most
 *   [Config.maxIdsPerBlock] ids (16, and [MesswertBlockReader.MaxRecordBytes] record bytes). Every
 *   read runs the whole STATUS_MESSWERTBLOCK_LESEN job with JA (clear, define, read), exactly
 *   like TestO and Deep OBD; the blocks of one ECU take turns.
 * - Pace: two reads of one SGBD (block or job) start at least [currentReadIntervalMs] apart
 *   ([Config.minReadIntervalMs] = 125 ms, 8 reads/s). A read the car did not answer (timeout,
 *   transmission error) doubles the interval up to [Config.maxReadIntervalMs]; after
 *   [Config.recoverAfterMs] without incident it is halved again, never below the minimum.
 *   Asked back to back for 40 values, an engine ECU misfired and the whole car glitched.
 * - [MeasurementSource.JobResult] measurements are grouped by (sgbd, job, args): one job run
 *   feeds every result of the group. When other groups are planned too, a job group runs at most
 *   every [Config.jobMinIntervalMs], and at most every (1 + [Config.jobIntervalFactor]) times its
 *   own duration (capped at [Config.jobMaxIntervalMs]), so a slow STATUS job does not starve the
 *   fast path. Only read-only jobs are run ([ReadOnlyJobs.isReadOnlyName]): any other job in a
 *   layout is reported in [unsupported] and never sent.
 *
 * Errors: transport errors put the group in exponential backoff ([Config.retryDelayMinMs]..
 * [Config.retryDelayMaxMs]); other groups keep running. When every group failed
 * [Config.reconnectAfterFailedCycles] times in a row, the interface is reconnected. A definition
 * the ECU refuses is split to find unsupported ids ([unsupported]); if none is found the block
 * size limit of that SGBD is halved (the ECU may accept fewer values than the SGBD).
 *
 * Shared cable ([CableExecutor]): the engine is the only owner of [iface] and of its interpreter.
 * One-off operations ([exclusive]: Tool32, vehicle scan, fault memory, API) are queued and run on
 * the worker thread between two read cycles: one operation per cycle while measurements are
 * read, all of them in a row when nothing is read (every block read defines its record again
 * anyway, so an operation that cleared F0 does no harm). An operation longer than
 * [Config.pauseThresholdMs] while measurements are read is reported in [pauses].
 */
class AcquisitionEngine(
    private val iface: EdInterfaceBase,
    private val catalog: EdiabasMeasurementCatalog,
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
) : LiveDataSource, CableExecutor, AutoCloseable {

    data class Config(
        /**
         * 1..42: values per dynamic record (the SGBD accepts 42). 16 like TestO: more values on one
         * ECU = blocks of that ECU taking turns.
         */
        val maxIdsPerBlock: Int = 16,
        /**
         * Start-to-start minimum time between two reads of the same SGBD (each block or job of that
         * ECU counts), never less: 125 ms = 8 reads/s like TestO / Deep OBD. 0 only for tests.
         */
        val minReadIntervalMs: Long = 125,
        /** Longest interval the automatic slow-down goes to (it doubles on each incident). */
        val maxReadIntervalMs: Long = 1000,
        /** Without incident for this long, the interval is halved again (down to [minReadIntervalMs]). */
        val recoverAfterMs: Long = 180_000,
        val retryDelayMinMs: Long = 200,
        val retryDelayMaxMs: Long = 5000,
        val reconnectAfterFailedCycles: Int = 5,
        /** Window for [AcquisitionStatus.cyclesPerSecond]. */
        val statusWindowMs: Long = 3000,
        /** Optional pause between cycles (0 = as fast as the ECU answers). */
        val minCycleMs: Long = 0,
        /** Extra EdiabasNet configuration ("Key=Value;..."), e.g. "IfhTrace=1". */
        val ediabasConfig: String = "",
        /** Shortest period of a job group when other groups share the interface. */
        val jobMinIntervalMs: Long = 200,
        /** A job group waits this many times its own duration before it runs again (with other groups). */
        val jobIntervalFactor: Double = 2.0,
        /** Longest period the duration rule may give a job group. */
        val jobMaxIntervalMs: Long = 5000,
        /** A one-off operation at least this long while measurements are read is reported in [pauses]. */
        val pauseThresholdMs: Long = 1000,
        /**
         * The interface never connected since [start]: stop after this many failed attempts and
         * report [LinkState.Unreachable] (0 = keep trying). Once it connected, a lost link is
         * always retried.
         */
        val firstConnectAttempts: Int = 0,
    )

    /** Whether the interface connected since [start]. */
    enum class LinkState {
        /** Not connected yet, still trying. */
        Pending,
        /** Connected at least once since [start] (it may be reconnecting now). */
        Up,
        /** Never connected: gave up ([Config.firstConnectAttempts]) or stopped before; the loop is over. */
        Unreachable,
    }

    private val _samples = MutableStateFlow<Map<String, Sample>>(emptyMap())
    override val samples: StateFlow<Map<String, Sample>> = _samples.asStateFlow()

    /** Every published group, in order; a large buffer so a slow consumer (export) misses nothing. */
    private val _readings = MutableSharedFlow<List<Pair<String, Sample>>>(extraBufferCapacity = READINGS_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val readings: SharedFlow<List<Pair<String, Sample>>> = _readings.asSharedFlow()

    private val _status = MutableStateFlow(AcquisitionStatus())
    override val status: StateFlow<AcquisitionStatus> = _status.asStateFlow()

    private val _link = MutableStateFlow(LinkState.Pending)

    /** First connection of the interface since [start] (the reason of an [LinkState.Unreachable] is in [status]). */
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _measurements = MutableStateFlow<List<Measurement>>(emptyList())

    /** Measurements requested by [setMeasurements]. */
    val measurements: StateFlow<List<Measurement>> = _measurements.asStateFlow()

    private val _pinned = MutableStateFlow<List<Measurement>>(emptyList())

    private val _unsupported = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of measurements the ECU refused (not read any more until the set changes). */
    val unsupported: StateFlow<Set<String>> = _unsupported.asStateFlow()

    private val _unavailable = MutableStateFlow<Map<String, String>>(emptyMap())

    /** See [LiveDataSource.unavailable]: job values the car did not give (plus [unsupported] ids). */
    override val unavailable: StateFlow<Map<String, String>> = _unavailable.asStateFlow()

    /** Measurements actually read: requested + pinned (e.g. by a running recording). */
    private val effective = combine(_measurements, _pinned) { a, b -> (a + b).distinctBy { it.id } }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "live-acquisition").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    @Volatile private var loopJob: Job? = null
    @Volatile private var abortRequested = false
    @Volatile private var closed = false

    // ---- one-off operations (shared cable) ----
    private class Op<T>(val reason: String, val block: (CableAccess) -> T) {
        val result = CompletableDeferred<T>()
        @Volatile var cancelled = false
    }

    private val opsLock = Any()
    private val ops = ArrayDeque<Op<*>>()
    private val opSignal = MutableStateFlow(0L)
    @Volatile private var currentOp: Op<*>? = null
    @Volatile private var currentAccess: CableAccess? = null
    @Volatile private var opAbort = false

    private val _pauses = MutableSharedFlow<AcquisitionPause>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val pauses: SharedFlow<AcquisitionPause> = _pauses.asSharedFlow()

    /** Number of one-off operations run so far (tests, diagnostics). */
    /** One-off operations at least this long are reported in [pauses] ([Config.pauseThresholdMs]). */
    val pauseThresholdMs: Long get() = config.pauseThresholdMs

    @Volatile var exclusiveRuns: Long = 0
        private set

    // ---- worker thread state ----
    private var ediabas: EdiabasNet? = null
    private var connected = false
    private var plan: List<Group> = emptyList()
    private var plannedFor: List<Measurement>? = null
    /** Earliest start of the next read of each SGBD ([currentReadIntervalMs]). */
    private val sgbdNextAt = HashMap<String, Long>()
    private val blockLimit = HashMap<String, Int>()
    private val refusedRows = HashMap<String, MutableSet<Int>>()
    private val cycleTimes = ArrayDeque<Long>()
    private var failedCycles = 0

    override fun setMeasurements(measurements: List<Measurement>) {
        _measurements.value = measurements.distinctBy { it.id }
    }

    /** Extra measurements read whatever [setMeasurements] says (null/empty = none). */
    fun setPinnedMeasurements(measurements: List<Measurement>?) {
        _pinned.value = measurements?.distinctBy { it.id } ?: emptyList()
    }

    @Volatile private var readIntervalMs = config.minReadIntervalMs.coerceAtLeast(0)
    @Volatile private var slowDowns = 0L
    private var lastIncidentAt = 0L

    /** Start-to-start interval between two reads of one ECU now (raised by the automatic slow-down). */
    val currentReadIntervalMs: Long get() = readIntervalMs

    /**
     * The car did not answer or forgot the record while reading: maybe the ECU is overloaded or
     * rebooted. Read it half as often (up to [Config.maxReadIntervalMs]).
     */
    private fun slowDown(now: Long) {
        lastIncidentAt = now
        val base = config.minReadIntervalMs.coerceAtLeast(0)
        val max = maxOf(config.maxReadIntervalMs, base)
        val next = (maxOf(readIntervalMs, 1) * 2).coerceIn(base, max)
        if (next != readIntervalMs) {
            readIntervalMs = next
            slowDowns++
        }
    }

    /** Back towards [Config.minReadIntervalMs], one halving per [Config.recoverAfterMs] without incident. */
    private fun maybeRecover(now: Long) {
        val base = config.minReadIntervalMs.coerceAtLeast(0)
        if (readIntervalMs > base && now - lastIncidentAt >= config.recoverAfterMs) {
            readIntervalMs = (readIntervalMs / 2).coerceAtLeast(base)
            lastIncidentAt = now // next halving after another quiet period
        }
    }

    val isRunning: Boolean get() = loopJob?.isActive == true

    /** Connects and starts the loop (no-op if running). */
    fun start() {
        check(!closed) { "engine closed" }
        if (loopJob?.isActive == true) return
        abortRequested = false
        _link.value = LinkState.Pending
        _status.value = AcquisitionStatus(AcquisitionState.Connecting)
        val previous = loopJob
        loopJob = scope.launch {
            previous?.join()
            runLoop()
        }
    }

    /** Stops the loop and releases the interface, asynchronously. Queued one-off operations fail. */
    fun stop() {
        val job = loopJob ?: return
        abortRequested = true
        job.cancel()
        runCatching { iface.transmitCancel(true) }
        failQueued("Disconnected")
    }

    // ---------------------------------------------------------------- shared cable

    override suspend fun <T> exclusive(reason: String, block: (CableAccess) -> T): T {
        val op = Op(reason, block)
        synchronized(opsLock) {
            if (closed || abortRequested || loopJob == null) throw CableUnavailableException("Not connected")
            ops.addLast(op)
        }
        opSignal.update { it + 1 }
        try {
            return op.result.await()
        } catch (ex: CancellationException) {
            if (op.result.isCompleted) throw ex
            op.cancelled = true
            if (currentOp === op) abortCurrentOp()
            throw ex
        }
    }

    /** Aborts the running one-off operation, if any (between opcodes / pending receive). */
    private fun abortCurrentOp() {
        opAbort = true
        runCatching { iface.transmitCancel(true) }
    }

    private fun failQueued(message: String) {
        val pending = synchronized(opsLock) { ops.toList().also { ops.clear() } }
        pending.forEach { it.result.completeExceptionally(CableUnavailableException(message)) }
    }

    private fun pollOp(): Op<*>? = synchronized(opsLock) {
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            if (!op.cancelled) return@synchronized op
            op.result.cancel()
        }
        null
    }

    private fun hasOps(): Boolean = synchronized(opsLock) { ops.isNotEmpty() }

    /** Runs one queued operation (or all of them when [all]) on the worker thread. */
    private fun runOps(ed: EdiabasNet, all: Boolean) {
        while (true) {
            val op = pollOp() ?: return
            runOp(ed, op)
            if (!all) return
        }
    }

    private fun <T> runOp(ed: EdiabasNet, op: Op<T>) {
        currentOp = op
        opAbort = false
        runCatching { iface.transmitCancel(false) }
        val access = CableAccess(ed, iface) { opAbort || abortRequested || op.cancelled }
        currentAccess = access
        val sgbdBefore = ed.sgbdFileName
        val started = clock()
        exclusiveRuns++
        try {
            op.result.complete(op.block(access))
        } catch (ex: Throwable) {
            op.result.completeExceptionally(if (ex is CancellationException) CableUnavailableException("Cancelled") else ex)
        } finally {
            currentOp = null
            currentAccess = null
            opAbort = false
            if (!abortRequested) runCatching { iface.transmitCancel(false) }
        }
        // The operation may have run jobs of an SGBD read on the fast path (e.g. its own
        // STATUS_MESSWERTBLOCK_LESEN): define those blocks again before trusting a native decode.
        if (!ed.sgbdFileName.equals(sgbdBefore, ignoreCase = true)) access.touched += EdiabasMeasurementCatalog.normalize(ed.sgbdFileName)
        plan.forEach { if (it is BlockGroup && it.sgbd in access.touched) it.reader.invalidate() }
        val ended = clock()
        if (plan.isNotEmpty() && ended - started >= config.pauseThresholdMs) {
            _pauses.tryEmit(AcquisitionPause(started, ended, op.reason))
        }
    }

    /** [stop], then waits until the interface is released (e.g. before another user opens the cable). */
    suspend fun stopAndJoin() {
        val job = loopJob ?: return
        stop()
        job.join()
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        val job = loopJob
        scope.launch {
            job?.join()
            runCatching { ediabas?.close() }
            ediabas = null
        }.invokeOnCompletion { executor.shutdown() }
    }

    // ---------------------------------------------------------------- loop

    private sealed class Group {
        var failures = 0
        var retryAt = 0L
        /** Start of the last read (round robin among the groups of one SGBD). */
        var lastStart = 0L
        abstract val sgbd: String
        abstract val label: String
    }

    private class BlockGroup(
        override val sgbd: String,
        val reader: MesswertBlockReader,
        val byRow: Map<Int, List<Measurement>>,
    ) : Group() {
        override val label: String get() = "$sgbd block of ${reader.rows.size}"
    }

    private class JobGroup(override val sgbd: String, val job: String, val args: String, val members: List<Measurement>) : Group() {
        override val label: String get() = "$sgbd $job${if (args.isNotEmpty()) "($args)" else ""}"
    }

    private suspend fun runLoop() {
        iface.transmitCancel(false)
        val ed = ediabas ?: EdiabasNet(listOf("EcuPath=${catalog.ecuDir.path}", config.ediabasConfig).filter { it.isNotBlank() }.joinToString(";")).also {
            it.edInterfaceClass = iface
            it.abortJobFunc = { abortRequested || (currentOp != null && currentAccess?.aborted == true) }
            ediabas = it
        }
        cycleTimes.clear()
        failedCycles = 0
        var errorCount = 0
        var lastError: String? = null
        statErrors = 0
        statLastError = null
        statDefines = 0
        statTimeouts = 0
        sgbdNextAt.clear()
        readIntervalMs = config.minReadIntervalMs.coerceAtLeast(0)
        slowDowns = 0
        lastIncidentAt = 0
        intervalCount = 0
        totalCycles = 0
        slowCycles = 0
        lastCycleAt = 0
        var connectFailures = 0
        var unreachable: String? = null
        try {
            while (true) {
                coroutineContext.ensureActive()
                if (!connected) {
                    _status.update { it.copy(state = AcquisitionState.Connecting, cyclesPerSecond = 0.0) }
                    val err = connect()
                    if (err != null) {
                        errorCount++
                        connectFailures++
                        lastError = err
                        _status.value = AcquisitionStatus(AcquisitionState.Error, 0.0, errorCount, err)
                        failQueued(err) // one-off operations do not wait for a reconnection
                        if (_link.value == LinkState.Pending && config.firstConnectAttempts in 1..connectFailures) {
                            unreachable = err // never reached: give up instead of "reconnecting" forever
                            abortRequested = true
                            return
                        }
                        waitOrChange(backoff(connectFailures))
                        continue
                    }
                    connectFailures = 0
                    _link.value = LinkState.Up
                }
                val wanted = effectiveNow()
                if (wanted != plannedFor) replan(ed, wanted)
                if (plan.isEmpty()) {
                    cycleTimes.clear()
                    _status.value = AcquisitionStatus(AcquisitionState.Running, 0.0, errorCount, idleMessage())
                    val sig = opSignal.value
                    if (hasOps()) {
                        runOps(ed, all = true) // nothing to read: run them back to back
                        continue
                    }
                    merge(effective.filter { it != plannedFor }.map { }, opSignal.filter { it != sig }.map { }).first()
                    continue
                }
                runOps(ed, all = false) // one-off operation between two read cycles
                val now = clock()
                maybeRecover(now)
                // due = not in backoff and its ECU not read too recently (pace); the least recently
                // read first, so the blocks of one ECU rotate
                val due = plan.filter { it.retryAt <= now && nextReadAt(it.sgbd) <= now }.sortedBy { it.lastStart }
                if (due.isEmpty()) {
                    waitOrChange((plan.minOf { maxOf(it.retryAt, nextReadAt(it.sgbd)) } - now).coerceAtLeast(1))
                    continue
                }
                var successes = 0
                var failures = 0
                var linkFailures = 0
                for (group in due) {
                    coroutineContext.ensureActive()
                    if (plannedFor != wanted) break // replanned (refused ids)
                    val started = clock()
                    if (nextReadAt(group.sgbd) > started) continue // this ECU was just read by another group
                    val interval = readIntervalMs
                    if (interval > 0) sgbdNextAt[group.sgbd] = started + interval
                    group.lastStart = started
                    val failure = readGroup(ed, group)
                    val error = failure?.text
                    if (failure?.link == true) {
                        linkFailures++
                        slowDown(clock())
                    }
                    if (error == null) {
                        successes++
                        if (group is JobGroup && group.members.any { it.id in _unavailable.value }) {
                            _unavailable.update { it - group.members.map { m -> m.id }.toSet() }
                        }
                        group.failures = 0
                        group.retryAt = if (group is JobGroup && plan.size > 1) started + jobPeriod(clock() - started) else 0
                    } else {
                        failures++
                        errorCount++
                        lastError = "${group.label}: $error"
                        statErrors = errorCount
                        statLastError = lastError
                        group.failures++
                        group.retryAt = clock() + backoff(group.failures)
                        if (failure?.link == false && group is JobGroup) {
                            // the ECU answers: no hurry to ask again, and never at the cost of the other cards
                            group.retryAt = clock() + unavailableRetryMs(group.failures, error)
                            _unavailable.update { old -> old + group.members.associate { it.id to (error ?: "not available") } }
                        }
                        if (group.failures >= 2 && group is BlockGroup) group.reader.invalidate()
                    }
                    yield()
                }
                if (successes > 0) {
                    failedCycles = 0
                    markCycle()
                    _status.value = AcquisitionStatus(AcquisitionState.Running, cyclesPerSecond(), errorCount, planMessage(lastError.takeIf { failures > 0 }))
                } else if (failures > 0 && linkFailures == 0) {
                    // The link works: the ECUs answered but refused these measurements (unsupported
                    // service, missing result, protocol of that SGBD not implemented). Not a reason to
                    // show the connection as dead or to reconnect; the groups retry with their backoff.
                    failedCycles = 0
                    _status.value = AcquisitionStatus(AcquisitionState.Running, 0.0, errorCount, planMessage(lastError))
                } else if (failures > 0) {
                    failedCycles++
                    _status.value = AcquisitionStatus(AcquisitionState.Error, cyclesPerSecond(), errorCount, lastError)
                    if (failedCycles >= config.reconnectAfterFailedCycles) {
                        failedCycles = 0
                        disconnect()
                        plan.forEach { if (it is BlockGroup) it.reader.invalidate() }
                    }
                }
                if (config.minCycleMs > 0) delay(config.minCycleMs)
            }
        } finally {
            disconnect()
            if (abortRequested || closed) failQueued(unreachable ?: "Disconnected")
            _status.value = AcquisitionStatus(AcquisitionState.Stopped, 0.0, errorCount, unreachable)
            if (_link.value == LinkState.Pending) _link.value = LinkState.Unreachable
        }
    }

    /**
     * Why a group could not be read. [link] = the car did not answer or the transmission failed
     * (counts towards a reconnection); otherwise the ECU answered but these values are not
     * available (refused service, missing result, SGBD concept not implemented...).
     */
    private class GroupFailure(val text: String, val link: Boolean)

    /** Reads one group and publishes its samples; returns null on success. */
    private fun readGroup(ed: EdiabasNet, group: Group): GroupFailure? = try {
        when (group) {
            is BlockGroup -> readBlock(group)
            is JobGroup -> readJob(ed, group)
        }
        null
    } catch (ex: DefineRejectedException) {
        if (group is BlockGroup) {
            refused(group, ex)
            null
        } else {
            GroupFailure(ex.message ?: "refused", link = false)
        }
    } catch (ex: MeasurementUnavailableException) {
        GroupFailure(ex.message ?: "not available", link = false)
    } catch (ex: TransportException) {
        if (isTimeout(ex)) statTimeouts++
        GroupFailure(ex.message ?: "transport error", link = true)
    } catch (ex: DefinitionLostException) {
        GroupFailure(ex.message ?: "definition lost", link = true)
    } catch (ex: kotlinx.coroutines.CancellationException) {
        throw ex
    } catch (ex: Exception) {
        if (isTimeout(ex)) statTimeouts++
        GroupFailure(MesswertBlockReader.errorText(ex), link = true)
    }

    private fun readBlock(group: BlockGroup) {
        val reader = group.reader
        val definesBefore = reader.defines
        try {
            // like TestO / Deep OBD: the whole job with JA (clear, define, read) on every read,
            // never a record defined once and read back to back
            val values = reader.defineAndRead()
            publish(values.flatMap { (row, sample) -> group.byRow[row.id].orEmpty().map { it.id to sample } })
        } finally {
            statDefines += reader.defines - definesBefore
        }
    }

    private fun readJob(ed: EdiabasNet, group: JobGroup) {
        val file = catalog.sgbdFile(group.sgbd)?.name ?: group.sgbd
        if (!ed.sgbdFileName.equals(file, ignoreCase = true)) ed.sgbdFileName = file
        val sets: List<Map<String, ResultData>> = try {
            ed.argString = group.args
            ed.executeJob(group.job)
            ed.resultSets ?: emptyList()
        } catch (ex: Exception) {
            if (isLinkFailure(ex)) throw TransportException(MesswertBlockReader.errorText(ex), ex)
            throw MeasurementUnavailableException(MesswertBlockReader.errorText(ex), ex)
        }
        val now = clock()
        val out = ArrayList<Pair<String, Sample>>()
        var missing: String? = null
        for (m in group.members) {
            val src = m.source as MeasurementSource.JobResult
            val r = sets.getOrNull(src.set)?.get(src.resultName.uppercase(Locale.ROOT))
            val s = r?.let { MesswertBlockReader.toSample(it, now) }
            if (s == null) missing = src.resultName else out += m.id to s
        }
        publish(out)
        if (out.isEmpty()) {
            val status = sets.lastOrNull()?.get("JOB_STATUS")?.opData
            // the job ran: the ECU answered, it just did not give these results
            throw MeasurementUnavailableException("$missing missing" + (status?.let { " (JOB_STATUS $it)" } ?: ""))
        }
    }

    private fun publish(values: List<Pair<String, Sample>>) {
        if (values.isEmpty()) return
        _samples.update { old -> HashMap(old).apply { values.forEach { (id, s) -> put(id, s) } } }
        _readings.tryEmit(values)
    }

    /**
     * The ECU (or the SGBD) refused a block definition: find ids it does not support by defining
     * them one by one; if all are fine individually, the block is too big for this ECU.
     */
    private fun refused(group: BlockGroup, ex: DefineRejectedException) {
        val rows = group.reader.rows
        val refusedHere = refusedRows.getOrPut(group.sgbd) { HashSet() }
        if (rows.size == 1) {
            refusedHere += rows[0].id
        } else {
            val ed = ediabas!!
            for (row in rows) {
                val single = MesswertBlockReader(ed, iface, group.reader.sgbdFile, group.reader.tab, listOf(row), clock)
                statDefines++
                try {
                    val values = single.defineAndRead()
                    publish(values.flatMap { (r, s) -> group.byRow[r.id].orEmpty().map { it.id to s } })
                } catch (_: DefineRejectedException) {
                    refusedHere += row.id
                }
            }
            if (rows.none { it.id in refusedHere }) {
                val limit = (rows.size / 2).coerceAtLeast(1)
                blockLimit[group.sgbd] = limit
            }
        }
        _unsupported.update { old -> old + refusedHere.flatMap { id -> group.byRow[id].orEmpty().map { it.id } } }
        _unavailable.update { old -> old + _unsupported.value.associateWith { "not supported by the ECU" } }
        plannedFor = null // replan on the next cycle
        lastRefusal = "${group.sgbd}: ${ex.jobStatus.ifBlank { ex.message }}"
    }

    private var lastRefusal: String? = null

    private fun replan(ed: EdiabasNet, wanted: List<Measurement>) {
        val maxIds = config.maxIdsPerBlock.coerceIn(1, MesswertBlockReader.MaxIdsPerDefinition)
        val old = plan
        val groups = ArrayList<Group>()
        // ---- MESSWERTETAB blocks per SGBD ----
        val tableBySgbd = LinkedHashMap<String, MutableList<Measurement>>()
        val jobs = LinkedHashMap<Triple<String, String, String>, MutableList<Measurement>>()
        val unknown = HashSet<String>()
        for (m in wanted) {
            when (val src = m.source) {
                is MeasurementSource.MeasurementTable ->
                    tableBySgbd.getOrPut(EdiabasMeasurementCatalog.normalize(m.sgbd)) { ArrayList() } += m
                is MeasurementSource.JobResult ->
                    if (ReadOnlyJobs.isReadOnlyName(src.job)) {
                        jobs.getOrPut(Triple(EdiabasMeasurementCatalog.normalize(m.sgbd), src.job.uppercase(Locale.ROOT), src.args)) { ArrayList() } += m
                    } else {
                        unknown += m.id // never run a job that could act on the car
                    }
                is MeasurementSource.Sensor -> Unit // phone sensors (GPS) are not read on the cable
            }
        }
        for ((sgbd, members) in tableBySgbd) {
            val tab = catalog.table(sgbd)
            val file = catalog.sgbdFile(sgbd)
            if (tab == null || file == null) {
                members.forEach { unknown += it.id }
                continue
            }
            val refused = refusedRows[sgbd].orEmpty()
            val byRow = LinkedHashMap<Int, MutableList<Measurement>>()
            for (m in members) {
                val id = (m.source as MeasurementSource.MeasurementTable).id
                if (tab.row(id) == null || id in refused) unknown += m.id else byRow.getOrPut(id) { ArrayList() } += m
            }
            val rows = byRow.keys.map { tab.row(it)!! }
            val maxLimit = minOf(blockLimit[sgbd] ?: maxIds, maxIds).coerceIn(1, MesswertBlockReader.MaxIdsPerDefinition)
            // balanced blocks (20 values with 16 per block = 10 + 10, not 16 + 4): they rotate at
            // the same rate
            val blockCount = if (rows.isEmpty()) 1 else (rows.size + maxLimit - 1) / maxLimit
            val limit = if (rows.isEmpty()) maxLimit else (rows.size + blockCount - 1) / blockCount
            val chunks = ArrayList<List<MesswertRow>>()
            var current = ArrayList<MesswertRow>()
            var bytes = 0
            for (row in rows) {
                if (current.size >= limit || bytes + row.dataType.size > MesswertBlockReader.MaxRecordBytes) {
                    chunks += current
                    current = ArrayList()
                    bytes = 0
                }
                current += row
                bytes += row.dataType.size
            }
            if (current.isNotEmpty()) chunks += current
            for (chunk in chunks) {
                // keep the reader (and the ECU definition) when the block did not change
                val reuse = old.firstOrNull { it is BlockGroup && it.sgbd == sgbd && it.reader.rows == chunk } as BlockGroup?
                val reader = reuse?.reader ?: MesswertBlockReader(ed, iface, file.name, tab, chunk, clock)
                groups += BlockGroup(sgbd, reader, chunk.associate { r -> r.id to byRow[r.id]!!.toList() })
            }
        }
        for ((key, members) in jobs) groups += JobGroup(key.first, key.second, key.third, members)
        plan = groups
        plannedFor = wanted
        val wantedIds = wanted.map { it.id }.toSet()
        _unsupported.update { (it + unknown).intersect(wantedIds) }
        _unavailable.update { old -> old.filterKeys { it in wantedIds } + _unsupported.value.associateWith { "not supported by the ECU" } }
    }

    private fun connect(): String? {
        return try {
            if (iface.interfaceConnect()) {
                connected = true
                // a (re)connected interface has no communication parameters: make the interpreter
                // run INITIALISIERUNG again before the next job, and redefine every block
                ediabas?.closeSgbd()
                plan.forEach { if (it is BlockGroup) it.reader.invalidate() }
                null
            } else {
                connectError() ?: "interface connect failed"
            }
        } catch (ex: Exception) {
            listOfNotNull(MesswertBlockReader.errorText(ex), connectError()).distinct().joinToString(" - ")
        }
    }

    private fun connectError(): String? = (iface as? EdInterfaceObd)?.lastConnectError

    private fun disconnect() {
        if (!connected) return
        connected = false
        runCatching { iface.interfaceDisconnect() }
    }

    private fun effectiveNow(): List<Measurement> = (_measurements.value + _pinned.value).distinctBy { it.id }

    /** Start-to-start period of a job group that took [durationMs], when it shares the interface. */
    internal fun jobPeriod(durationMs: Long): Long =
        maxOf(config.jobMinIntervalMs, (durationMs.coerceAtLeast(0) * (1.0 + config.jobIntervalFactor)).toLong())
            .coerceAtMost(maxOf(config.jobMaxIntervalMs, config.jobMinIntervalMs))

    /**
     * Next try of a job the car answered but did not give: 5 s, then up to 60 s; a protocol this
     * app does not implement (IFH-0014) will not change during the session: every 5 min.
     */
    private fun unavailableRetryMs(failures: Int, error: String?): Long = when {
        error?.contains("IFH-0014") == true -> 300_000
        else -> (5_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(60_000)
    }

    private fun backoff(failures: Int): Long =
        (config.retryDelayMinMs shl (failures - 1).coerceIn(0, 16)).coerceAtMost(config.retryDelayMaxMs)

    /** Waits [ms], or until the measurement set changes or a one-off operation is queued. */
    private suspend fun waitOrChange(ms: Long) {
        val sig = opSignal.value
        if (hasOps()) return
        withTimeoutOrNull(ms) { merge(effective.drop(1).map { }, opSignal.filter { it != sig }.map { }).first() }
    }

    /** Earliest start of the next read of [sgbd] allowed by [currentReadIntervalMs]. */
    private fun nextReadAt(sgbd: String): Long = sgbdNextAt[sgbd] ?: 0L

    // ---- read statistics (ReadStats), engine thread; read approximately from other threads ----
    private val intervals = LongArray(ReadStats.WINDOW)
    @Volatile private var intervalCount = 0
    @Volatile private var totalCycles = 0L
    @Volatile private var slowCycles = 0L
    private var lastCycleAt = 0L
    @Volatile private var statErrors = 0
    @Volatile private var statLastError: String? = null
    @Volatile private var statDefines = 0L
    @Volatile private var statTimeouts = 0L

    /** How the read loop is doing (block modes, cycle times, errors, pace) since connecting. */
    fun readStats(): ReadStats {
        val n = intervalCount.coerceAtMost(ReadStats.WINDOW)
        val (median, p90) = ReadStats.percentiles(intervals.copyOf(n))
        val blocks = plan.filterIsInstance<BlockGroup>().map { g ->
            ReadStats.BlockStats(g.sgbd, g.reader.rows.size, g.reader.mode.name, g.reader.reads, g.reader.defines, g.reader.interpretedReason)
        }
        return ReadStats(
            blocks, totalCycles, slowCycles, median, p90, statErrors, statLastError,
            defines = statDefines, timeouts = statTimeouts,
            readsPerSecond = cyclesPerSecondApprox(),
            readIntervalMs = readIntervalMs, slowDowns = slowDowns,
        )
    }

    /** [cyclesPerSecond] from another thread (reads a copy of the window, never modifies it). */
    private fun cyclesPerSecondApprox(): Double {
        val times = synchronized(cycleTimes) { cycleTimes.toList() }
        val now = clock()
        val recent = times.filter { now - it <= config.statusWindowMs }
        if (recent.size < 2) return 0.0
        val span = recent.last() - recent.first()
        return if (span <= 0) 0.0 else (recent.size - 1) * 1000.0 / span
    }

    private fun markCycle() {
        val now = clock()
        if (lastCycleAt > 0) {
            val ms = now - lastCycleAt
            intervals[intervalCount % ReadStats.WINDOW] = ms
            intervalCount++
            totalCycles++
            if (ms > ReadStats.SLOW_MS) slowCycles++
        }
        lastCycleAt = now
        synchronized(cycleTimes) {
            cycleTimes.addLast(now)
            while (cycleTimes.size > 2 && now - cycleTimes.first() > config.statusWindowMs) cycleTimes.removeFirst()
            while (cycleTimes.size > 500) cycleTimes.removeFirst()
        }
    }

    private fun cyclesPerSecond(): Double = synchronized(cycleTimes) {
        val now = clock()
        while (cycleTimes.isNotEmpty() && now - cycleTimes.first() > config.statusWindowMs) cycleTimes.removeFirst()
        if (cycleTimes.size < 2) return 0.0
        val span = cycleTimes.last() - cycleTimes.first()
        if (span <= 0) 0.0 else (cycleTimes.size - 1) * 1000.0 / span
    }

    private fun idleMessage(): String? =
        if (_unsupported.value.isNotEmpty()) "Not supported: ${_unsupported.value.joinToString()}" else null

    private fun planMessage(error: String?): String {
        val parts = plan.map { g ->
            when (g) {
                is BlockGroup -> "${g.label} (" + when {
                    g.reader.defines > 0 -> "redefined each read"
                    else -> "defining"
                } + ")"
                is JobGroup -> g.label
            }
        }
        val extra = listOfNotNull(
            error,
            lastRefusal,
            _unsupported.value.takeIf { it.isNotEmpty() }?.let { "not supported: ${it.joinToString()}" },
        )
        return (parts + extra).joinToString("; ")
    }
}

/** Read groups buffered for [LiveDataSource.readings] (about 6 min at 22 groups/s before the oldest would be dropped). */
private const val READINGS_BUFFER = 8192

/** A job measurement is not available although the car answered (see [AcquisitionEngine]). */
class MeasurementUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * True when [ex] means the car did not answer or the transmission failed (EDIABAS IFH-0002
 * interface, IFH-0003 transmission, IFH-0009 no ECU response, IFH-0010 data error), as opposed to
 * an ECU or SGBD level refusal.
 */
internal fun isLinkFailure(ex: Throwable): Boolean {
    var e: Throwable? = ex
    while (e != null) {
        if (e is io.github.jonathangtd.bimmerdata.ediabas.EdiabasNetException) {
            val c = e.errorCode
            return c == io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes.EDIABAS_IFH_0002 ||
                c == io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes.EDIABAS_IFH_0003 ||
                c == io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes.EDIABAS_IFH_0009 ||
                c == io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes.EDIABAS_IFH_0010
        }
        if (e is java.io.IOException) return true
        e = e.cause
    }
    return false
}

/** True when [ex] means nobody answered (EDIABAS IFH-0009: no response from the control unit). */
internal fun isTimeout(ex: Throwable): Boolean {
    var e: Throwable? = ex
    while (e != null) {
        if (e is io.github.jonathangtd.bimmerdata.ediabas.EdiabasNetException) {
            return e.errorCode == io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes.EDIABAS_IFH_0009
        }
        if (e.message?.contains("IFH-0009") == true) return true
        e = e.cause
    }
    return false
}
