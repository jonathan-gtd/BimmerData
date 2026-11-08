/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.dashboard.Target
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream

/**
 * Everything the API needs from the app. The Android implementation
 * ([io.github.jonathangtd.bimmerdata.api.android.AndroidApiBackend]) bridges the live repository,
 * the dashboard store, the page renderers and the settings; the JVM tests use a fake.
 *
 * Calls come from the server's worker threads (never the main thread) and may block.
 */
interface ApiBackend {
    val appVersion: String

    fun status(): BackendStatus

    /** Latest sample per measurement id (all that were read since the app started). */
    val samples: StateFlow<Map<String, Sample>>

    /**
     * ECUs to offer. Today the SGBDs of the imported ECU folder that have a measurement table
     * ([EcuSource.Catalog]); once vehicle detection exists, the detected ECUs come first
     * ([EcuSource.Detected], see [DetectedEcuProvider]).
     */
    fun ecus(): List<EcuInfo>

    /** All measurements of [sgbd] (lower case, no extension); empty if unknown. */
    fun measurements(sgbd: String): List<Measurement>

    /** Measurements the API itself wants read (merged with the phone and car screens); empty = none. */
    fun setApiMeasurements(measurements: List<Measurement>)

    fun loadDashboard(): Dashboard
    fun saveDashboard(dashboard: Dashboard)
    fun defaultDashboard(): Dashboard

    /**
     * Read-modify-write of the layout with no other writer in between (the phone editor may save
     * at the same moment). The default is load + save; the Android backend uses the store's lock.
     */
    fun <T> editDashboard(op: (Dashboard) -> Pair<Dashboard, T>): T {
        val (d, result) = op(loadDashboard())
        saveDashboard(d)
        return result
    }

    /** PNG of [RenderRequest.page] as the phone or the Android Auto screen would draw it. */
    fun renderPage(request: RenderRequest): ByteArray

    /** Starts connecting (asynchronous; follow [status]). [mode] null = the default mode of the settings. */
    fun connect(mode: ConnectMode?)
    fun disconnect()

    val recording: StateFlow<RecordingState>
    fun startRecording()
    fun stopRecording()
    fun addMarker(label: String)

    fun recordings(): List<RecordingFile>

    /** Opens a recording listed by [recordings] by its exact name; null if there is none. */
    fun openRecording(name: String): InputStream?

    fun settings(): SettingsView

    /** Applies the writable settings of [patch] (validated by the caller) and returns the result. */
    fun updateSettings(patch: SettingsPatch): SettingsView

    /** Telegraf export settings and state, without the token / password; null = not available. */
    fun telegraf(): TelegrafView? = null

    /** Applies [patch] (validated by the caller) and returns the result. */
    fun updateTelegraf(patch: TelegrafPatch): TelegrafView = throw ApiException(501, "not_available", "The Telegraf export is not available")

    /** Empty write to the configured Telegraf listener. */
    fun testTelegraf(): TelegrafTest = throw ApiException(501, "not_available", "The Telegraf export is not available")

    /** Upload the waiting points now (also after an authentication error). */
    fun sendTelegrafNow() = Unit
}

/** Telegraf export as seen through the API (secrets are never returned). */
@Serializable
data class TelegrafView(
    val enabled: Boolean,
    val url: String,
    /** none, token or basic; changed on the phone only, with the secret. */
    val auth: String,
    val username: String,
    /** A token / password is stored (encrypted) on the phone. */
    val secretSet: Boolean,
    val car: String,
    val pointsPerSecond: Int,
    /** live or afterTrip */
    val mode: String,
    val state: TelegrafStateView,
)

@Serializable
data class TelegrafStateView(
    /** off, idle, waitingForNetwork, waitingForUnmetered, sending, retrying, authFailed */
    val phase: String,
    val queuedPoints: Long,
    /** Size of the queue on disk (gzip). */
    val queuedBytes: Long,
    /** Points not queued because the phone reached its free-space reserve (the CSV has them). */
    val refusedPoints: Long,
    /** Points of the batches Telegraf refused for good (HTTP 400), kept on the phone. */
    val rejectedPoints: Long,
    /** The phone reached its free-space reserve: new points are not queued. */
    val blocked: Boolean,
    val freeBytes: Long,
    val reserveBytes: Long,
    val sentPoints: Long,
    val lastSuccessMs: Long? = null,
    val lastError: String? = null,
)

data class TelegrafPatch(
    val enabled: Boolean? = null,
    val url: String? = null,
    val car: String? = null,
    val pointsPerSecond: Int? = null,
    /** "live" or "afterTrip" */
    val mode: String? = null,
)

@Serializable
data class TelegrafTest(val ok: Boolean, val message: String)

/** Future vehicle detection plugs in here (detected ECUs are offered before the catalog ones). */
fun interface DetectedEcuProvider {
    /** SGBD names identified on the connected car, or null while nothing was detected. */
    fun detectedSgbds(): List<String>?
}

@Serializable
enum class ConnectMode { @SerialName("usb") Usb, @SerialName("simulation") Simulation }

@Serializable
enum class EcuSource { @SerialName("detected") Detected, @SerialName("catalog") Catalog }

@Serializable
data class EcuInfo(val sgbd: String, val source: EcuSource)

@Serializable
data class ConnectionInfo(
    /** disconnected, connecting, connected, failed */
    val state: String,
    val mode: ConnectMode? = null,
    val label: String? = null,
    val message: String? = null,
)

@Serializable
data class AcquisitionInfo(
    /** Stopped, Connecting, Running, Error */
    val state: String,
    val readsPerSecond: Double,
    val errorCount: Int,
    val message: String? = null,
)

@Serializable
data class RecordingInfo(
    val recording: Boolean,
    val fileName: String? = null,
    val startedAtMs: Long? = null,
    val rows: Long = 0,
    val error: String? = null,
) {
    companion object {
        fun of(s: RecordingState) = RecordingInfo(s.recording, s.fileName, s.startedAtMs, s.rows, s.error)
    }
}

data class BackendStatus(
    val connection: ConnectionInfo,
    val acquisition: AcquisitionInfo,
    /** Ids the acquisition engine reads right now (phone, car, recorder and API merged). */
    val measurementsRead: List<String>,
    /** True while the car is known to be moving (always false until speed is read); diag writes are refused then. */
    val driving: Boolean = false,
)

@Serializable
data class RecordingFile(val name: String, val sizeBytes: Long, val modifiedMs: Long)

/** Settings as seen through the API; the `api` part is read-only (it can only be changed on the phone). */
@Serializable
data class SettingsView(
    val defaultMode: ConnectMode,
    val autoConnectOnCable: Boolean,
    val recordingFolder: String,
    val api: ApiSettingsView,
)

@Serializable
data class ApiSettingsView(val enabled: Boolean, val port: Int, val scopes: List<String>)

data class SettingsPatch(val defaultMode: ConnectMode? = null, val autoConnectOnCable: Boolean? = null)

class RenderRequest(
    val target: Target,
    val page: Page,
    val pageIndex: Int,
    val pageCount: Int,
    val width: Int,
    val height: Int,
    /** Catalog metadata (label, unit, range) of the measurements on the page. */
    val index: Map<String, Measurement>,
    val samples: Map<String, Sample>,
    val history: SampleHistory,
    val status: BackendStatus,
    val recording: RecordingState,
)
