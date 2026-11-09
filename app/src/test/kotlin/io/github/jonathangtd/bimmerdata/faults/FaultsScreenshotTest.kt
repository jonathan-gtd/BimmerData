/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.faults

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.jonathangtd.bimmerdata.ediabas.FakeInterface
import io.github.jonathangtd.bimmerdata.ediabas.sim.VehicleSimulator
import io.github.jonathangtd.bimmerdata.live.vehicle.EdiabasJobRunner
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultService
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleScanner
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleTables
import io.github.jonathangtd.bimmerdata.ui.BimmerDataTheme
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.vehicle.DetailState
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import io.github.jonathangtd.bimmerdata.vehicle.LinkMode
import io.github.jonathangtd.bimmerdata.vehicle.VehicleMessage
import io.github.jonathangtd.bimmerdata.vehicle.VehicleState
import io.github.jonathangtd.bimmerdata.vehicle.VehicleTask
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FaultsScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    /** [settleMs]: time left to background loads (the ECU functions are read off the main thread). */
    private fun capture(name: String, settleMs: Long = 0, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BimmerDataTheme { Box(Modifier.fillMaxSize().background(Cockpit.Background)) { content() } } }
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        repeat((settleMs / 100).toInt()) {
            Thread.sleep(100)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    private fun scanned(): VehicleState {
        val env = System.getenv("BIMMERDATA_ECU_DIR")
        assumeTrue(env != null)
        val dir = File(env!!)
        val sim = VehicleSimulator.e90(engine = null)
        val fake = FakeInterface().apply { multiResponder = sim::respond }
        EdiabasJobRunner(dir, fake).use { runner ->
            runBlocking { runner.connect() }
            val tables = VehicleTables(dir)
            val vehicle = runBlocking { VehicleScanner(runner, tables).scan() }.vehicle!!.copy(label = "330i Touring")
            val service = FaultService(runner, tables)
            val faults = runBlocking { service.readAll(vehicle) }.map { if (it.address == 0x64) it.copy(status = io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus.NotResponding, message = "IFH-0009: NO RESPONSE FROM CONTROLUNIT") else it }
            val dme = vehicle.ecu(0x12)!!
            val detail = runBlocking { service.detail(dme, faults.first { it.address == 0x12 }.faults.first()) }.getOrThrow()
            return VehicleState(
                garage = Garage(listOf(vehicle), vehicle.id, mapOf(vehicle.id to faults), LinkMode.Usb),
                details = mapOf(VehicleState.detailKey(vehicle.id, 0x12, 0x2F08) to DetailState.Loaded(detail)),
            )
        }
    }

    @Composable
    private fun Overview(state: VehicleState) = VehicleOverview(
        state = state, onDetect = {}, onOpenCars = {}, onReadFaults = {}, onClearAll = {}, onShare = {}, onCancel = {},
        onSelectVehicle = {}, onRename = { _, _ -> }, onForget = {}, onSeries = {}, seriesChoices = { emptyList() }, onOpenEcu = {},
        onDismissMessage = {},
        connectionBar = {
            io.github.jonathangtd.bimmerdata.ui.ConnectionPillContent(
                io.github.jonathangtd.bimmerdata.live.LiveConnection.Connected(io.github.jonathangtd.bimmerdata.live.LiveMode.Usb, "FTDI"),
                io.github.jonathangtd.bimmerdata.live.AcquisitionStatus(io.github.jonathangtd.bimmerdata.live.AcquisitionState.Running, 21.7),
                io.github.jonathangtd.bimmerdata.live.LiveMode.Usb, {}, {},
            )
        },
    )

    @Test
    fun empty() = capture("faults_empty.png") { Overview(VehicleState()) }

    /** A car added by hand, ECUs not detected yet: the one-tap detection. */
    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun detectFirstFrench() {
        val car = io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle("car-1", "Project car", series = "E46")
        capture("faults_detect_first_fr.png") { Overview(VehicleState(garage = Garage(listOf(car), car.id))) }
    }

    @Test
    fun overview() {
        val s = scanned()
        capture("faults_overview.png") { Overview(s) }
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun overviewFrenchScanning() {
        val s = scanned().copy(task = VehicleTask.ReadingFaults(5, 15, "DSC"), message = VehicleMessage(VehicleMessage.Kind.Cleared, "DME/DDE"))
        capture("faults_overview_fr_busy.png") { Overview(s) }
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun overviewFrench() {
        val s = scanned()
        capture("faults_overview_fr.png") { Overview(s) }
    }

    /** The DME detail with its functions (the ECU file copied where the app keeps its ECU files). */
    @Test
    @Config(qualifiers = "fr-w411dp-h2400dp-xxhdpi")
    fun ecuDetailFunctionsFrench() {
        val s = scanned()
        val v = s.current!!
        org.robolectric.shadows.ShadowLog.stream = System.out
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val ecuDir = File(app.filesDir, "ecu").apply { mkdirs() }
        File(System.getenv("BIMMERDATA_ECU_DIR")!!).listFiles()!!.first { it.name.equals("msv70.prg", true) }.copyTo(File(ecuDir, "msv70.prg"), overwrite = true)
        capture("faults_ecu_dme_functions_fr.png", settleMs = 2000) {
            EcuDetailScreen(
                v, v.ecu(0x12)!!, s, {}, {}, {}, {}, {}, {},
                initialFunctions = io.github.jonathangtd.bimmerdata.live.vehicle.EcuFunctionKind.Test to "STEUERN_EKP",
            )
        }
    }

    @Test
    fun ecuDetail() {
        val s = scanned()
        val v = s.current!!
        capture("faults_ecu_dme.png") {
            EcuDetailScreen(v, v.ecu(0x12)!!, s, {}, {}, {}, {}, {}, {}, initiallyExpanded = 0x2F08)
        }
    }
}
