/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.android

import android.content.Context
import io.github.jonathangtd.bimmerdata.api.AcquisitionInfo
import io.github.jonathangtd.bimmerdata.api.ApiBackend
import io.github.jonathangtd.bimmerdata.api.ApiSettingsView
import io.github.jonathangtd.bimmerdata.api.BluetoothAdapterView
import io.github.jonathangtd.bimmerdata.api.BackendStatus
import io.github.jonathangtd.bimmerdata.api.ConnectMode
import io.github.jonathangtd.bimmerdata.api.ConnectionInfo
import io.github.jonathangtd.bimmerdata.api.DetectedEcuProvider
import io.github.jonathangtd.bimmerdata.api.EcuInfo
import io.github.jonathangtd.bimmerdata.api.EcuSource
import io.github.jonathangtd.bimmerdata.api.RecordingFile
import io.github.jonathangtd.bimmerdata.api.RenderRequest
import io.github.jonathangtd.bimmerdata.api.SettingsPatch
import io.github.jonathangtd.bimmerdata.api.SettingsView
import io.github.jonathangtd.bimmerdata.api.TelegrafPatch
import io.github.jonathangtd.bimmerdata.api.TelegrafStateView
import io.github.jonathangtd.bimmerdata.api.TelegrafTest
import io.github.jonathangtd.bimmerdata.api.TelegrafView
import io.github.jonathangtd.bimmerdata.export.ExportMode
import io.github.jonathangtd.bimmerdata.export.TelegrafRuntime
import io.github.jonathangtd.bimmerdata.export.WriteResult
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardStore
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementClient
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.RecordingStorage
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import io.github.jonathangtd.bimmerdata.settings.SecretStore
import io.github.jonathangtd.bimmerdata.settings.TelegrafAuthMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.util.Locale

