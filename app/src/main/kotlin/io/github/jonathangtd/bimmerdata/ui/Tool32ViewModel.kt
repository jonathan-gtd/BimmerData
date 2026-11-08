/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jonathangtd.bimmerdata.diag.DiagSession
import io.github.jonathangtd.bimmerdata.files.EcuImporter
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import io.github.jonathangtd.bimmerdata.ediabas.ResultType
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import io.github.jonathangtd.bimmerdata.usb.UsbCable
import io.github.jonathangtd.bimmerdata.usb.UsbFtdiSerialPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class ConnectionMode { Usb, Simulation }

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val label: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class ImportUi(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesTotal: Long, val current: String)

data class ResultEntryUi(val name: String, val value: String, val unit: String?, val type: String)

data class ResultSetUi(val index: Int, val entries: List<ResultEntryUi>)

data class FrameUi(val offsetMs: Double, val direction: EdInterfaceObd.FrameDirection, val hex: String, val text: String?)

data class JobResultUi(
    val title: String,
    val jobStatus: String?,
    val error: String?,
    val elapsedMs: Double,
    val telegrams: Long,
    val sets: List<ResultSetUi>,
    val frames: List<FrameUi>,
)

data class RepeatUi(val done: Int, val total: Int, val avgMs: Double, val minMs: Double, val maxMs: Double, val avgTelegramMs: Double?)

data class Tool32State(
    val mode: ConnectionMode = ConnectionMode.Simulation,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val ignition: Boolean? = null,
    val ecuFiles: List<String> = emptyList(),
    val import: ImportUi? = null,
    val sgbd: String? = null,
    val jobs: List<String> = emptyList(),
    val jobsLoading: Boolean = false,
    val job: String? = null,
    val args: String = "",
    val repeatCount: String = "10",
    val running: Boolean = false,
    val result: JobResultUi? = null,
    val repeat: RepeatUi? = null,
    val showFrames: Boolean = false,
    val message: String? = null,
)

class Tool32ViewModel(application: Application) : AndroidViewModel(application) {

    private val importer = EcuImporter(application)
    private val _state = MutableStateFlow(Tool32State(ecuFiles = importer.importedFiles()))
    val state: StateFlow<Tool32State> = _state.asStateFlow()

    private var session: DiagSession? = null
    private var importJob: Job? = null
    private var runJob: Job? = null

    // ---- ECU folder import ----

