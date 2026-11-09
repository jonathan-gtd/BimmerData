/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.vehicle

import android.annotation.SuppressLint
import android.content.Context
import io.github.jonathangtd.bimmerdata.cars.EcuUsage
import io.github.jonathangtd.bimmerdata.files.EcuImporter
import io.github.jonathangtd.bimmerdata.live.CableUnavailableException
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.live.vehicle.CarMatch
import io.github.jonathangtd.bimmerdata.live.vehicle.DetectedEcuSource
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuSummary
import io.github.jonathangtd.bimmerdata.live.vehicle.EdiabasJobRunner
import io.github.jonathangtd.bimmerdata.live.vehicle.Fault
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultDetail
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultService
import io.github.jonathangtd.bimmerdata.live.vehicle.JobRunner
import io.github.jonathangtd.bimmerdata.live.vehicle.ScanMerge
import io.github.jonathangtd.bimmerdata.live.vehicle.UserConfirmation
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleScanner
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleTables
import io.github.jonathangtd.bimmerdata.live.vehicle.ecuSummaries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** Short VIN: the last 7 characters of the VIN (FG_NR). */
private val ShortVinPattern = Regex("^[A-Z0-9]{7}$")

/** What the Faults tab is doing with the car right now. */
sealed interface VehicleTask {
    data object Idle : VehicleTask
    data object Connecting : VehicleTask
    data class Scanning(val progress: VehicleScanner.Progress?) : VehicleTask
    data class ReadingFaults(val done: Int, val total: Int, val ecuName: String?) : VehicleTask
    data class ReadingEcu(val address: Int) : VehicleTask
    data class Clearing(val address: Int?) : VehicleTask
    data class ReadingDetail(val address: Int, val code: Int) : VehicleTask
}

/** A message for the user; [error] ones are shown in the danger color. */
data class VehicleMessage(val kind: Kind, val detail: String? = null) {
    enum class Kind {
        NoEcuFiles, NoCable, UsbDenied, ConnectFailed, ScanFailed, ScanCancelled, ReadFailed, Cleared, ClearFailed,
        NewVehicle, Cancelled, Detected,
    }

    val error: Boolean get() = kind !in setOf(Kind.Cleared, Kind.NewVehicle, Kind.Cancelled, Kind.ScanCancelled, Kind.Detected)
}

/**
 * ECUs of car [carId] the last detection did not find although its profiles use them: kept in its
 * list (as added by hand) until the user says whether to take them out of those profiles.
 */
data class MissingEcus(val carId: String, val sgbds: List<String>)

/** Outcome of the last detection that got a result: the car it went to, how many ECUs it found, those kept although not found. */
data class DetectResult(val carId: String, val ecus: Int, val kept: List<String>)

/** State of one fault detail (FS_LESEN_DETAIL). */
sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val detail: FaultDetail) : DetailState
    data class Failed(val message: String) : DetailState
}

data class VehicleState(
    val garage: Garage = Garage(),
    val task: VehicleTask = VehicleTask.Idle,
    val message: VehicleMessage? = null,
    /** Fault details read in this session, by [detailKey]. */
    val details: Map<String, DetailState> = emptyMap(),
    /** The question left by the last detection (null: none); the screens ask it ([dismissMissing] after the answer). */
    val missingEcus: MissingEcus? = null,
    /** Last successful detection (for the REST API), null before the first one. */
    val lastDetect: DetectResult? = null,
) {
    val busy: Boolean get() = task != VehicleTask.Idle
    val current: Vehicle? get() = garage.current
    val currentFaults: List<EcuFaults>? get() = garage.currentId?.let { garage.faults[it] }

    companion object {
        fun detailKey(vehicleId: String, address: Int, code: Int) = "$vehicleId/$address/$code"
    }
}

/**
 * Process-wide owner of the user's cars (persisted, several cars), the detection of their ECUs
 * (My cars) and the Faults tab: fault memory read / detail / clear.
 *
 * Cable: the app's single connection ([LiveRepository], cable or simulated E90). Every job is
 * slipped in between two read cycles of the live acquisition, which goes on; when nothing is
 * connected the first job connects in the last mode the user chose.
 *
 * Implements [DetectedEcuSource] for the rest of the app (ECUs of the current vehicle). Selecting,
 * renaming, adding and deleting cars go through [io.github.jonathangtd.bimmerdata.cars.CarBook]:
 * the profiles of each car change with it.
 */
