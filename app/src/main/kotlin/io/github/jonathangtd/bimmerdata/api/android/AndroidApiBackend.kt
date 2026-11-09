/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.android

import io.github.jonathangtd.bimmerdata.net.LocalNetwork
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
import io.github.jonathangtd.bimmerdata.api.AdapterInfoView
import io.github.jonathangtd.bimmerdata.api.ApiException
import io.github.jonathangtd.bimmerdata.api.BluetoothView
import io.github.jonathangtd.bimmerdata.api.GpsView
import io.github.jonathangtd.bimmerdata.api.PairedDeviceView
import io.github.jonathangtd.bimmerdata.api.PermissionView
import io.github.jonathangtd.bimmerdata.api.RecordingSettingsPatch
import io.github.jonathangtd.bimmerdata.api.RecordingSettingsView
import io.github.jonathangtd.bimmerdata.api.TelegrafAuthPatch
import io.github.jonathangtd.bimmerdata.api.TranslationUpdateView
import io.github.jonathangtd.bimmerdata.api.TranslationsPatch
import io.github.jonathangtd.bimmerdata.api.TranslationsView
import io.github.jonathangtd.bimmerdata.bluetooth.BluetoothAccess
import io.github.jonathangtd.bimmerdata.ediabas.deepobd.AdapterInfo
import io.github.jonathangtd.bimmerdata.ediabas.deepobd.CanMode
import io.github.jonathangtd.bimmerdata.files.EcuFileStore
import io.github.jonathangtd.bimmerdata.files.EcuImportFolder
import io.github.jonathangtd.bimmerdata.gps.GpsRuntime
import io.github.jonathangtd.bimmerdata.gps.GpsState
import io.github.jonathangtd.bimmerdata.i18n.EcuSelection
import io.github.jonathangtd.bimmerdata.i18n.TranslationConfig
import io.github.jonathangtd.bimmerdata.i18n.TranslationRepository
import io.github.jonathangtd.bimmerdata.i18n.TranslationSettings
import io.github.jonathangtd.bimmerdata.i18n.TranslationSource
import io.github.jonathangtd.bimmerdata.live.CableUnavailableException
import io.github.jonathangtd.bimmerdata.live.CsvSessionRecorder
import io.github.jonathangtd.bimmerdata.settings.GpsSettings
import io.github.jonathangtd.bimmerdata.settings.RecordingConfig
import androidx.core.net.toUri
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.SequenceInputStream
import java.util.Collections
import io.github.jonathangtd.bimmerdata.export.ExportMode
import io.github.jonathangtd.bimmerdata.export.TelegrafRuntime
import io.github.jonathangtd.bimmerdata.export.WriteResult
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardStore
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
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
            acquisition = AcquisitionInfo(s.state.name, s.cyclesPerSecond, s.errorCount, s.message, repo.readStats()?.summary()),
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

    override fun profiles(): ProfileSet = ProfileStore.get(app).load()

    /** Read, edit and write under the profile store's process-wide lock (the phone and Android Auto follow). */
    override fun <T> editProfiles(op: (ProfileSet) -> Pair<ProfileSet, T>): T {
        var result: Result<T>? = null
        val written = ProfileStore.get(app).update { current -> op(current).also { result = Result.success(it.second) }.first }
        checkNotNull(written) { "the profiles could not be written" }
        return result!!.getOrThrow()
    }

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
            api = ApiRuntime.get(app).state.value.tls.let { tls ->
                ApiSettingsView(
                    s.api.enabled, s.api.port, s.api.scopes.sortedBy { it.ordinal }.map { it.id },
                    https = s.api.https, certificateSha256 = tls?.certificateSha256, publicKeyPin = tls?.publicKeyPin,
                    localNetworkAccess = LocalNetwork.state(app),
                )
            },
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
            mode = if (t.mode == ExportMode.Live) "live" else "afterTrip",
            state = TelegrafStateView(
                phase = st.phase.name.replaceFirstChar { it.lowercase(Locale.ROOT) },
                queuedPoints = st.queuedLines, queuedBytes = st.queuedBytes, refusedPoints = st.refusedLines, rejectedPoints = st.rejectedLines,
                blocked = st.blocked, freeBytes = st.freeBytes, reserveBytes = st.reserveBytes, sentPoints = st.sentLines,
                lastSuccessMs = st.lastSuccessMs, lastError = st.lastError, rejectedBatches = st.rejectedBatches,
            ),
        )
    }

    override fun updateTelegraf(patch: TelegrafPatch): TelegrafView {
        settingsStore.updateTelegraf { t ->
            t.copy(
                enabled = patch.enabled ?: t.enabled,
                url = patch.url ?: t.url,
                car = patch.car ?: t.car,
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

    override fun setTelegrafAuth(patch: TelegrafAuthPatch): TelegrafView {
        val mode = when (patch.auth) {
            "none" -> TelegrafAuthMode.None
            "basic" -> TelegrafAuthMode.Basic
            else -> TelegrafAuthMode.Token
        }
        if (patch.secret != null && mode != TelegrafAuthMode.None) {
            SecretStore.get(app).set(if (mode == TelegrafAuthMode.Basic) SecretStore.TELEGRAF_PASSWORD else SecretStore.TELEGRAF_TOKEN, patch.secret)
        }
        settingsStore.updateTelegraf { t -> t.copy(authMode = mode, username = patch.username ?: t.username) }
        telegrafRuntime.sendNow()
        return telegraf()
    }

    override fun clearTelegrafQueue() = telegrafRuntime.clearQueue()

    override fun retryTelegrafRejected(): Int = telegrafRuntime.retryRejected()

    override fun deleteTelegrafRejected() = telegrafRuntime.deleteRejected()

    override fun openTelegrafRejected(): InputStream? {
        val files = telegrafRuntime.rejectedFiles().filter { it.isFile }
        if (files.isEmpty()) return null
        // concatenated gzip members are a valid gzip file
        return SequenceInputStream(Collections.enumeration(files.map { f -> java.io.BufferedInputStream(f.inputStream()) }))
    }

    // ---- setup: ECU files, permissions, Bluetooth, settings pages ----

    override val ecuFiles: EcuFileStore by lazy {
        EcuFileStore(
            app.filesDir,
            importDir = EcuImportFolder.dir(app),
            freeSpace = { EcuImportFolder.allocatableBytes(app) },
            beforeReplace = {
                // the interpreter reads the folder being replaced (same as the in-app import)
                if (repo.connection.value != LiveConnection.Disconnected) {
                    repo.disconnect()
                    val end = System.currentTimeMillis() + 5_000
                    while (repo.connection.value != LiveConnection.Disconnected && System.currentTimeMillis() < end) Thread.sleep(50)
                }
            },
            afterChange = { repo.ecuFilesChanged() },
        )
    }

    override fun permissions(): List<PermissionView> = AndroidPermissions.report(app)

    override fun bluetooth(): BluetoothView {
        val bt = settingsStore.state.value.bluetooth
        val c = repo.connection.value
        return BluetoothView(
            permission = when {
                BluetoothAccess.connectPermission == null -> "notRequired"
                BluetoothAccess.hasConnectPermission(app) -> "granted"
                else -> "missing"
            },
            enabled = runCatching { BluetoothAccess.isEnabled(app) }.getOrDefault(false),
            adapter = bt.address?.let { BluetoothAdapterView(bt.name, it) },
            connectOnStart = bt.connectOnStart,
            connected = c is LiveConnection.Connected && c.mode == LiveMode.Bluetooth,
            adapterInfo = repo.adapterInfo.value?.let(::adapterView),
        )
    }

    override fun pairedDevices(): List<PairedDeviceView> {
        val current = settingsStore.state.value.bluetooth.address
        return BluetoothAccess.pairedDevices(app).map {
            PairedDeviceView(it.name, it.address, it.likelyObd, it.lowEnergyOnly, it.address.equals(current, ignoreCase = true))
        }
    }

    override fun selectBluetoothAdapter(address: String?, forget: Boolean, connectOnStart: Boolean?): BluetoothView {
        val device = address?.let { a -> BluetoothAccess.pairedDevices(app).firstOrNull { it.address.equals(a, ignoreCase = true) } }
        settingsStore.updateBluetooth { b ->
            when {
                forget -> b.copy(address = null, name = null)
                device != null -> b.copy(address = device.address, name = device.name, connectOnStart = connectOnStart ?: b.connectOnStart)
                else -> b.copy(connectOnStart = connectOnStart ?: b.connectOnStart)
            }
        }
        return bluetooth()
    }

    override fun readBluetoothAdapter(): AdapterInfoView? = adapterCall { repo.readAdapter() }

    override fun writeCanMode(code: Int): AdapterInfoView? {
        val mode = CanMode.fromCode(code) ?: throw ApiException(400, "bad_request", "Unknown CAN mode $code")
        return adapterCall { repo.writeCanMode(mode) }
    }

    private fun adapterCall(block: suspend () -> AdapterInfo?): AdapterInfoView? = try {
        runBlocking { withTimeout(20_000) { block() } }?.let(::adapterView)
    } catch (e: TimeoutCancellationException) {
        throw ApiException(503, "adapter_timeout", "The adapter did not answer within 20 s")
    } catch (e: CableUnavailableException) {
        throw ApiException(409, "not_connected_bluetooth", e.message ?: "Not connected over Bluetooth")
    } catch (e: java.io.IOException) {
        throw ApiException(503, "adapter_error", "Adapter error: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun adapterView(i: AdapterInfo) = AdapterInfoView(
        type = i.typeName, firmware = i.firmwareText, serial = i.serialText, voltage = i.voltage,
        canMode = i.canModeCode?.let { code ->
            when (CanMode.fromCode(code)) {
                CanMode.Auto -> "auto"
                CanMode.KLineOnly -> "kLineOnly"
                CanMode.DCan500 -> "dCan500"
                CanMode.Can100 -> "can100"
                null -> "unknown"
            }
        },
        canModeCode = i.canModeCode, reachesKLine = i.canMode?.reachesKLine,
    )

    private val gpsRuntime by lazy { GpsRuntime.get(app) }

    override fun gps(): GpsView {
        val g = settingsStore.state.value.gps
        val s = gpsRuntime.state.value
        return GpsView(
            enabled = g.enabled, intervalMs = g.intervalMs, intervals = GpsSettings.INTERVALS,
            permission = if (gpsRuntime.hasPermission()) "granted" else "missing",
            locationOn = runCatching { gpsRuntime.locationEnabled() }.getOrDefault(false),
            state = when (s) {
                GpsState.Off -> "off"
                GpsState.Idle -> "idle"
                GpsState.NoPermission -> "noPermission"
                GpsState.LocationOff -> "locationOff"
                GpsState.Searching -> "searching"
                is GpsState.Fix -> "fix"
            },
            fixAtMs = (s as? GpsState.Fix)?.timeMs,
            accuracyM = (s as? GpsState.Fix)?.accuracyM,
        )
    }

    override fun updateGps(enabled: Boolean?, intervalMs: Long?): GpsView {
        settingsStore.updateGps { g -> g.copy(enabled = enabled ?: g.enabled, intervalMs = intervalMs ?: g.intervalMs) }
        gpsRuntime.recheck()
        return gps()
    }

    override fun recordingSettings(): RecordingSettingsView {
        val r = settingsStore.state.value.recording
        return RecordingSettingsView(
            folder = RecordingStorage.describe(app),
            customFolder = RecordingStorage.chosenFolder(app) != null,
            rowIntervalMs = r.rowIntervalMs,
            rowIntervals = RecordingConfig.ROW_INTERVALS,
            filePrefix = r.filePrefix,
            nextFileName = CsvSessionRecorder.fileName(System.currentTimeMillis(), prefix = r.filePrefix, profile = ProfileStore.get(app).load().active?.name),
        )
    }

    override fun updateRecordingSettings(patch: RecordingSettingsPatch): RecordingSettingsView {
        if (patch.useDefaultFolder) RecordingStorage.reset(app)
        settingsStore.updateRecording { r ->
            r.copy(
                rowIntervalMs = patch.rowIntervalMs ?: r.rowIntervalMs,
                filePrefix = patch.filePrefix?.let(CsvSessionRecorder::safePrefix) ?: r.filePrefix,
            )
        }
        return recordingSettings()
    }

    private val translationSettings by lazy { TranslationSettings.get(app) }
    private val translationRepo by lazy { TranslationRepository.get(app) }

    override fun translations(): TranslationsView {
        val c = translationSettings.state.value
        val s = translationRepo.state.value
        return TranslationsView(
            enabled = c.enabled, language = c.language, effectiveLanguage = c.effectiveLanguage, languages = TranslationConfig.LANGUAGES.filter { it.isNotEmpty() },
            source = if (c.source == TranslationSource.Folder) "folder" else "url",
            baseUrl = c.baseUrl, defaultBaseUrl = TranslationConfig.DEFAULT_URL, tokenSet = c.token.isNotEmpty(),
            folder = c.folderUri?.let { RecordingStorage.label(it.toUri()) },
            selection = if (c.selection == EcuSelection.Chosen) "chosen" else "auto",
            chosen = c.chosen.sorted(), cachedEcus = translationRepo.cachedSgbds(),
            updating = s.updating,
            lastUpdate = s.lastUpdate?.let { TranslationUpdateView(it.updated, it.notFound, it.errors, it.atMs) },
            lastError = s.lastError,
        )
    }

    override fun updateTranslations(patch: TranslationsPatch): TranslationsView {
        val targets = if (patch.selection == "chosen" && patch.chosen == null) translationRepo.updateTargets().toSet() else null
        translationSettings.update { c ->
            val selection = when (patch.selection) { null -> c.selection; "chosen" -> EcuSelection.Chosen; else -> EcuSelection.Auto }
            c.copy(
                language = patch.language ?: c.language,
                source = when (patch.source) { null -> c.source; "folder" -> TranslationSource.Folder; else -> TranslationSource.Url },
                baseUrl = patch.baseUrl ?: c.baseUrl,
                token = patch.token ?: c.token,
                selection = selection,
                // like the phone: switching to "chosen" with an empty list starts from the current targets
                chosen = patch.chosen?.toSet() ?: if (selection == EcuSelection.Chosen && c.chosen.isEmpty()) targets.orEmpty() else c.chosen,
            )
        }
        return translations()
    }

    override fun updateTranslationsNow(): TranslationsView {
        translationRepo.updateAll()
        return translations().copy(updating = true)
    }

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
