/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dyno

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.jonathangtd.bimmerdata.cars.CarBook
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementClient
import io.github.jonathangtd.bimmerdata.live.ProfileMeasurements
import io.github.jonathangtd.bimmerdata.live.ValueKind
import io.github.jonathangtd.bimmerdata.live.dyno.DynoDetector
import io.github.jonathangtd.bimmerdata.live.dyno.DynoEvent
import io.github.jonathangtd.bimmerdata.live.dyno.DynoParams
import io.github.jonathangtd.bimmerdata.live.dyno.DynoRole
import io.github.jonathangtd.bimmerdata.live.vehicle.CarPhysics
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** The dyno's runs and inputs, `files/dyno.json` (atomic writes), cached in memory. */
class DynoStore(context: Context, private val file: AtomicFile = AtomicFile(File(context.filesDir, "dyno.json"))) {
    private val lock = Any()
    private var cached: DynoBook? = null
    private val _book = MutableStateFlow(DynoBook())
    val flow: StateFlow<DynoBook> = _book.asStateFlow()

    fun load(): DynoBook = synchronized(lock) {
        cached ?: read().also { cached = it; _book.value = it }
    }

    private fun read(): DynoBook {
        val text = runCatching { file.readFully().toString(Charsets.UTF_8) }.getOrNull() ?: return DynoBook()
        return runCatching { DynoJson.decode(text) }.onFailure { Log.w(TAG, "Unreadable dyno.json, starting empty", it) }.getOrDefault(DynoBook())
    }