class VehicleRepository private constructor(context: Context) : DetectedEcuSource {

    private val app: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val importer = EcuImporter(app)
    private val store = VehicleStore(app)

    private val _state = MutableStateFlow(VehicleState(garage = store.load()))
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    override val detectedEcus: StateFlow<List<EcuSummary>> = _state
        .map { it.garage.current.ecuSummaries() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, _state.value.garage.current.ecuSummaries())

    @Volatile private var job: Job? = null

    private var tablesCache: Pair<Long, VehicleTables>? = null

    /** ECU folder tables, re-read when the folder was imported again. */
    private fun tables(): VehicleTables {
        val stamp = importer.ecuDir.lastModified()
        tablesCache?.let { (s, t) -> if (s == stamp) return t }
        return VehicleTables(importer.ecuDir).also { tablesCache = stamp to it }
    }

    /**
     * The selected car's ECU list ([Vehicle.sgbds]) present in the ECU folder: the ECUs of the last
     * scan whose SGBD was found, then those added by hand. The card picker and the GET /ecus route
     * offer them first.
     */
    override fun sgbds(): List<String> = _state.value.garage.current?.let { v ->
        (v.ecus.filter { it.sgbdAvailable }.mapNotNull { it.sgbd } + v.addedSgbds).distinct()
    }.orEmpty()

    // ---- simple state changes ----

    fun garage(): Garage = _state.value.garage

    /**
     * Puts [next] in place of the garage if it still is [expected] (else false: something changed
     * it meanwhile), and saves it. For [io.github.jonathangtd.bimmerdata.cars.CarBook].
     */
    fun replaceGarage(expected: Garage, next: Garage): Boolean {
        while (true) {
            val prev = _state.value
            if (prev.garage != expected) return false
            if (_state.compareAndSet(prev, prev.copy(garage = next))) {
                save()
                return true
            }
        }
    }

    /** The user's choice of vehicle SGBD (series) for the next scans of the current vehicle. */
    fun setSeries(sgbd: String) = updateGarage { g ->
        g.copy(
            vehicles = g.vehicles.map { v ->
                if (v.id == g.currentId) v.copy(seriesSgbd = sgbd, series = VehicleTables.FamilyLabels[sgbd] ?: sgbd.uppercase(), seriesByUser = true) else v
            },
        )
    }

    /** Vehicle SGBDs present in the imported ECU folder (for the series choice). */
    fun availableSeriesSgbds(): List<String> = tables().vehicleSgbds

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun updateGarage(change: (Garage) -> Garage) {
        _state.update { it.copy(garage = change(it.garage)) }
        save()
    }

    /** Writes the garage as it is when the write runs, one write at a time: a later change is never overwritten by an earlier one. */
    private fun save() {
        scope.launch(Dispatchers.IO) { synchronized(store) { store.save(_state.value.garage) } }
    }

    private fun message(kind: VehicleMessage.Kind, detail: String? = null) = _state.update { it.copy(message = VehicleMessage(kind, detail)) }

    private fun task(t: VehicleTask) = _state.update { it.copy(task = t) }

    // ---- operations on the car ----

