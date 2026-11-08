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
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import io.github.jonathangtd.bimmerdata.files.EcuImporter
import io.github.jonathangtd.bimmerdata.usb.UsbCable
import io.github.jonathangtd.bimmerdata.usb.UsbFtdiSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** How the live acquisition reaches the car. */
enum class LiveMode { Usb, Simulation }

/** Connection of the live acquisition (the read loop itself reports [AcquisitionStatus]). */
sealed interface LiveConnection {
    data object Disconnected : LiveConnection
    data class Connecting(val mode: LiveMode) : LiveConnection
    data class Connected(val mode: LiveMode, val label: String) : LiveConnection

    /** The mini Tool32 holds the cable; acquisition resumes when it disconnects. */
    data class Paused(val mode: LiveMode, val label: String, val reason: String) : LiveConnection
    data class Failed(val mode: LiveMode, val message: String) : LiveConnection
}

/**
 * Process-wide owner of the live acquisition: [liveDataSource], [recorder] and [catalog] are
 * stable objects the UI can keep for the whole app life; [connect]/[disconnect] swap the
 * acquisition engine behind them.
 *
 * - USB mode: K+DCAN cable through [UsbFtdiSerialPort]; the [AcquisitionService] foreground
 *   service keeps it running with the screen off and shows the ongoing notification.
 * - Simulation mode: MSV70-like simulated ECU with moving values ([EcuSimulator.liveValues]) on a
 *   simulated K-line with realistic timing; runs in-process without the foreground service
 *   (Android 14 only grants a connectedDevice service with a real device).
 *
 * Cable sharing with the mini Tool32: see [InterfaceArbiter] (a connected Tool32 session pauses
 * USB acquisition). All methods are non-blocking and may be called from the main thread.
 */
class LiveRepository private constructor(context: Context) : InterfaceArbiter.CableUser {

    private val app: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val importer = EcuImporter(app)

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

    val isConnected: Boolean get() = _connection.value is LiveConnection.Connected || _connection.value is LiveConnection.Paused

    // ---- guarded by mutex ----
    private var engine: AcquisitionEngine? = null
    private var mode: LiveMode? = null
    private var label: String = ""
    private var connectJob: Job? = null

    init {
        InterfaceArbiter.liveUser = this
    }

    /** Connects in [mode] (USB: asks for the USB permission if needed) and starts reading. */
    fun connect(mode: LiveMode) {
        connectJob?.cancel()
        connectJob = scope.launch {
            mutex.withLock {
                shutdownEngine()
                _connection.value = LiveConnection.Connecting(mode)
                val (iface, name) = when (mode) {
                    LiveMode.Simulation -> {
                        val line = SimulatedKLine.withSimulator(EcuSimulator(liveValues = true), realisticTiming = true)
                        EdInterfaceObd(line) to "Simulation (MSV70-like ECU)"
                    }
                    LiveMode.Usb -> {
                        val usbManager = UsbCable.usbManager(app)
                        val device = UsbCable.findCable(usbManager) ?: run {
                            _connection.value = LiveConnection.Failed(mode, "No FTDI cable found (VID 0403). Plug the K+DCAN cable via USB-C OTG.")
                            return@launch
                        }
                        if (!UsbCable.requestPermission(app, usbManager, device)) {
                            _connection.value = LiveConnection.Failed(mode, "USB permission denied")
                            return@launch
                        }
                        val port = UsbFtdiSerialPort(usbManager, device)
                        EdInterfaceObd(port) to port.name
                    }
                }
                val e = AcquisitionEngine(iface, edCatalog)
                engine = e
                this@LiveRepository.mode = mode
                label = name
                proxy.attach(e)
                if (mode == LiveMode.Usb && InterfaceArbiter.diagUsingCable.value) {
                    _connection.value = LiveConnection.Paused(mode, name, PausedReason)
                } else {
                    e.start()
                    _connection.value = LiveConnection.Connected(mode, name)
                }
                if (mode == LiveMode.Usb) startService()
            }
        }
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

    private suspend fun shutdownEngine() {
        val e = engine ?: return
        engine = null
        mode = null
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

    // ---- InterfaceArbiter.CableUser ----

    override suspend fun releaseCable() {
        mutex.withLock {
            val e = engine ?: return
            if (mode != LiveMode.Usb) return
            e.stopAndJoin()
            _connection.value = LiveConnection.Paused(LiveMode.Usb, label, PausedReason)
        }
    }

    override fun cableAvailable() {
        scope.launch {
            mutex.withLock {
                val e = engine ?: return@launch
                if (_connection.value !is LiveConnection.Paused) return@launch
                e.start()
                _connection.value = LiveConnection.Connected(LiveMode.Usb, label)
            }
        }
    }

    companion object {
        private const val Tag = "LiveRepository"
        const val PausedReason = "The cable is in use (Tool32 or fault memory)"

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
        }
    }

    fun detach() {
        collectors?.cancel()
        collectors = null
        engine = null
        _status.value = AcquisitionStatus(AcquisitionState.Stopped)
    }
}