    /** Applies [op] and writes the file; a failed write throws and keeps the old content. */
    fun update(op: (DynoBook) -> DynoBook): DynoBook = synchronized(lock) {
        val next = op(load())
        val out = file.startWrite()
        try {
            out.write(DynoJson.encode(next).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        cached = next
        _book.value = next
        next
    }

    private companion object {
        const val TAG = "DynoStore"
    }
}

/**
 * The virtual dyno on the phone: while armed (or during a manual run) it asks the acquisition for
 * its inputs ([MeasurementClient.Dyno], so they are read even when the active profile does not show
 * them), gives every reading to a [DynoDetector] and keeps each finished run in [DynoStore].
 *
 * It never changes how fast the car is read: the read pace and the automatic slow-downs of the
 * acquisition stay as they are; a profile holding only the dyno's values reads them the fastest.
 */
class DynoRuntime private constructor(context: Context) : DynoControl {
    private val app = context.applicationContext
    private val repo = LiveRepository.get(app)
    private val cars = CarBook.get(app)
    val store = DynoStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val _state = MutableStateFlow(DynoLiveState())
    override val state: StateFlow<DynoLiveState> = _state.asStateFlow()

    private class Session(
        val carId: String,
        val roles: Map<String, List<DynoRole>>,
        val rpm: DynoRunInput,
        val speed: DynoRunInput,
        val pedal: DynoRunInput?,
        val overlay: DynoRunInput?,
        val physics: CarPhysics,
        val lossPct: Double,
        val profileName: String?,
    )

    // ---- guarded by lock ----
    private var session: Session? = null
    private var detector: DynoDetector? = null
    private var collector: Job? = null
    private var manualOnly = false
    private var runManual = false
    private var readings = 0L
    private var lastRpm: Double? = null
    private var lastPedal: Double? = null
    private var lastRunId: String? = null
    private var lastDiscarded: Int? = null

    override fun book(): DynoBook = store.load()

    override fun candidates(): List<Measurement> {
        val profile = ProfileMeasurements.current.filter { it.kind == ValueKind.Number }
        val gps = runCatching { repo.catalog.measurements("gps") }.getOrDefault(emptyList()).filter(DynoDefaults::isSpeed)
        return (profile + gps).distinctBy { it.id }
    }

    override fun resolvedInputs(carId: String): DynoInputs = DynoDefaults.resolve(inputs(carId), candidates())

    override fun setInputs(carId: String, inputs: DynoInputs) {
        require(inputs.drivelineLossPct in DynoParams.LOSS_RANGE) { "drivelineLossPct must be in ${DynoParams.LOSS_RANGE.start}..${DynoParams.LOSS_RANGE.endInclusive}" }
        store.update { it.copy(inputs = it.inputs + (carId to inputs)) }
    }

    private fun measurement(id: String): Measurement? =
        candidates().firstOrNull { it.id == id }
            ?: runCatching { repo.catalog.measurements(id.substringBefore('/')).firstOrNull { it.id == id } }.getOrNull()

    private fun Measurement.input() = DynoRunInput(id, label, unit)

    /** The selected car's session: its mass and inputs are checked, the inputs requested. */
    private fun prepare(): Session {
        val space = cars.snapshot()
        val car = space.selected ?: throw DynoNotReady("no_car", "No car selected: add or choose a car first")
        if (car.physics.massKg == null) {
            throw DynoNotReady("car_mass_missing", "Set the mass of '${car.label}' first (My cars › ⋮ › Mass and drag, or PUT /api/v1/cars/${car.id} {\"physics\":{\"massKg\":…}})")
        }
        val inputs = resolvedInputs(car.id)
        val rpm = inputs.rpm?.let(::measurement)?.takeIf(DynoDefaults::isRpm)
            ?: throw DynoNotReady("dyno_inputs_missing", "Choose an engine speed value (unit rpm) for the dyno: put one in the active profile or set it with PUT /api/v1/dyno/settings")
        val speed = inputs.speed?.let(::measurement)?.takeIf(DynoDefaults::isSpeed)
            ?: throw DynoNotReady("dyno_inputs_missing", "Choose a vehicle speed value (unit km/h, an ECU's or the GPS) for the dyno")
        val pedal = inputs.pedal?.let(::measurement)?.takeIf(DynoDefaults::isPercent)
        val overlay = inputs.overlay?.let(::measurement)?.takeIf(DynoDefaults::isOverlay)
        val roles = HashMap<String, MutableList<DynoRole>>()
        roles.getOrPut(rpm.id, ::ArrayList) += DynoRole.Rpm
        roles.getOrPut(speed.id, ::ArrayList) += DynoRole.Speed
        pedal?.let { roles.getOrPut(it.id, ::ArrayList) += DynoRole.Pedal }
        overlay?.let { roles.getOrPut(it.id, ::ArrayList) += DynoRole.Overlay }
        repo.requests.set(MeasurementClient.Dyno, listOfNotNull(rpm, speed, pedal, overlay).distinctBy { it.id }, foreground = true)
        return Session(
            car.id, roles, rpm.input(), speed.input(), pedal?.input(), overlay?.input(), car.physics, inputs.drivelineLossPct,
            ProfileStore.state.value?.active?.name,
        )
    }

    private fun open(): Pair<Session, DynoDetector> {
        val s = session ?: prepare().also { s ->
            session = s
            readings = 0
            collect(s)
        }
        val d = detector ?: DynoDetector(hasPedal = s.pedal != null).also { detector = it }
        return s to d
    }

    override fun arm() = synchronized(lock) {
        open().second.arm()
        manualOnly = false
        publish()
    }

    override fun start() = synchronized(lock) {
        val (_, d) = open()
        if (d.state == DynoDetector.State.Idle) manualOnly = true
        handle(d.start(System.currentTimeMillis()))
        publish()
    }

    override fun stop() = synchronized(lock) {
        handle(detector?.stop(System.currentTimeMillis()))
        publish()
    }

    override fun disarm() = synchronized(lock) {
        handle(detector?.disarm(System.currentTimeMillis()))
        close()
        publish()
    }

    override fun deleteRun(id: String): Boolean {
        if (store.load().run(id) == null) return false
        store.update { DynoRuns.delete(it, id) }
        return true
    }

    override fun renameRun(id: String, note: String): Boolean {
        if (store.load().run(id) == null) return false
        store.update { DynoRuns.rename(it, id, note) }
        return true
    }

    private fun close() {
        collector?.cancel()
        collector = null
        detector = null
        session = null
        lastRpm = null
        lastPedal = null
        repo.requests.clear(MeasurementClient.Dyno)
    }

    private fun collect(s: Session) {
        collector?.cancel()
        collector = scope.launch {
            launch {
                repo.liveDataSource.readings.collect { list ->
                    synchronized(lock) {
                        if (session !== s) return@synchronized
                        var any = false
                        for ((id, sample) in list) {
                            val roles = s.roles[id] ?: continue
                            val v = sample.value ?: continue
                            any = true
                            for (role in roles) {
                                readings++
                                if (role == DynoRole.Rpm) lastRpm = v
                                if (role == DynoRole.Pedal) lastPedal = v
                                handle(detector?.onValue(role, sample.timestampMs, v))
                            }
                        }
                        if (any) publish()
                    }
                }
            }
            launch {
                while (isActive) {
                    delay(500)
                    synchronized(lock) {
                        if (session === s) {
                            handle(detector?.tick(System.currentTimeMillis()))
                            publish()
                        }
                    }
                }
            }
        }
    }

    /** Keeps a finished run; a manual run that was not armed releases the inputs afterwards. */
    private fun handle(event: DynoEvent?) {
        val s = session ?: return
        when (event) {
            null -> return
            is DynoEvent.Started -> {
                runManual = event.manual
                return
            }
            is DynoEvent.Finished -> {
                val run = DynoRuns.of(
                    s.carId, event.capture, event.startedAtMs, event.endedAtMs, event.end, runManual,
                    s.profileName, s.rpm, s.speed, s.pedal, s.overlay, s.physics, s.lossPct,
                )
                lastRunId = run.id
                lastDiscarded = null
                scope.launch(Dispatchers.IO) {
                    runCatching { store.update { DynoRuns.add(it, run) } }.onFailure { Log.w(TAG, "Could not save the dyno run", it) }
                }
            }
            is DynoEvent.Discarded -> lastDiscarded = event.rpmSamples
        }
        if (detector?.state == DynoDetector.State.Idle) close()
    }

    private fun publish() {
        val d = detector
        val phase = when (d?.state) {
            DynoDetector.State.Armed -> DynoPhase.Armed
            DynoDetector.State.Running -> DynoPhase.Running
            else -> DynoPhase.Idle
        }
        _state.value = DynoLiveState(
            phase = phase, carId = session?.carId, runSamples = d?.runSamples ?: 0,
            runStartedAtMs = _state.value.runStartedAtMs.takeIf { phase == DynoPhase.Running } ?: if (phase == DynoPhase.Running) System.currentTimeMillis() else null,
            lastRunId = lastRunId, lastDiscardedSamples = lastDiscarded, readings = readings, rpm = lastRpm, pedal = lastPedal,
        )
    }

    companion object {
        private const val TAG = "DynoRuntime"

        @android.annotation.SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: DynoRuntime? = null

        fun get(context: Context): DynoRuntime =
            instance ?: synchronized(this) { instance ?: DynoRuntime(context).also { instance = it } }
    }
}