    fun importFolder(treeUri: Uri) {
        if (importJob?.isActive == true) return
        disconnect() // the session reads files from the folder being replaced
        importJob = viewModelScope.launch {
            _state.update { it.copy(import = ImportUi(0, 0, 0, 0, "Listing files"), message = null) }
            try {
                val count = importer.import(treeUri) { p ->
                    _state.update { it.copy(import = ImportUi(p.filesDone, p.filesTotal, p.bytesDone, p.bytesTotal, p.current)) }
                }
                _state.update { it.copy(message = "$count ECU files imported", sgbd = null, jobs = emptyList(), job = null, result = null) }
            } catch (ex: CancellationException) {
                _state.update { it.copy(message = "Import cancelled, previous files kept") }
                throw ex
            } catch (ex: Exception) {
                _state.update { it.copy(message = "Import failed: ${ex.message}") }
            } finally {
                _state.update { it.copy(import = null, ecuFiles = importer.importedFiles()) }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    // ---- connection ----

    fun setMode(mode: ConnectionMode) {
        if (mode == _state.value.mode) return
        disconnect()
        _state.update { it.copy(mode = mode) }
    }

    fun connect() {
        if (_state.value.connection is ConnectionState.Connecting) return
        disconnect()
        _state.update { it.copy(connection = ConnectionState.Connecting, message = null) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val newSession = when (_state.value.mode) {
                ConnectionMode.Simulation -> {
                    val line = SimulatedKLine.withEcu(realisticTiming = true, respond = EcuSimulator()::respond)
                    DiagSession(importer.ecuDir, EdInterfaceObd(line), "Simulation (MSV70-like ECU)")
                }
                ConnectionMode.Usb -> {
                    val usbManager = UsbCable.usbManager(app)
                    val device = UsbCable.findCable(usbManager)
                    if (device == null) {
                        fail("No FTDI cable found (VID 0403). Plug the K+DCAN cable via USB-C OTG.")
                        return@launch
                    }
                    if (!UsbCable.requestPermission(app, usbManager, device)) {
                        fail("USB permission denied")
                        return@launch
                    }
                    val port = UsbFtdiSerialPort(usbManager, device)
                    DiagSession(importer.ecuDir, EdInterfaceObd(port), port.name)
                }
            }
            val error = newSession.connect()
            if (error != null) {
                newSession.close()
                fail(error)
                return@launch
            }
            session = newSession
            _state.update { it.copy(connection = ConnectionState.Connected(newSession.label), ignition = newSession.readIgnition()) }
            _state.value.sgbd?.let { loadJobs(it) }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(connection = ConnectionState.Failed(message)) }
    }

    fun disconnect() {
        runJob?.cancel()
        session?.close()
        session = null
        _state.update {
            it.copy(
                connection = if (it.connection is ConnectionState.Failed) it.connection else ConnectionState.Disconnected,
                ignition = null, running = false,
            )
        }
    }

    // ---- SGBD and job selection ----

    fun selectSgbd(name: String) {
        _state.update { it.copy(sgbd = name, jobs = emptyList(), job = null, result = null, repeat = null) }
        loadJobs(name)
    }

    private fun loadJobs(sgbd: String) {
        val s = session ?: run {
            _state.update { it.copy(message = "Connect (USB or Simulation) to load the job list") }
            return
        }
        _state.update { it.copy(jobsLoading = true) }
        viewModelScope.launch {
            try {
                val jobs = s.listJobs(sgbd)
                _state.update { st -> st.copy(jobs = jobs, job = st.job?.takeIf { it in jobs }) }
            } catch (ex: Exception) {
                _state.update { it.copy(message = "Cannot read $sgbd: ${DiagSession.describe(ex)}") }
            } finally {
                _state.update { it.copy(jobsLoading = false) }
            }
        }
    }

    /** For a group file: identify the variant with the ECU and switch to it. */
    fun resolveVariant() {
        val s = session ?: return
        val group = _state.value.sgbd ?: return
        runJob = viewModelScope.launch {
            _state.update { it.copy(running = true, message = null) }
            try {
                val variant = s.resolveVariant(group)
                _state.update { it.copy(message = "$group -> $variant") }
                selectSgbd(variant)
            } catch (ex: Exception) {
                _state.update { it.copy(message = "Identification failed: ${DiagSession.describe(ex)}") }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    /**
     * Selects the ECU file of [sgbd] ("msv70", "MSV70.PRG"…) when Tool32 is opened from an ECU's
     * detail; does nothing if it is already selected.
     */
    fun preselectSgbd(sgbd: String) {
        val base = sgbd.substringBeforeLast('.').lowercase(Locale.ROOT)
        val files = _state.value.ecuFiles
        val file = files.firstOrNull { it.equals(sgbd, ignoreCase = true) }
            ?: files.filter { it.substringBeforeLast('.').lowercase(Locale.ROOT) == base }.minByOrNull { if (it.endsWith(".prg", ignoreCase = true)) 0 else 1 }
        if (file == null) {
            _state.update { it.copy(message = "${base.uppercase(Locale.ROOT)} is not in the imported ECU files") }
            return
        }
        if (_state.value.sgbd != file) selectSgbd(file)
    }

    fun selectJob(job: String) = _state.update { it.copy(job = job, result = null, repeat = null) }

    fun setArgs(args: String) = _state.update { it.copy(args = args) }

    fun setRepeatCount(value: String) = _state.update { it.copy(repeatCount = value.filter(Char::isDigit).take(4)) }

    fun setShowFrames(show: Boolean) = _state.update { it.copy(showFrames = show) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- running ----

    fun run(times: Int = 1) {
        val s = session ?: return
        val st = _state.value
        val sgbd = st.sgbd ?: return
        val job = st.job ?: return
        if (st.running) return
        val total = times.coerceIn(1, 9999)
        runJob = viewModelScope.launch {
            _state.update { it.copy(running = true, repeat = null, message = null) }
            val durations = ArrayList<Double>()
            var telegrams = 0L
            try {
                for (i in 1..total) {
                    val run = s.runJob(sgbd, job, st.args)
                    val ms = run.elapsedNanos / 1e6
                    durations.add(ms)
                    telegrams += run.telegrams
                    _state.update {
                        it.copy(
                            result = run.toUi(),
                            repeat = if (total > 1) RepeatUi(
                                i, total, durations.average(), durations.min(), durations.max(),
                                if (telegrams > 0) durations.sum() / telegrams else null,
                            ) else null,
                        )
                    }
                    if (run.error != null) break
                }
                _state.update { it.copy(ignition = s.readIgnition()) }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun cancelRun() {
        session?.cancel()
    }

    override fun onCleared() {
        session?.close()
        session = null
    }

    private fun DiagSession.JobRun.toUi(): JobResultUi {
        val jobStatus = sets.lastOrNull()?.get("JOB_STATUS")?.opData as? String
        val setsUi = sets.mapIndexed { index, set ->
            ResultSetUi(index, set.values.map { r -> r.toUi(set) })
        }
        val framesUi = frames.map {
            FrameUi((it.timeNanos - startNanos) / 1e6, it.direction, hex(it.data), it.text)
        }
        return JobResultUi(
            "${sgbd.substringBeforeLast('.').uppercase(Locale.ROOT)} · $job${if (args.isNotEmpty()) "($args)" else ""}",
            jobStatus, error, elapsedNanos / 1e6, telegrams, setsUi, framesUi,
        )
    }

    private fun ResultData.toUi(set: Map<String, ResultData>): ResultEntryUi {
        val unit = if (name.endsWith("_WERT")) set[name.removeSuffix("_WERT") + "_EINH"]?.opData as? String else null
        return ResultEntryUi(name, formatValue(this), unit?.takeIf { it.isNotBlank() }, resType.name.removePrefix("Type"))
    }

    companion object {
        fun hex(data: ByteArray): String = data.joinToString(" ") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }

        fun formatValue(r: ResultData): String = when (val v = r.opData) {
            is Long -> if (r.resType == ResultType.TypeB || r.resType == ResultType.TypeW || r.resType == ResultType.TypeD) {
                "$v (0x${java.lang.Long.toHexString(v).uppercase(Locale.ROOT)})"
            } else {
                v.toString()
            }
            is Double -> String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')
            is ByteArray -> if (v.size > 64) hex(v.copyOf(64)) + " … (${v.size} bytes)" else hex(v)
            else -> v.toString()
        }
    }
}