    /**
     * Detects the selected car's ECUs (full scan) and puts them in its ECU list
     * ([CarMatch.mergeScan]: the car with that VIN, the selected one added by hand, or a new car).
     * ECUs no longer found but used by the car's profiles stay in the list and are asked about
     * ([VehicleState.missingEcus]). The fault memories are read afterwards from the Faults tab.
     * Returns false when another operation runs (nothing started).
     */
    fun detect(): Boolean = launchOperation { runner, tables ->
        val previous = _state.value.current
        task(VehicleTask.Scanning(null))
        val preferred = previous?.takeIf { it.seriesByUser }?.seriesSgbd
        val result = VehicleScanner(runner, tables).scan(previous, preferred) { p -> task(VehicleTask.Scanning(p)) }
        val found = result.vehicle
        if (found == null) {
            message(if (result.cancelled) VehicleMessage.Kind.ScanCancelled else VehicleMessage.Kind.ScanFailed, result.error)
            return@launchOperation
        }
        val profiles = withContext(Dispatchers.IO) { ProfileStore.get(app).load() }.profiles
        var merge: ScanMerge? = null
        updateGarage { g ->
            val m = CarMatch.mergeScan(g.vehicles, g.currentId, found) { id -> EcuUsage.usedSgbds(profiles.filter { it.carId == id }) }
            merge = m
            g.copy(vehicles = m.vehicles, currentId = m.car.id)
        }
        val m = merge ?: return@launchOperation
        _state.update {
            it.copy(
                lastDetect = DetectResult(m.car.id, m.car.ecus.size, m.kept),
                missingEcus = m.kept.takeIf { k -> k.isNotEmpty() }?.let { k -> MissingEcus(m.car.id, k) },
            )
        }
        if (m.isNew && previous != null) message(VehicleMessage.Kind.NewVehicle, m.car.label)
        else message(VehicleMessage.Kind.Detected, m.car.ecus.size.toString())
    }

    /** The user answered about the first ECU of [VehicleState.missingEcus]. */
    fun dismissMissing() = _state.update { s ->
        val rest = s.missingEcus?.let { it.copy(sgbds = it.sgbds.drop(1)) }?.takeIf { it.sgbds.isNotEmpty() }
        s.copy(missingEcus = rest)
    }

    /** Reads the fault memory of every ECU of the current vehicle. */
    fun readFaults() = launchOperation { runner, tables ->
        val vehicle = _state.value.current ?: return@launchOperation
        readAllFaults(runner, tables, vehicle)
    }

    private suspend fun readAllFaults(runner: JobRunner, tables: VehicleTables, vehicle: Vehicle) {
        task(VehicleTask.ReadingFaults(0, vehicle.ecus.size, null))
        val faults = FaultService(runner, tables).readAll(vehicle) { p -> task(VehicleTask.ReadingFaults(p.done, p.total, p.ecuName)) }
        storeFaults(vehicle.id, faults)
    }

    /** Reads one ECU again. */
    fun readEcu(address: Int) = launchOperation { runner, tables ->
        val vehicle = _state.value.current ?: return@launchOperation
        val ecu = vehicle.ecu(address) ?: return@launchOperation
        task(VehicleTask.ReadingEcu(address))
        val faults = FaultService(runner, tables).readEcu(ecu)
        mergeFaults(vehicle.id, faults)
    }

    /** FS_LESEN_DETAIL of one fault. */
    fun readDetail(address: Int, fault: Fault) {
        val vehicle = _state.value.current ?: return
        val key = VehicleState.detailKey(vehicle.id, address, fault.code)
        launchOperation { runner, tables ->
            val ecu = vehicle.ecu(address) ?: return@launchOperation
            task(VehicleTask.ReadingDetail(address, fault.code))
            _state.update { it.copy(details = it.details + (key to DetailState.Loading)) }
            val result = FaultService(runner, tables).detail(ecu, fault)
            _state.update {
                it.copy(details = it.details + (key to result.fold({ d -> DetailState.Loaded(d) }, { e -> DetailState.Failed(e.message ?: "?") })))
            }
        }
    }

    /** FS_LOESCHEN on one ECU (after the confirmation dialog), then reads it again. */
    fun clearEcu(address: Int, confirmation: UserConfirmation) = launchOperation { runner, tables ->
        val vehicle = _state.value.current ?: return@launchOperation
        val ecu = vehicle.ecu(address) ?: return@launchOperation
        task(VehicleTask.Clearing(address))
        val after = FaultService(runner, tables).clearEcu(ecu, confirmation)
        mergeFaults(vehicle.id, after)
        dropDetails(vehicle.id, address)
        message(if (after.message?.startsWith("Clear failed") == true) VehicleMessage.Kind.ClearFailed else VehicleMessage.Kind.Cleared, ecu.name)
    }

