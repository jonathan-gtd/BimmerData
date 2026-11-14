/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.cars.CarSpace
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.files.EcuFileStore
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.concurrent.thread

/** In-memory backend: a fake MSV70 that produces moving samples for what is requested while "connected". */
class FakeApiBackend : ApiBackend {
    override val appVersion = "test"

    val catalog = listOf(
        Measurement("msv70/0x4402", "msv70", "Öltemperatur", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OELTEMPERATUR_WERT"), 0.0, 150.0),
        Measurement("msv70/0x4300", "msv70", "Kühlmitteltemperatur", "°C", MeasurementSource.MeasurementTable(0x4300, "STAT_KUEHLMITTELTEMPERATUR_WERT")),
        Measurement("msv70/0x4807", "msv70", "Lambda Bank 1", "", MeasurementSource.MeasurementTable(0x4807, "STAT_LAMBDA_BANK1_WERT")),
        Measurement("msv70/0x4808", "msv70", "Lambda Bank 2", "", MeasurementSource.MeasurementTable(0x4808, "STAT_LAMBDA_BANK2_WERT")),
        Measurement("msv70/status_motor", "msv70", "Motorstatus", "", MeasurementSource.JobResult("STATUS_MOTOR", "", "STAT_MOTOR_TEXT")),
    )

    private val _samples = MutableStateFlow<Map<String, Sample>>(emptyMap())
    override val samples: StateFlow<Map<String, Sample>> = _samples

    @Volatile var connection = ConnectionInfo("disconnected")
    @Volatile var requested: List<Measurement> = emptyList()
    @Volatile var dashboard = Dashboard(
        phonePages = listOf(Page(id = "p1", name = "Engine", columns = 2, rows = 3)),
        carPages = listOf(Page(id = "c1", name = "Car", columns = 4, rows = 1)),
    )
    var saves = 0
    var lastRender: RenderRequest? = null
    var settings = SettingsView(false, "Documents/BimmerData/", ApiSettingsView(true, 8765, listOf("read", "layout")))
    private val _recording = MutableStateFlow(RecordingState())
    override val recording: StateFlow<RecordingState> = _recording
    val markers = ArrayList<String>()

    @Volatile private var running = true
    private val producer = thread(isDaemon = true, name = "fake-ecu") {
        var t = 0.0
        while (running) {
            Thread.sleep(40)
            if (connection.state != "connected") continue
            t += 0.04
            val now = System.currentTimeMillis()
            val fresh = requested.associate { m ->
                m.id to if (m.source is MeasurementSource.JobResult) Sample(null, "Motor läuft", now) else Sample(90.0 + t, null, now)
            }
            if (fresh.isNotEmpty()) _samples.update { it + fresh }
        }
    }

    fun close() {
        running = false
        producer.join(500)
    }

    @Volatile var pausedReading = false

    override fun setReadingPaused(paused: Boolean) { pausedReading = paused }

    override fun status() = BackendStatus(
        connection,
        AcquisitionInfo(
            when { connection.state != "connected" -> "Stopped"; pausedReading -> "Paused"; else -> "Running" },
            if (connection.state == "connected" && !pausedReading) 12.5 else 0.0, 0, paused = pausedReading,
        ),
        requested.map { it.id },
    )

    override fun ecus() = listOf(EcuInfo("msv70", EcuSource.Catalog), EcuInfo("ds2_ews", EcuSource.Catalog))
    override fun measurements(sgbd: String) = catalog.filter { it.sgbd == sgbd }
    override fun setApiMeasurements(measurements: List<Measurement>) { requested = measurements }
    override fun loadDashboard() = dashboard
    override fun saveDashboard(dashboard: Dashboard) { this.dashboard = dashboard; saves++ }
    override fun defaultDashboard() = Dashboard(phonePages = listOf(Page(name = "Default", columns = 2, rows = 6)), carPages = listOf(Page(name = "Default", columns = 4, rows = 1)))

    @Volatile var profileSet = io.github.jonathangtd.bimmerdata.profiles.ProfileSet()
    @Volatile var garage = Garage()
    override fun profiles() = profileSet
    @Synchronized
    override fun <T> editProfiles(op: (io.github.jonathangtd.bimmerdata.profiles.ProfileSet) -> Pair<io.github.jonathangtd.bimmerdata.profiles.ProfileSet, T>): T {
        val (set, result) = op(profileSet)
        profileSet = set
        return result
    }

    override fun cars() = CarSpace(garage, profileSet)
    @Synchronized
    override fun <T> editCars(op: (CarSpace) -> Pair<CarSpace, T>): T {
        val (space, result) = op(cars())
        garage = space.garage
        profileSet = space.profiles
        return result
    }

    /** Cars whose detection was started; [detectionBusy] refuses a new one. */
    val detections = ArrayList<String>()
    @Volatile var detectionBusy = false
    override fun startDetection(carId: String): Boolean {
        if (detectionBusy) return false
        detections += carId
        garage = garage.copy(currentId = carId)
        return true
    }
    override fun detection() = DetectionView(running = detections.isNotEmpty(), carId = garage.currentId, step = "identifying".takeIf { detections.isNotEmpty() })

    override fun renderPage(request: RenderRequest): ByteArray {
        lastRender = request
        return PNG_SIGNATURE + ByteArray(8)
    }

    override fun connect(mode: ConnectMode?) {
        val m = mode ?: settings.lastMode ?: return
        settings = settings.copy(lastMode = m)
        connection = ConnectionInfo("connecting", m)
        thread(isDaemon = true) {
            Thread.sleep(100)
            connection = ConnectionInfo("connected", m, if (m == ConnectMode.Simulation) "Simulation (E90 with MSV70)" else "FT232R")
        }
    }

    override fun disconnect() {
        connection = ConnectionInfo("disconnected")
        pausedReading = false
        _recording.value = RecordingState()
    }

    override fun startRecording() { _recording.value = RecordingState(true, "bimmerdata_test.csv", System.currentTimeMillis(), 0) }
    override fun stopRecording() { _recording.value = RecordingState() }
    override fun addMarker(label: String) { markers += label }

    override fun recordings() = listOf(RecordingFile("bimmerdata_test.csv", 24, 1_700_000_000_000))
    override fun openRecording(name: String): InputStream? =
        if (name == "bimmerdata_test.csv") ByteArrayInputStream("time;oil\n0.0;90.1\n".toByteArray()) else null

    override fun settings() = settings
    override fun updateSettings(patch: SettingsPatch): SettingsView {
        settings = settings.copy(
            autoConnectOnCable = patch.autoConnectOnCable ?: settings.autoConnectOnCable,
            bluetoothConnectOnStart = patch.bluetoothConnectOnStart ?: settings.bluetoothConnectOnStart,
        )
        return settings
    }

    // ---- setup routes ----

    /** ECU file store of the test (null: not available, 501). */
    var ecuStore: EcuFileStore? = null
    override val ecuFiles: EcuFileStore? get() = ecuStore

    var permissionList = listOf(
        PermissionView("bluetoothConnect", "android.permission.BLUETOOTH_CONNECT", "runtime", "missing", true, "Bluetooth adapter", "Settings › Connection › Allow"),
        PermissionView("notifications", "android.permission.POST_NOTIFICATIONS", "runtime", "granted", false, "Notifications", "Android settings"),
        PermissionView("locationOn", null, "systemSetting", "off", false, "GPS", "Quick settings"),
    )
    override fun permissions() = permissionList

    var bt = BluetoothView(permission = "granted", enabled = true, connectOnStart = false, connected = false)
    var paired = listOf(
        PairedDeviceView("Deep OBD BMW", "00:11:22:33:44:55", likelyAdapter = true, lowEnergyOnly = false, selected = false),
        PairedDeviceView("Watch", "AA:BB:CC:DD:EE:FF", likelyAdapter = false, lowEnergyOnly = true, selected = false),
    )
    var adapterInfo = AdapterInfoView("Deep OBD", "0.12", voltage = 12.4, canMode = "auto", canModeCode = 0xFF, reachesKLine = true)
    val canModeWrites = ArrayList<Int>()
    override fun bluetooth() = bt
    override fun pairedDevices() = if (bt.permission == "missing") emptyList() else paired.map { it.copy(selected = it.address == bt.adapter?.address) }
    override fun selectBluetoothAdapter(address: String?, forget: Boolean, connectOnStart: Boolean?): BluetoothView {
        val device = paired.firstOrNull { it.address == address }
        bt = bt.copy(
            adapter = if (forget) null else device?.let { BluetoothAdapterView(it.name, it.address) } ?: bt.adapter,
            connectOnStart = connectOnStart ?: bt.connectOnStart,
        )
        return bt
    }
    override fun readBluetoothAdapter() = if (bt.connected) adapterInfo else null
    override fun writeCanMode(code: Int): AdapterInfoView {
        canModeWrites += code
        adapterInfo = adapterInfo.copy(canModeCode = code, canMode = if (code == 0) "kLineOnly" else if (code == 1) "dCan500" else "auto", reachesKLine = code != 1)
        return adapterInfo
    }

    var gpsView = GpsView(false, 1000, listOf(1000L, 2000L, 5000L, 10_000L), "missing", true, "off")
    override fun gps() = gpsView
    override fun updateGps(enabled: Boolean?, intervalMs: Long?): GpsView {
        val on = enabled ?: gpsView.enabled
        gpsView = gpsView.copy(enabled = on, intervalMs = intervalMs ?: gpsView.intervalMs, state = if (!on) "off" else if (gpsView.permission == "missing") "noPermission" else "idle")
        return gpsView
    }

    var recordingView = RecordingSettingsView("Download/Car logs/", true, 0, listOf(0L, 100L, 200L, 500L, 1000L), "bimmerdata", "bimmerdata_20261109_100000.csv")
    override fun recordingSettings() = recordingView
    override fun updateRecordingSettings(patch: RecordingSettingsPatch): RecordingSettingsView {
        recordingView = recordingView.copy(
            folder = if (patch.useDefaultFolder) "Documents/BimmerData/" else recordingView.folder,
            customFolder = recordingView.customFolder && !patch.useDefaultFolder,
            rowIntervalMs = patch.rowIntervalMs ?: recordingView.rowIntervalMs,
            filePrefix = patch.filePrefix?.ifEmpty { "bimmerdata" } ?: recordingView.filePrefix,
        )
        return recordingView
    }

    var translationToken = ""
    var translationUpdates = 0
    var translationsView = TranslationsView(
        enabled = true, language = "", effectiveLanguage = "en", languages = listOf("de", "en", "fr"), source = "url",
        baseUrl = "https://example.org/translations", defaultBaseUrl = "https://example.org/translations", tokenSet = false,
        selection = "auto", chosen = emptyList(), cachedEcus = listOf("msv70"), updating = false,
    )
    override fun translations() = translationsView.copy(tokenSet = translationToken.isNotEmpty())
    override fun updateTranslations(patch: TranslationsPatch): TranslationsView {
        patch.token?.let { translationToken = it }
        val lang = patch.language ?: translationsView.language
        translationsView = translationsView.copy(
            language = lang, enabled = lang != "de", effectiveLanguage = lang.ifEmpty { "en" },
            source = patch.source ?: translationsView.source, baseUrl = patch.baseUrl ?: translationsView.baseUrl,
            selection = patch.selection ?: translationsView.selection, chosen = patch.chosen ?: translationsView.chosen,
        )
        return translations()
    }
    override fun updateTranslationsNow(): TranslationsView {
        translationUpdates++
        return translations().copy(updating = true)
    }

    companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
