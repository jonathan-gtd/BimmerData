/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.android

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.jonathangtd.bimmerdata.api.AcquisitionInfo
import io.github.jonathangtd.bimmerdata.api.ApiBackend
import io.github.jonathangtd.bimmerdata.api.ApiSettingsView
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
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardStore
import io.github.jonathangtd.bimmerdata.live.CsvFiles
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementClient
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

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

    private fun mode(m: LiveMode) = if (m == LiveMode.Usb) ConnectMode.Usb else ConnectMode.Simulation

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
        val m = mode ?: if (settingsStore.state.value.defaultMode == LiveMode.Usb) ConnectMode.Usb else ConnectMode.Simulation
        repo.connect(if (m == ConnectMode.Usb) LiveMode.Usb else LiveMode.Simulation)
    }

    override fun disconnect() = repo.disconnect()

    override val recording: StateFlow<RecordingState> get() = repo.recorder.state
    override fun startRecording() = repo.recorder.start()
    override fun stopRecording() = repo.recorder.stop()
    override fun addMarker(label: String) = repo.recorder.mark(label)

    /** CSV files of Documents/BimmerData/ that this installation created (visible without storage permission). */
    override fun recordings(): List<RecordingFile> = query().map { it.second }

    private fun query(): List<Pair<Long, RecordingFile>> {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val out = ArrayList<Pair<Long, RecordingFile>>()
        runCatching {
            app.contentResolver.query(uri, projection, "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(CsvFiles.RelativePath), null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".csv", ignoreCase = true)) continue
                    out += c.getLong(0) to RecordingFile(name, c.getLong(2), c.getLong(3) * 1000)
                }
            }
        }
        return out
    }

    override fun openRecording(name: String): InputStream? {
        val id = query().firstOrNull { it.second.name == name }?.first ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)
        return runCatching { app.contentResolver.openInputStream(uri) }.getOrNull()
    }

    override fun settings(): SettingsView {
        val s = settingsStore.state.value
        return SettingsView(
            defaultMode = mode(s.defaultMode),
            autoConnectOnCable = s.autoConnectOnCable,
            recordingFolder = CsvFiles.RelativePath,
            api = ApiSettingsView(s.api.enabled, s.api.port, s.api.scopes.sortedBy { it.ordinal }.map { it.id }),
        )
    }

    override fun updateSettings(patch: SettingsPatch): SettingsView {
        settingsStore.update { s ->
            s.copy(
                defaultMode = patch.defaultMode?.let { if (it == ConnectMode.Usb) LiveMode.Usb else LiveMode.Simulation } ?: s.defaultMode,
                autoConnectOnCable = patch.autoConnectOnCable ?: s.autoConnectOnCable,
            )
        }
        return settings()
    }
}
