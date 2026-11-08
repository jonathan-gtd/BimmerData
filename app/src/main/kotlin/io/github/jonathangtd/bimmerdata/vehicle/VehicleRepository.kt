/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.vehicle

import android.annotation.SuppressLint
import android.content.Context
import io.github.jonathangtd.bimmerdata.files.EcuImporter
import io.github.jonathangtd.bimmerdata.live.CableUnavailableException
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.vehicle.DetectedEcuSource
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuSummary
import io.github.jonathangtd.bimmerdata.live.vehicle.EdiabasJobRunner
import io.github.jonathangtd.bimmerdata.live.vehicle.Fault
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultDetail
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultService
import io.github.jonathangtd.bimmerdata.live.vehicle.JobRunner
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
        NewVehicle, Cancelled,
    }

    val error: Boolean get() = kind !in setOf(Kind.Cleared, Kind.NewVehicle, Kind.Cancelled, Kind.ScanCancelled)
}

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
) {
    val busy: Boolean get() = task != VehicleTask.Idle
    val current: Vehicle? get() = garage.current
    val currentFaults: List<EcuFaults>? get() = garage.currentId?.let { garage.faults[it] }

    companion object {
        fun detailKey(vehicleId: String, address: Int, code: Int) = "$vehicleId/$address/$code"
    }
}

/**
 * Process-wide owner of the Faults tab: the user's vehicles (persisted, several cars), vehicle
 * scans, fault memory read / detail / clear.
 *
 * Cable: the app's single connection ([LiveRepository], cable or simulated E90). Every job is
 * slipped in between two read cycles of the live acquisition, which goes on; when nothing is
 * connected the first job connects in the last mode the user chose.
 *
 * Implements [DetectedEcuSource] for the rest of the app (ECUs of the current vehicle).
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

    // ---- simple state changes ----

    fun selectVehicle(id: String) = updateGarage { g -> if (g.vehicles.any { it.id == id }) g.copy(currentId = id) else g }

    fun renameVehicle(id: String, label: String) {
        val name = label.trim().take(40)
        if (name.isEmpty()) return
        updateGarage { g -> g.copy(vehicles = g.vehicles.map { if (it.id == id) it.copy(label = name) else it }) }
    }

    /** Forgets a vehicle and its last fault read. */
    fun deleteVehicle(id: String) = updateGarage { g ->
        val left = g.vehicles.filterNot { it.id == id }
        g.copy(vehicles = left, faults = g.faults - id, currentId = if (g.currentId == id) left.firstOrNull()?.id else g.currentId)
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
        val next = _state.updateAndGet { it.copy(garage = change(it.garage)) }.garage
        scope.launch(Dispatchers.IO) { store.save(next) }
    }

    private inline fun MutableStateFlow<VehicleState>.updateAndGet(f: (VehicleState) -> VehicleState): VehicleState {
        while (true) {
            val prev = value
            val next = f(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    private fun message(kind: VehicleMessage.Kind, detail: String? = null) = _state.update { it.copy(message = VehicleMessage(kind, detail)) }

    private fun task(t: VehicleTask) = _state.update { it.copy(task = t) }

    // ---- operations on the car ----

    /** Detects the car's ECUs, then reads all fault memories (ISTA "short test"). */
    fun scan() = launchOperation { runner, tables ->
        val previous = _state.value.current
        task(VehicleTask.Scanning(null))
        val preferred = previous?.takeIf { it.seriesByUser }?.seriesSgbd
        val result = VehicleScanner(runner, tables).scan(previous, preferred) { p -> task(VehicleTask.Scanning(p)) }
        val found = result.vehicle
        if (found == null) {
            message(if (result.cancelled) VehicleMessage.Kind.ScanCancelled else VehicleMessage.Kind.ScanFailed, result.error)
            return@launchOperation
        }
        var isNew = false
        updateGarage { g ->
            val existing = g.vehicles.firstOrNull { it.id == found.id }
            isNew = existing == null
            val merged = if (existing != null) found.copy(label = existing.label) else found
            g.copy(vehicles = if (existing != null) g.vehicles.map { if (it.id == found.id) merged else it } else g.vehicles + merged, currentId = found.id)
        }
        if (isNew && previous != null) message(VehicleMessage.Kind.NewVehicle, found.label)
        readAllFaults(runner, tables, _state.value.current ?: return@launchOperation)
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

    /** Runs [block] with a connected runner; one operation at a time. */
    private fun launchOperation(block: suspend (JobRunner, VehicleTables) -> Unit) {
        if (job?.isActive == true) return
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
