/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.VehicleSimulator
import io.github.jonathangtd.bimmerdata.files.EcuImporter
import io.github.jonathangtd.bimmerdata.live.vehicle.JobRunner
import io.github.jonathangtd.bimmerdata.live.vehicle.SharedJobRunner
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import io.github.jonathangtd.bimmerdata.settings.Settings
import io.github.jonathangtd.bimmerdata.usb.UsbCable
import io.github.jonathangtd.bimmerdata.usb.UsbFtdiSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** How the app reaches the car. */
enum class LiveMode { Usb, Simulation }

/**
 * The one connection of the app (cable or simulated car). While [Connected], the acquisition
 * engine reads what the dashboard / Android Auto / recording need ([LiveDataSource.status] tells
 * how that goes: reading, reconnecting, ECU not answering) and every one-off user of the cable
 * (Tool32, faults, API) goes through [LiveRepository.exclusive].
 */
sealed interface LiveConnection {
    data object Disconnected : LiveConnection
    data class Connecting(val mode: LiveMode) : LiveConnection
    data class Connected(val mode: LiveMode, val label: String) : LiveConnection
    data class Failed(val mode: LiveMode, val message: String) : LiveConnection
}

/** Mode of the connection, null when disconnected. */
val LiveConnection.mode: LiveMode?
    get() = when (this) {
        LiveConnection.Disconnected -> null
        is LiveConnection.Connecting -> mode
        is LiveConnection.Connected -> mode
        is LiveConnection.Failed -> mode
    }

/**
 * Process-wide **cable manager**: the single owner of the K+DCAN cable (or of the simulated car)
 * for the whole app. [liveDataSource], [recorder] and [catalog] are stable objects the UI can keep
 * for the whole app life; [connect]/[disconnect] swap the acquisition engine behind them.
 *
 * - One connection, one [AcquisitionEngine], one interpreter. The dashboard, Android Auto and the
 *   CSV recording read through the engine; Tool32, the vehicle scan / fault memory ([jobRunner])
 *   and the REST API run their jobs with [exclusive]: each job is slipped in between two read
 *   cycles and the reading goes on by itself (a long job leaves a pause line in the CSV). Nobody
 *   waits for another user to "release" the cable.
 * - [exclusive] connects first if needed, in the last mode the user chose (else the default mode).
 * - USB mode: K+DCAN cable through [UsbFtdiSerialPort]; the [AcquisitionService] foreground
 *   service keeps it running with the screen off and shows the ongoing notification.
 * - Simulation mode: a simulated E90 ([VehicleSimulator.e90]: 14 ECUs, MSV70-like engine ECU with
 *   moving values) on a simulated K-line with realistic timing, kept for the app's life (cleared
 *   faults stay cleared); runs in-process without the foreground service (Android 14 only grants
 *   a connectedDevice service with a real device).
 *
 * All methods except [exclusive] are non-blocking and may be called from the main thread.
 */
class LiveRepository private constructor(context: Context) : CableExecutor {

    private val app: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val importer = EcuImporter(app)
    private val settings = AppSettings.get(app)

    private val edCatalog = EdiabasMeasurementCatalog(importer.ecuDir, File(app.cacheDir, "measurement_catalog.tsv"))

    /** Measurements of the imported ECU folder (call off the main thread: file I/O). */
    val catalog: MeasurementCatalog get() = edCatalog

    private val proxy = LiveDataProxy(scope)

    /** Samples and status of the current acquisition; [LiveDataSource.setMeasurements] survives reconnects. */
    val liveDataSource: LiveDataSource get() = proxy

    /** What the phone dashboard and Android Auto want read; the engine reads their merge. */
    val requests = MeasurementRequests(proxy::setMeasurements)

    private val _lastRecording = MutableStateFlow<Uri?>(null)

    /** Content Uri of the last finished CSV file (for [CsvFiles.shareIntent] / [CsvFiles.viewIntent]). */
    val lastRecording: StateFlow<Uri?> = _lastRecording.asStateFlow()

    private val csvRecorder = CsvSessionRecorder(
        source = proxy,
        columns = { proxy.requested },
        output = { name -> CsvFiles.create(app, name) { uri -> _lastRecording.value = uri } },
        scope = scope,
        pin = proxy::setPinned,
    )

    /** CSV logger of the live values, files in Documents/BimmerData/. */
    val recorder: SessionRecorder get() = csvRecorder

    private val _connection = MutableStateFlow<LiveConnection>(LiveConnection.Disconnected)
    val connection: StateFlow<LiveConnection> = _connection.asStateFlow()

    val isConnected: Boolean get() = _connection.value is LiveConnection.Connected

    /** The simulated car keeps its state (cleared faults stay cleared) for the app's life. */
    private val simulator by lazy { VehicleSimulator.e90(EcuSimulator(liveValues = true)) }

    // ---- guarded by mutex ----
    private var engine: AcquisitionEngine? = null
    @Volatile private var connectJob: Job? = null

    /** Connects in [mode] (USB: asks for the USB permission if needed) and starts reading. */
    fun connect(mode: LiveMode) {
        connectJob?.cancel()
        connectJob = scope.launch { connectNow(mode) }
    }

    /** Stops reading and recording and releases the cable. */
    fun disconnect() {
        connectJob?.cancel()
        scope.launch {
            mutex.withLock {
                shutdownEngine()
                _connection.value = LiveConnection.Disconnected
            }
        }
    }

