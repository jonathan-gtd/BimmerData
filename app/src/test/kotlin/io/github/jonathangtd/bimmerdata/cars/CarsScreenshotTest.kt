/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.cars

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.dashboard.ui.ProfileActions
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleScanner
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.ui.BimmerDataTheme
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import io.github.jonathangtd.bimmerdata.vehicle.VehicleState
import io.github.jonathangtd.bimmerdata.vehicle.VehicleTask
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** My cars and the Profiles tab: the list, the empty start screen, a detection running and the ECU removal question (visual check). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CarsScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    private fun capture(name: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BimmerDataTheme { Box(Modifier.fillMaxSize().background(Cockpit.Background)) { content() } } }
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    private val ecus = listOf("msv70", "egs", "kombi87", "dsc_87", "cas", "kbm60").mapIndexed { i, s -> VehicleEcu(0x12 + i, s.uppercase(), sgbd = s) }
    private val daily = Vehicle("AB12345", "E90 330i", vinShort = "AB12345", series = "E8x/E9x", ecus = ecus, scannedAtMs = 1)
    private val track = Vehicle("car-track", "Track car", series = "E46", model = "M3", addedSgbds = listOf("ms430"))
    private val project = Vehicle("car-project", "Project")
    private val rows = listOf(CarRowInfo(daily, 12, selected = true), CarRowInfo(track, 2, selected = false), CarRowInfo(project, 0, selected = false))
    private val state = VehicleState(garage = Garage(listOf(daily, track, project), daily.id))
    private val none = CarActions({}, {}, {}, {}, {})

    @Composable
    private fun Cars(rows: List<CarRowInfo>, state: VehicleState) =
        CarsContent(rows = rows, state = state, onBack = {}, onAdd = {}, actions = none, onCancel = {}, onDismissMessage = {})

    @Test fun cars() = capture("cars.png") { Cars(rows, state) }

    @Composable
    private fun CarTab(car: Vehicle?, state: VehicleState, cars: List<Vehicle> = rows.map { it.car }) = CarTabContent(
        car, cars = cars, state = state, set = ProfileSet(), onActivate = {}, onNew = {}, onImport = {},
        actions = ProfileActions({}, {}, {}, {}), carActions = CarTabActions(),
        connection = {
            io.github.jonathangtd.bimmerdata.ui.ConnectionPillContent(
                io.github.jonathangtd.bimmerdata.live.LiveConnection.Disconnected, io.github.jonathangtd.bimmerdata.live.AcquisitionStatus(),
                io.github.jonathangtd.bimmerdata.live.LiveMode.Usb, {}, {},
            )
        },
    )

    /** A fresh install: no car at all, so no profile either; connect (the VIN adds the car) or add it by hand. */
    @Test @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun carTabFreshInstallFrench() = capture("car_tab_fresh_fr.png") { CarTab(null, VehicleState(), cars = emptyList()) }

    @Test @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun carTabDetectingFrench() = capture("car_tab_detecting_fr.png") {
        CarTab(daily, state.copy(task = VehicleTask.Scanning(VehicleScanner.Progress(VehicleScanner.Step.Variants, 7, 21, "EGS"))))
    }

    @Test fun carTabNoCar() = capture("car_tab_no_car.png") { CarTab(null, VehicleState()) }

    @Test @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun carTabNoCarFrench() = capture("car_tab_no_car_fr.png") { CarTab(null, VehicleState()) }

    @Test @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun carTabHandFrench() = capture("car_tab_hand_fr.png") { CarTab(track, state) }

    @Test @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun carsFrench() = capture("cars_fr.png") { Cars(rows, state) }

    @Test fun carsEmpty() = capture("cars_empty.png") { Cars(emptyList(), VehicleState()) }

    @Test @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun carsEmptyFrench() = capture("cars_empty_fr.png") { Cars(emptyList(), VehicleState()) }

    @Test @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun carsDetectingFrench() = capture("cars_detecting_fr.png") {
        Cars(rows, state.copy(task = VehicleTask.Scanning(VehicleScanner.Progress(VehicleScanner.Step.Variants, 7, 21, "EGS"))))
    }

    @Test @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun ecuInUseFrench() = capture("cars_ecu_in_use_fr.png") {
        val cards = Dashboard(phonePages = listOf(Page(name = "P", columns = 2, rows = 2, cards = List(3) { Card(type = GaugeType.Dial, measurementIds = listOf("egs/0x01")) })))
        val p1 = Profile(name = "E90 330i · Boîte", carId = daily.id, dashboard = cards)
        val p2 = Profile(name = "E90 330i · Circuit", carId = daily.id)
        // a dialog is its own window (not drawn here): its body on the dialog's card
        androidx.compose.material3.Surface(color = Cockpit.Card, shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), modifier = Modifier.padding(24.dp)) {
            Box(Modifier.padding(24.dp)) { EcuInUseText("egs", listOf(EcuUse(p1, true, 3, 1), EcuUse(p2, true, 2, 0)), byDetection = true) }
        }
    }
}
