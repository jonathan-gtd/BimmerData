/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.Sample
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
    var settings = SettingsView(ConnectMode.Simulation, false, "Documents/BimmerData/", ApiSettingsView(true, 8765, listOf("read", "layout")))
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

    override fun status() = BackendStatus(
        connection,
        AcquisitionInfo(if (connection.state == "connected") "Running" else "Stopped", if (connection.state == "connected") 12.5 else 0.0, 0),
        requested.map { it.id },
    )

    override fun ecus() = listOf(EcuInfo("msv70", EcuSource.Catalog), EcuInfo("ds2_ews", EcuSource.Catalog))
    override fun measurements(sgbd: String) = catalog.filter { it.sgbd == sgbd }
    override fun setApiMeasurements(measurements: List<Measurement>) { requested = measurements }
    override fun loadDashboard() = dashboard
    override fun saveDashboard(dashboard: Dashboard) { this.dashboard = dashboard; saves++ }
    override fun defaultDashboard() = Dashboard(phonePages = listOf(Page(name = "Default", columns = 2, rows = 6)), carPages = listOf(Page(name = "Default", columns = 4, rows = 1)))

    override fun renderPage(request: RenderRequest): ByteArray {
        lastRender = request
        return PNG_SIGNATURE + ByteArray(8)
    }

    override fun connect(mode: ConnectMode?) {
        val m = mode ?: settings.defaultMode
        connection = ConnectionInfo("connecting", m)
        thread(isDaemon = true) {
            Thread.sleep(100)
            connection = ConnectionInfo("connected", m, if (m == ConnectMode.Simulation) "Simulation (E90 with MSV70)" else "FT232R")
        }
    }

    override fun disconnect() {
        connection = ConnectionInfo("disconnected")
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
            defaultMode = patch.defaultMode ?: settings.defaultMode,
            autoConnectOnCable = patch.autoConnectOnCable ?: settings.autoConnectOnCable,
        )
        return settings
    }

    companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