    /** Starts or stops the CSV recording (used by the notification action). */
    fun toggleRecording() {
        if (csvRecorder.state.value.recording) csvRecorder.stop() else csvRecorder.start()
    }

    /**
     * Runs a one-off operation on the shared cable between two read cycles (see
     * [AcquisitionEngine.exclusive]). Connects first when needed, in the last mode the user
     * chose ([Settings.lastMode], else [Settings.defaultMode]).
     *
     * @throws CableUnavailableException with a readable reason when no connection can be made.
     */
    override suspend fun <T> exclusive(reason: String, block: (CableAccess) -> T): T = ensureEngine().exclusive(reason, block)

    /** Jobs of the vehicle scan and fault memory on the shared cable. */
    fun jobRunner(label: String = "Faults"): JobRunner = SharedJobRunner(this, label)

    /** The running engine, connecting first if needed. */
    private suspend fun ensureEngine(): AcquisitionEngine {
        connectJob?.join() // a connection in progress
        currentEngine()?.let { return it }
        val s = settings.state.value
        val job = scope.launch { connectNow(s.lastMode ?: s.defaultMode) }
        connectJob = job
        job.join()
        currentEngine()?.let { return it }
        throw CableUnavailableException((_connection.value as? LiveConnection.Failed)?.message ?: "Not connected")
    }

    private fun currentEngine(): AcquisitionEngine? =
        if (_connection.value is LiveConnection.Connected) engine?.takeIf { it.isRunning } else null

    private suspend fun connectNow(mode: LiveMode) {
        mutex.withLock {
            shutdownEngine()
            _connection.value = LiveConnection.Connecting(mode)
            settings.update { it.copy(lastMode = mode) }
            val (iface, name) = when (mode) {
                LiveMode.Simulation -> EdInterfaceObd(simulator.kLine(realisticTiming = true)) to SimulationLabel
                LiveMode.Usb -> {
                    val usbManager = UsbCable.usbManager(app)
                    val device = UsbCable.findCable(usbManager) ?: run {
                        _connection.value = LiveConnection.Failed(mode, NoCableMessage)
                        return
                    }
                    if (!UsbCable.requestPermission(app, usbManager, device)) {
                        _connection.value = LiveConnection.Failed(mode, UsbDeniedMessage)
                        return
                    }
                    val port = UsbFtdiSerialPort(usbManager, device)
                    EdInterfaceObd(port) to port.name
                }
            }
            val e = AcquisitionEngine(iface, edCatalog)
            engine = e
            proxy.attach(e)
            e.start()
            _connection.value = LiveConnection.Connected(mode, name)
            if (mode == LiveMode.Usb) startService()
        }
    }

    private suspend fun shutdownEngine() {
        val e = engine ?: return
        engine = null
        if (csvRecorder.state.value.recording) csvRecorder.stop()
        proxy.detach()
        e.stopAndJoin()
        e.close()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, AcquisitionService::class.java))
        } catch (ex: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException: acquisition keeps running in-process
            Log.w(Tag, "Cannot start the acquisition service", ex)
        }
    }

    companion object {
        private const val Tag = "LiveRepository"
        const val SimulationLabel = "Simulation (E90 with MSV70)"
        const val NoCableMessage = "No FTDI cable found (VID 0403). Plug the K+DCAN cable via USB-C OTG."
        const val UsbDeniedMessage = "USB permission denied"

        // holds only the application context
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: LiveRepository? = null

        fun get(context: Context): LiveRepository =
            instance ?: synchronized(this) { instance ?: LiveRepository(context).also { instance = it } }
    }
}

/**
 * Stable [LiveDataSource] in front of the engine of the current connection: keeps the requested
 * measurements across reconnects and the last samples while disconnected.
 */
internal class LiveDataProxy(private val scope: CoroutineScope) : LiveDataSource {
    private val _samples = MutableStateFlow<Map<String, Sample>>(emptyMap())
    override val samples: StateFlow<Map<String, Sample>> = _samples.asStateFlow()

    private val _status = MutableStateFlow(AcquisitionStatus())
    override val status: StateFlow<AcquisitionStatus> = _status.asStateFlow()

    private val _pauses = MutableSharedFlow<AcquisitionPause>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val pauses: SharedFlow<AcquisitionPause> = _pauses.asSharedFlow()

    @Volatile var requested: List<Measurement> = emptyList()
        private set
    @Volatile private var pinned: List<Measurement>? = null
    @Volatile private var engine: AcquisitionEngine? = null
    private var collectors: Job? = null

    override fun setMeasurements(measurements: List<Measurement>) {
        requested = measurements
        engine?.setMeasurements(measurements)
    }

    fun setPinned(measurements: List<Measurement>?) {
        pinned = measurements
        engine?.setPinnedMeasurements(measurements)
    }

    fun attach(e: AcquisitionEngine) {
        collectors?.cancel()
        engine = e
        e.setMeasurements(requested)
        e.setPinnedMeasurements(pinned)
        collectors = scope.launch {
            launch { e.samples.collect { fresh -> if (fresh.isNotEmpty()) _samples.update { it + fresh } } }
            launch { e.status.collect { _status.value = it } }
            launch { e.pauses.collect { _pauses.emit(it) } }
        }
    }

    fun detach() {
        collectors?.cancel()
        collectors = null
        engine = null
        _status.value = AcquisitionStatus(AcquisitionState.Stopped)
    }
}
