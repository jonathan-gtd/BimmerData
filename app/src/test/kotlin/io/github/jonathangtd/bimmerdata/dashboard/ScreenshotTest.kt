/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.car.CarScreen
import io.github.jonathangtd.bimmerdata.dashboard.ui.DashboardHeader
import io.github.jonathangtd.bimmerdata.dashboard.ui.EmptyPage
import io.github.jonathangtd.bimmerdata.dashboard.ui.SegmentedTabs
import io.github.jonathangtd.bimmerdata.dashboard.ui.SgbdStep
import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import io.github.jonathangtd.bimmerdata.live.AcquisitionState
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.ui.ConnectionPillContent
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.live.SgbdInfo
import io.github.jonathangtd.bimmerdata.ui.BimmerDataTheme
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * Renders dashboard screens to PNG on the JVM (Robolectric native graphics), to check them
 * without a phone. Files go to BIMMERDATA_SCREENSHOT_DIR, else app/build/screenshots. Nothing
 * is compared: the images are for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    private fun save(bitmap: Bitmap, name: String) {
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    /** Composes [content] in a bare activity and draws its window into a bitmap (software canvas). */
    private fun capture(name: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BimmerDataTheme { content() } }
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        save(bitmap, name)
    }

    /** The dashboard as on first launch: header with the app name, no page, the empty state. */
    @Composable
    private fun FirstLaunch(title: String) {
        Column(Modifier.fillMaxSize().background(Cockpit.Background).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(14.dp))
            DashboardHeader(
                title = title, onRename = {}, status = AcquisitionStatus(), rec = RecordingState(),
                onRecord = {}, onEdit = {}, connection = LiveConnection.Disconnected, lastMode = null, onConnect = {}, onDisconnect = {},
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().weight(1f)) { EmptyPage(onAdd = {}, onAddPage = {}) }
        }
    }

    /** Every state of the connection pill, top to bottom. */
    @Composable
    private fun Pills() {
        val usb = LiveConnection.Connected(LiveMode.Usb, "FTDI")
        Column(
            Modifier.fillMaxSize().background(Cockpit.Background).padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
        ) {
            ConnectionPillContent(LiveConnection.Disconnected, AcquisitionStatus(), null, {}, {})
            ConnectionPillContent(LiveConnection.Disconnected, AcquisitionStatus(), LiveMode.Usb, {}, {})
            ConnectionPillContent(LiveConnection.Failed(LiveMode.Usb, "No FTDI cable found (VID 0403)."), AcquisitionStatus(), LiveMode.Usb, {}, {})
            ConnectionPillContent(LiveConnection.Connecting(LiveMode.Usb), AcquisitionStatus(AcquisitionState.Connecting), LiveMode.Usb, {}, {})
            ConnectionPillContent(usb, AcquisitionStatus(AcquisitionState.Running, 22.4), LiveMode.Usb, {}, {})
            ConnectionPillContent(usb, AcquisitionStatus(AcquisitionState.Connecting), LiveMode.Usb, {}, {})
            ConnectionPillContent(usb, AcquisitionStatus(AcquisitionState.Error, 0.0, 3, "msv70 block of 5: no response"), LiveMode.Usb, {}, {})
            ConnectionPillContent(LiveConnection.Connected(LiveMode.Simulation, "Sim"), AcquisitionStatus(AcquisitionState.Running, 23.1), LiveMode.Simulation, {}, {})
        }
    }

    @Test
    fun connectionPills() = capture("connection_pills.png") { Pills() }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun connectionPillsFrench() = capture("connection_pills_fr.png") { Pills() }

    @Test
    fun phoneFirstLaunch() = capture("phone_first_launch.png") { FirstLaunch("BimmerData") }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun phoneFirstLaunchFrench() = capture("phone_first_launch_fr.png") { FirstLaunch("Ma 330i") }

    private val choices = SgbdChoices.of(
        listOf(
            SgbdInfo("cas", "CAS · Keine Bemerkung", 243),
            SgbdInfo("dsc_87", "Dynamische Stabilitaets Control DSC E87,E90 · Conti_Teves MK60, BMW_FAST, E8x,E9x", 373),
            SgbdInfo("frm_87", "FRM_87 · SGBD fuer FRMFA-E87", 418),
            SgbdInfo("gs19d", "Getriebesteuerung GS19.11 · SGBD fuer GS19.11", 523),
            SgbdInfo("ihka87", "IHKA · Muster-SGBD fuer PL2", 418),
            SgbdInfo("kombi87", "KOMBI E87", 120),
            SgbdInfo("msv70", "MSV70 fuer N52 mit EWS3 oder CAS · SGBD für MSV70 Serie mit SW 0911 an ZGM oder K-Line", 1197),
        ),
        listOf("msv70", "gs19d", "kombi87"),
    )

    @Test
    fun sgbdPicker() = capture("picker_sgbd.png") {
        Column(Modifier.fillMaxSize().background(Cockpit.Card).padding(top = 16.dp)) { SgbdStep(choices) {} }
    }

    private class StaticFeed(private val values: Map<String, Double>) : GaugeFeed {
        override val nowMs: Long = System.currentTimeMillis()
        override fun sample(id: String): Sample? = values[id]?.let { Sample(it, null, nowMs) }
        override fun history(id: String): ValueHistory? = null
    }

    @Test
    fun carPages() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        save(CarScreen.render(context, Locale.ENGLISH, null, 0, 0, emptyMap(), StaticFeed(emptyMap()), AcquisitionStatus(), RecordingState()), "car_no_page.png")
        val empty = DefaultDashboard.carPage("Page 1")
        save(CarScreen.render(context, Locale.ENGLISH, empty, 0, 1, emptyMap(), StaticFeed(emptyMap()), AcquisitionStatus(), RecordingState()), "car_empty.png")
        val example = Msv70Example.create().carPages[0]
        val feed = StaticFeed(mapOf(Msv70.OIL_TEMP to 104.0, Msv70.COOLANT_TEMP to 92.0, Msv70.STFT_B1 to 2.3, Msv70.STFT_B2 to -1.6, Msv70.RPM to 2350.0))
        save(CarScreen.render(context, Locale.ENGLISH, example, 0, 3, emptyMap(), feed, AcquisitionStatus(), RecordingState()), "car_msv70_example.png")
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun carNoPageFrench() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        save(CarScreen.render(context, Locale.FRANCE, null, 0, 0, emptyMap(), StaticFeed(emptyMap()), AcquisitionStatus(), RecordingState()), "car_no_page_fr.png")
    }
}