/** The API's view of the app: live repository, dashboard store, renderers and settings. */
class AndroidApiBackend(
    context: Context,
    /** Hook for the future vehicle detection (null: ECUs come from the imported folder only). */
    private val detected: DetectedEcuProvider? = null,
) : ApiBackend {
    private val app = context.applicationContext
    private val repo = LiveRepository.get(app)
    private val store = DashboardStore(app)
    private val settingsStore = AppSettings.get(app)
    private val renderer = AndroidPageRenderer(app)

    override val appVersion: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    override fun status(): BackendStatus {
        val c = repo.connection.value
        val s = repo.liveDataSource.status.value
        val connection = when (c) {
            LiveConnection.Disconnected -> ConnectionInfo("disconnected")
            is LiveConnection.Connecting -> ConnectionInfo("connecting", mode(c.mode))
            is LiveConnection.Connected -> ConnectionInfo("connected", mode(c.mode), c.label)
            is LiveConnection.Failed -> ConnectionInfo("failed", mode(c.mode), message = c.message)
        }
        return BackendStatus(
            connection = connection,
            acquisition = AcquisitionInfo(s.state.name, s.cyclesPerSecond, s.errorCount, s.message),
            measurementsRead = repo.requests.merged().map { it.id },
        )
    }

    private fun mode(m: LiveMode) = when (m) {
        LiveMode.Usb -> ConnectMode.Usb
        LiveMode.Bluetooth -> ConnectMode.Bluetooth
        LiveMode.Simulation -> ConnectMode.Simulation
    }

    private fun live(m: ConnectMode) = when (m) {
        ConnectMode.Usb -> LiveMode.Usb
        ConnectMode.Bluetooth -> LiveMode.Bluetooth
        ConnectMode.Simulation -> LiveMode.Simulation
    }

    override val samples: StateFlow<Map<String, Sample>> get() = repo.liveDataSource.samples

    override fun ecus(): List<EcuInfo> {
        val catalog = runCatching { repo.catalog.sgbdsWithMeasurements() }.getOrDefault(emptyList())
        val found = detected?.detectedSgbds().orEmpty().map { it.lowercase() }.filter { it in catalog }
        return found.map { EcuInfo(it, EcuSource.Detected) } + catalog.filterNot { it in found }.map { EcuInfo(it, EcuSource.Catalog) }
    }

    override fun measurements(sgbd: String): List<Measurement> = runCatching { repo.catalog.measurements(sgbd) }.getOrDefault(emptyList())

    override fun setApiMeasurements(measurements: List<Measurement>) {
        if (measurements.isEmpty()) repo.requests.clear(MeasurementClient.Api)
        else repo.requests.set(MeasurementClient.Api, measurements, foreground = true)
    }

    override fun loadDashboard(): Dashboard = DashboardStore.saved.value ?: store.load()

    /** Saves through [DashboardStore], so Android Auto follows at once (it observes [DashboardStore.saved]). */
    override fun saveDashboard(dashboard: Dashboard) {
        check(store.save(dashboard)) { "the layout could not be written" }
    }

    /** Uses [DashboardStore.update]: read, edit and write under the store's process-wide lock. */
    override fun <T> editDashboard(op: (Dashboard) -> Pair<Dashboard, T>): T {
        var result: Result<T>? = null
        val written = store.update { current -> op(current).also { result = Result.success(it.second) }.first }
        checkNotNull(written) { "the layout could not be written" }
        return result!!.getOrThrow()
    }

    override fun defaultDashboard(): Dashboard = DashboardStore.defaultDashboard(app)

    override fun renderPage(request: RenderRequest): ByteArray = renderer.render(request)

    override fun connect(mode: ConnectMode?) {
        repo.connect(mode?.let(::live) ?: settingsStore.state.value.lastMode ?: return)
    }

    override fun disconnect() = repo.disconnect()

    override val recording: StateFlow<RecordingState> get() = repo.recorder.state
    override fun startRecording() = repo.recorder.start()
    override fun stopRecording() = repo.recorder.stop()
    override fun addMarker(label: String) = repo.recorder.mark(label)

    /** CSV files of the recording folder (Settings › Recording), newest first. */
    override fun recordings(): List<RecordingFile> = RecordingStorage.list(app).map { RecordingFile(it.name, it.sizeBytes, it.modifiedMs) }

    override fun openRecording(name: String): InputStream? = RecordingStorage.open(app, name)

    override fun settings(): SettingsView {
        val s = settingsStore.state.value
        return SettingsView(
            autoConnectOnCable = s.autoConnectOnCable,
            recordingFolder = RecordingStorage.describe(app),
            api = ApiSettingsView(s.api.enabled, s.api.port, s.api.scopes.sortedBy { it.ordinal }.map { it.id }),
            bluetoothAdapter = s.bluetooth.address?.let { BluetoothAdapterView(s.bluetooth.name, it) },
            bluetoothConnectOnStart = s.bluetooth.connectOnStart,
            lastMode = s.lastMode?.let(::mode),
        )
    }

    // ---- Telegraf export ----

    private val telegrafRuntime by lazy { TelegrafRuntime.get(app) }

    override fun telegraf(): TelegrafView {
        val t = settingsStore.state.value.telegraf
        val st = telegrafRuntime.state.value
        val secrets = SecretStore.get(app)
        return TelegrafView(
            enabled = t.enabled,
            url = t.url,
            auth = t.authMode.name.lowercase(Locale.ROOT),
            username = t.username,
            secretSet = when (t.authMode) {
                TelegrafAuthMode.None -> false
                TelegrafAuthMode.Token -> secrets.has(SecretStore.TELEGRAF_TOKEN)
                TelegrafAuthMode.Basic -> secrets.has(SecretStore.TELEGRAF_PASSWORD)
            },
            car = t.car,
            pointsPerSecond = t.pointsPerSecond,
            mode = if (t.mode == ExportMode.Live) "live" else "afterTrip",
            state = TelegrafStateView(
                phase = st.phase.name.replaceFirstChar { it.lowercase(Locale.ROOT) },
                queuedPoints = st.queuedLines, queuedBytes = st.queuedBytes, droppedPoints = st.droppedLines, sentPoints = st.sentLines,
                lastSuccessMs = st.lastSuccessMs, lastError = st.lastError,
            ),
        )
    }

    override fun updateTelegraf(patch: TelegrafPatch): TelegrafView {
        settingsStore.updateTelegraf { t ->
            t.copy(
                enabled = patch.enabled ?: t.enabled,
                url = patch.url ?: t.url,
                car = patch.car ?: t.car,
                pointsPerSecond = patch.pointsPerSecond ?: t.pointsPerSecond,
                mode = when (patch.mode) { null -> t.mode; "live" -> ExportMode.Live; else -> ExportMode.AfterTrip },
            )
        }
        return telegraf()
    }

    override fun testTelegraf(): TelegrafTest = when (val r = runBlocking { telegrafRuntime.test() }) {
        WriteResult.Ok -> TelegrafTest(true, "Telegraf accepted the test write")
        is WriteResult.AuthFailed -> TelegrafTest(false, "Authentication failed: ${r.message}")
        is WriteResult.Rejected -> TelegrafTest(false, r.message)
        is WriteResult.Retry -> TelegrafTest(false, "Not reachable: ${r.message}")
    }

    override fun sendTelegrafNow() = telegrafRuntime.sendNow()

    override fun updateSettings(patch: SettingsPatch): SettingsView {
        settingsStore.update { s ->
            s.copy(
                autoConnectOnCable = patch.autoConnectOnCable ?: s.autoConnectOnCable,
                bluetooth = s.bluetooth.copy(connectOnStart = patch.bluetoothConnectOnStart ?: s.bluetooth.connectOnStart),
            )
        }
        return settings()
    }
}