    /** FS_LOESCHEN_FUNKTIONAL (after the confirmation dialog), then reads everything again. */
    fun clearAll(confirmation: UserConfirmation) = launchOperation { runner, tables ->
        val vehicle = _state.value.current ?: return@launchOperation
        task(VehicleTask.Clearing(null))
        val after = FaultService(runner, tables).clearAll(vehicle, confirmation) { p -> task(VehicleTask.ReadingFaults(p.done, p.total, p.ecuName)) }
        storeFaults(vehicle.id, after)
        dropDetails(vehicle.id, null)
        message(VehicleMessage.Kind.Cleared, null)
    }

    // ---- light checks at connection (profiles) ----

    /**
     * Short VIN of the connected car, from one read-only functional job (C_FG_LESEN_FUNKTIONAL of
     * the vehicle SGBDs of the known cars, or of the ECU folder). Never connects by itself and
     * gives up (null) while another vehicle operation runs or when nothing answers.
     */
    suspend fun readShortVin(): String? {
        val live = LiveRepository.get(app)
        if (!live.isConnected || importer.importedFiles().isEmpty()) return null
        if (!mutex.tryLock()) return null
        try {
            val tables = withContext(Dispatchers.IO) { tables() }
            val known = _state.value.garage.let { g -> listOfNotNull(g.current?.seriesSgbd) + g.vehicles.mapNotNull { it.seriesSgbd } }
            val candidates = (known + tables.vehicleSgbds).distinct().filter { tables.hasJob(it, "C_FG_LESEN_FUNKTIONAL") }.take(3)
            val runner = live.jobRunner("Profiles")
            for (sgbd in candidates) {
                val out = runCatching { runner.run(sgbd, "C_FG_LESEN_FUNKTIONAL") }.getOrNull() ?: continue
                val vin = out.sets.mapNotNull { set -> (set["FG_NR"]?.opData as? String)?.trim()?.uppercase(Locale.ROOT)?.takeIf { ShortVinPattern.matches(it) } }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                if (vin != null) return vin
            }
            return null
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Which of [sgbds] give no answer to IDENT (read-only) on the connected car. SGBDs without an
     * IDENT job or missing from the ECU folder are not probed. Empty when it could not check.
     */
    suspend fun probeEcus(sgbds: List<String>): List<String> {
        val live = LiveRepository.get(app)
        if (!live.isConnected || sgbds.isEmpty() || importer.importedFiles().isEmpty()) return emptyList()
        if (!mutex.tryLock()) return emptyList()
        try {
            val tables = withContext(Dispatchers.IO) { tables() }
            val runner = live.jobRunner("Profiles")
            return sgbds.filter { sgbd ->
                tables.prgExists(sgbd) && tables.hasJob(sgbd, "IDENT") &&
                    runCatching { runner.run(sgbd, "IDENT").noResponse }.getOrDefault(false)
            }
        } finally {
            mutex.unlock()
        }
    }

    // ---- for the REST API (scope diag): run now, return the result, throw on error ----

    /**
     * Reads the fault memory of every ECU ([address] null) or of one, under the same lock as the
     * screen's operations, and stores it like a read from the Faults tab. Throws
     * [IllegalStateException] (no ECU files, no vehicle, no such ECU) or [CableUnavailableException].
     */
    suspend fun readFaultsForApi(address: Int?): List<EcuFaults> = forApi { runner, tables, vehicle ->
        val service = FaultService(runner, tables)
        if (address == null) {
            task(VehicleTask.ReadingFaults(0, vehicle.ecus.size, null))
            service.readAll(vehicle) { p -> task(VehicleTask.ReadingFaults(p.done, p.total, p.ecuName)) }.also { storeFaults(vehicle.id, it) }
        } else {
            val ecu = vehicle.ecu(address) ?: throw IllegalStateException("No ECU at address 0x%02X".format(address))
            task(VehicleTask.ReadingEcu(address))
            listOf(service.readEcu(ecu).also { mergeFaults(vehicle.id, it) })
        }
    }

    /** Clears one ECU ([address]) or all (null) after the user's [confirmation] on the phone, then reads again. */
    suspend fun clearForApi(address: Int?, confirmation: UserConfirmation): List<EcuFaults> = forApi { runner, tables, vehicle ->
        val service = FaultService(runner, tables)
        task(VehicleTask.Clearing(address))
        if (address == null) {
            service.clearAll(vehicle, confirmation) { p -> task(VehicleTask.ReadingFaults(p.done, p.total, p.ecuName)) }
                .also { storeFaults(vehicle.id, it); dropDetails(vehicle.id, null) }
        } else {
            val ecu = vehicle.ecu(address) ?: throw IllegalStateException("No ECU at address 0x%02X".format(address))
            listOf(service.clearEcu(ecu, confirmation).also { mergeFaults(vehicle.id, it); dropDetails(vehicle.id, address) })
        }
    }

    private suspend fun <T> forApi(block: suspend (JobRunner, VehicleTables, Vehicle) -> T): T = mutex.withLock {
        check(importer.importedFiles().isNotEmpty()) { "No ECU files imported" }
        val vehicle = _state.value.current?.takeIf { it.ecus.isNotEmpty() } ?: throw IllegalStateException("No ECU detected yet: detect the car's ECUs first (My cars, or POST /api/v1/cars/{car}/detect)")
        val tables = withContext(Dispatchers.IO) { tables().also { it.vehicleSgbds } }
        try {
            block(LiveRepository.get(app).jobRunner("API"), tables, vehicle)
        } finally {
            task(VehicleTask.Idle)
        }
    }

    /** Stops the running operation (the job on the cable is aborted between telegrams). */
    fun cancel() {
        job?.cancel()
    }

    private fun storeFaults(vehicleId: String, faults: List<EcuFaults>) = updateGarage { it.copy(faults = it.faults + (vehicleId to faults)) }

    private fun mergeFaults(vehicleId: String, one: EcuFaults) = updateGarage { g ->
        val list = g.faults[vehicleId].orEmpty().filterNot { it.address == one.address } + one
        g.copy(faults = g.faults + (vehicleId to list.sortedBy { it.address }))
    }

    private fun dropDetails(vehicleId: String, address: Int?) = _state.update { s ->
        val prefix = if (address == null) "$vehicleId/" else "$vehicleId/$address/"
        s.copy(details = s.details.filterKeys { !it.startsWith(prefix) })
    }

    /** Runs [block] with a connected runner; one operation at a time (false: another one runs, nothing started). */
    private fun launchOperation(block: suspend (JobRunner, VehicleTables) -> Unit): Boolean {
        if (job?.isActive == true) return false
        job = scope.launch {
            mutex.withLock {
                try {
                    withLink(block)
                } catch (_: CancellationException) {
                    message(VehicleMessage.Kind.Cancelled)
                } catch (ex: Exception) {
                    message(VehicleMessage.Kind.ReadFailed, EdiabasJobRunner.describe(ex))
                } finally {
                    task(VehicleTask.Idle)
                }
            }
        }
        return true
    }

    private suspend fun withLink(block: suspend (JobRunner, VehicleTables) -> Unit) {
        if (importer.importedFiles().isEmpty()) {
            message(VehicleMessage.Kind.NoEcuFiles)
            return
        }
        val tables = withContext(Dispatchers.IO) { tables().also { it.vehicleSgbds } }
        val live = LiveRepository.get(app)
        if (!live.isConnected) task(VehicleTask.Connecting)
        try {
            block(live.jobRunner("Faults"), tables)
        } catch (ex: CableUnavailableException) {
            when (ex.message) {
                LiveRepository.NoCableMessage -> message(VehicleMessage.Kind.NoCable)
                LiveRepository.UsbDeniedMessage -> message(VehicleMessage.Kind.UsbDenied)
                else -> message(VehicleMessage.Kind.ConnectFailed, ex.message)
            }
        }
    }

    companion object {
        // holds only the application context
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: VehicleRepository? = null

        fun get(context: Context): VehicleRepository =
            instance ?: synchronized(this) { instance ?: VehicleRepository(context).also { instance = it } }
    }
}
