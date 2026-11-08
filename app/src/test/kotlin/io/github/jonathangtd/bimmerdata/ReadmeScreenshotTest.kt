/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.dashboard.car.CarScreen
import io.github.jonathangtd.bimmerdata.dashboard.ui.CardGrid
import io.github.jonathangtd.bimmerdata.dashboard.ui.DashboardHeader
import io.github.jonathangtd.bimmerdata.dashboard.ui.GridActions
import io.github.jonathangtd.bimmerdata.dashboard.ui.MeasurementList
import io.github.jonathangtd.bimmerdata.dashboard.ui.ProfileEcuBar
import io.github.jonathangtd.bimmerdata.dashboard.ui.ProfilesSheetContent
import io.github.jonathangtd.bimmerdata.dashboard.ui.SegmentedTabs
import io.github.jonathangtd.bimmerdata.dashboard.ui.SheetHeader
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.FakeInterface
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import io.github.jonathangtd.bimmerdata.ediabas.sim.VehicleSimulator
import io.github.jonathangtd.bimmerdata.faults.EcuDetailScreen
import io.github.jonathangtd.bimmerdata.faults.VehicleOverview
import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeRenderer
import io.github.jonathangtd.bimmerdata.gauges.GaugeTheme
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import io.github.jonathangtd.bimmerdata.i18n.EcuSelection
import io.github.jonathangtd.bimmerdata.i18n.TranslationRepository
import io.github.jonathangtd.bimmerdata.i18n.TranslationSettings
import io.github.jonathangtd.bimmerdata.live.AcquisitionState
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.live.i18n.EcuTranslations
import io.github.jonathangtd.bimmerdata.live.i18n.TranslationCsv
import io.github.jonathangtd.bimmerdata.live.i18n.Translations
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.EdiabasJobRunner
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultService
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleScanner
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleTables
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import io.github.jonathangtd.bimmerdata.settings.SettingsScreen
import io.github.jonathangtd.bimmerdata.ui.BimmerDataTheme
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.ui.ConnectionPillContent
import io.github.jonathangtd.bimmerdata.ui.JobResultUi
import io.github.jonathangtd.bimmerdata.ui.ResultEntryUi
import io.github.jonathangtd.bimmerdata.ui.ResultSetUi
import io.github.jonathangtd.bimmerdata.ui.Tool32Screen
import io.github.jonathangtd.bimmerdata.ui.Tool32State
import io.github.jonathangtd.bimmerdata.ui.Tool32ViewModel
import io.github.jonathangtd.bimmerdata.vehicle.DetailState
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import io.github.jonathangtd.bimmerdata.vehicle.LinkMode
import io.github.jonathangtd.bimmerdata.vehicle.VehicleState
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
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The pictures of the README (docs/screenshots, see tools/readme_screenshots.sh): English UI,
 * demo data of the built-in simulation, nothing personal (no VIN, token, address or user's car).
 * Excluded from the normal build like every *ScreenshotTest (run with -Pscreenshots).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
class ReadmeScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots", "readme").apply { mkdirs() }

    private fun save(bitmap: Bitmap, name: String) {
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    private fun capture(name: String, heightDp: Int? = null, topDp: Int = 0, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BimmerDataTheme { Box(Modifier.fillMaxSize().background(Cockpit.Background)) { content() } } }
        repeat(4) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        val view = activity.window.decorView
        val density = view.resources.displayMetrics.density
        val top = (topDp * density).toInt()
        val h = heightDp?.let { (it * density).toInt().coerceAtMost(view.height - top) } ?: (view.height - top)
        val bitmap = Bitmap.createBitmap(view.width, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.translate(0f, -top.toFloat())
        view.draw(canvas)
        save(bitmap, name)
    }

    // ------------------------------------------------------------------ demo data

    private fun m(key: Int, label: String, unit: String, result: String, min: Double, max: Double) =
        Measurement("dme/0x%04X".format(key), "dme", label, unit, MeasurementSource.MeasurementTable(key, result), min, max)

    private val oil = m(0x4402, "Oil temperature", "°C", "STAT_OELTEMPERATUR_WERT", 40.0, 140.0)
    private val coolant = m(0x4300, "Coolant temperature", "°C", "STAT_KUEHLMITTELTEMPERATUR_WERT", 40.0, 120.0)
    private val trimB1 = m(0x5A81, "Short-term fuel trim, bank 1", "%", "STAT_INTEGRATOR_BANK1_WERT", -25.0, 25.0)
    private val trimB2 = m(0x5A82, "Short-term fuel trim, bank 2", "%", "STAT_INTEGRATOR_BANK2_WERT", -25.0, 25.0)
    private val rpm = m(0x4807, "Engine speed", "rpm", "STAT_MOTORDREHZAHL_WERT", 0.0, 7000.0)
    private val intake = m(0x4200, "Intake air temperature", "°C", "STAT_ANSAUGLUFTTEMPERATUR_WERT", -20.0, 80.0)
    private val lambdaB1 = m(0x5A50, "Lambda before catalyst, bank 1", "", "STAT_LAMBDA_BANK1_WERT", 0.8, 1.2)
    private val lambdaB2 = m(0x5A51, "Lambda before catalyst, bank 2", "", "STAT_LAMBDA_BANK2_WERT", 0.8, 1.2)
    private val load = m(0x5804, "Engine load", "%", "STAT_LAST_WERT", 0.0, 100.0)
    private val battery = m(0x460A, "Battery voltage", "V", "STAT_UBATT_WERT", 10.0, 16.0)
    private val throttle = m(0x4600, "Throttle angle", "%", "STAT_DROSSELKLAPPENWINKEL_WERT", 0.0, 100.0)
    private val ignition = m(0x5A49, "Ignition angle, cylinder 1", "°CRK", "STAT_ZUENDWINKEL_ZYL1_WERT", -10.0, 50.0)
    private val index = listOf(oil, coolant, trimB1, trimB2, rpm, intake, lambdaB1, lambdaB2, load, battery, throttle, ignition).associateBy { it.id }

    /** Live-looking values: a smooth drive for the last 3 minutes, sampled 20 times a second. */
    private class DemoFeed(override val nowMs: Long) : GaugeFeed {
        private val histories = HashMap<String, ValueHistory>()
        private val last = HashMap<String, Sample>()

        fun curve(id: String, value: (Double) -> Double) {
            val h = ValueHistory(retainMs = 5 * 60_000L)
            var t = nowMs - 3 * 60_000L
            var v = 0.0
            while (t <= nowMs) {
                v = value((nowMs - t) / 1000.0)
                h.add(t, v)
                t += 50
            }
            histories[id] = h
            last[id] = Sample(v, null, nowMs)
        }

        override fun sample(id: String): Sample? = last[id]
        override fun history(id: String): ValueHistory? = histories[id]
    }

    private fun feed(): DemoFeed = DemoFeed(System.currentTimeMillis()).apply {
        // s = seconds ago; gentle, continuous curves (no steps).
        curve(oil.id) { s -> 104.0 - 3.0 * (1 - exp(-s / 120.0)) + 0.4 * sin(s / 23.0) }
        curve(coolant.id) { s -> 91.0 + 0.8 * sin(s / 31.0) }
        curve(trimB1.id) { s -> 2.3 + 1.6 * sin(s / 3.1) }
        curve(trimB2.id) { s -> -1.4 + 1.3 * sin(s / 2.7 + 1.0) }
        curve(rpm.id) { s -> 2350.0 + 900.0 * sin(2 * PI * s / 38.0) + 260.0 * sin(2 * PI * s / 9.0) }
        curve(intake.id) { s -> 31.0 + 1.5 * sin(s / 40.0) }
        curve(lambdaB1.id) { s -> 1.0 + 0.025 * sin(s * 2.2) }
        curve(lambdaB2.id) { s -> 0.99 + 0.025 * sin(s * 2.0 + 0.7) }
        curve(load.id) { s -> 38.0 + 14.0 * sin(2 * PI * s / 38.0) }
        curve(battery.id) { s -> 14.1 + 0.05 * sin(s) }
        curve(throttle.id) { s -> 18.0 + 9.0 * sin(2 * PI * s / 38.0) }
        curve(ignition.id) { s -> 24.0 + 4.0 * sin(2 * PI * s / 38.0 + 0.4) }
    }

    private fun card(type: GaugeType, ms: List<Measurement>, title: String, col: Int, row: Int, cs: Int = 1, rs: Int = 1, decimals: Int? = null, labels: List<String>? = null) =
        Card(
            type = type, measurementIds = ms.map { it.id }, title = title, channelLabels = labels,
            min = ms[0].min, max = ms[0].max, decimals = decimals, col = col, row = row, colSpan = cs, rowSpan = rs,
            windowSeconds = if (type == GaugeType.Trend) 60 else null,
        )

    private val phonePage = Page(
        name = "Engine", columns = 2, rows = 6,
        cards = listOf(
            card(GaugeType.Dial, listOf(oil), "Oil", 0, 0, rs = 2, decimals = 0).copy(warnBelow = 80.0, warnAbove = 130.0),
            card(GaugeType.Dial, listOf(coolant), "Coolant", 1, 0, rs = 2, decimals = 0).copy(warnBelow = 75.0, warnAbove = 110.0),
            card(GaugeType.BipolarBar, listOf(trimB1, trimB2), "Fuel trim", 0, 2, cs = 2, rs = 2, decimals = 1, labels = listOf("Bank 1", "Bank 2"))
                .copy(warnBelow = -10.0, warnAbove = 10.0),
            card(GaugeType.Trend, listOf(rpm), "Engine speed", 0, 4, cs = 2, decimals = 0),
            card(GaugeType.BigNumber, listOf(intake), "Intake air", 0, 5, decimals = 0),
            card(GaugeType.MultiValue, listOf(lambdaB1, lambdaB2), "Lambda", 1, 5, decimals = 2, labels = listOf("B1", "B2")),
        ),
    )

    private val carPage = Page(
        name = "Engine", columns = 4, rows = 1,
        cards = listOf(
            card(GaugeType.Dial, listOf(oil), "Oil", 0, 0, decimals = 0),
            card(GaugeType.Dial, listOf(coolant), "Coolant", 1, 0, decimals = 0),
            card(GaugeType.BipolarBar, listOf(trimB1, trimB2), "Fuel trim", 2, 0, decimals = 1, labels = listOf("B1", "B2")),
            card(GaugeType.Dial, listOf(rpm), "Engine speed", 3, 0, decimals = 0),
        ),
    )

    private val running = AcquisitionStatus(AcquisitionState.Running, 22.4, message = "Simulation")
    private val simulation = LiveConnection.Connected(LiveMode.Simulation, "Simulation")

    @Composable
    private fun Pill() = ConnectionPillContent(simulation, running, LiveMode.Simulation, {}, {})

    private val noActions = GridActions({ _, _, _ -> false }, { _, _, _ -> false }, {}, { _, _ -> }, {})

    // ------------------------------------------------------------------ pictures

    @Test
    fun dashboard() {
        val feed = feed()
        capture("dashboard.png") {
            val density = LocalDensity.current
            val renderer = remember { GaugeRenderer(GaugeTheme.phone(ApplicationProvider.getApplicationContext(), density.density), GaugeFormat(Locale.US)) }
            val frame = remember { mutableLongStateOf(System.nanoTime()) }
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(14.dp))
                DashboardHeader(
                    title = "Demo · Engine", onRename = {}, status = running, rec = RecordingState(),
                    onRecord = {}, onEdit = {}, connection = simulation, lastMode = LiveMode.Simulation, onConnect = {}, onDisconnect = {},
                )
                Spacer(Modifier.height(12.dp))
                SegmentedTabs(labels = listOf("Engine", "Mixture", "Temperatures"), selected = 0, onSelect = {})
                Spacer(Modifier.height(12.dp))
                CardGrid(
                    page = phonePage, renderer = renderer, feed = feed, frameClock = frame, index = index, editing = false,
                    gapPx = with(density) { 12.dp.toPx() }, cornerRadiusDp = 20f, actions = noActions, addLabel = "",
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp),
                )
            }
        }
    }

    @Test
    fun cardPicker() = capture("card_picker.png") {
        val selected = remember { mutableStateListOf<String>() }
        Surface(color = Cockpit.Card, modifier = Modifier.fillMaxSize().padding(top = 40.dp)) {
            Column {
                Spacer(Modifier.height(12.dp))
                SheetHeader("DME", onBack = {})
                ProfileEcuBar(listOf("dme", "egs", "kombi"), current = "dme", onPick = {}, onOther = {})
                MeasurementList(index.values.sortedBy { it.id }, GaugeType.Dial, selected, onConfirm = {})
            }
        }
    }

    private val profiles: ProfileSet = run {
        var s = ProfileSet()
        val demo = ProfileVehicle("Demo", "car")
        s = Profiles.create(s, "Demo · Engine", demo, listOf("dme"), 1, Dashboard(phonePages = listOf(phonePage, phonePage.copy(name = "Mixture")), carPages = listOf(carPage))).first
        s = Profiles.create(s, "Demo · Knock", demo, listOf("dme"), 2, Dashboard(phonePages = listOf(phonePage.copy(name = "Knock")), carPages = listOf(carPage))).first
        s = Profiles.create(s, "Demo · Gearbox", demo, listOf("egs", "dme"), 3, Dashboard(phonePages = listOf(phonePage.copy(name = "Gearbox")))).first
        s = Profiles.create(s, "Track day", ProfileVehicle("Track", "car"), listOf("dme", "dsc"), 4, Dashboard(phonePages = listOf(phonePage, phonePage, phonePage))).first
        Profiles.activate(s, s.profiles[0].id, 5)
    }

    @Test
    fun profiles() = capture("profiles.png", heightDp = 500) {
        Surface(color = Cockpit.Card, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            ProfilesSheetContent(profiles, {}, {}, {}, {}, {}, {}, {})
        }
    }

    /** The built-in demo car, scanned and read like in the app; the VIN is left out. */
    private fun scanned(): VehicleState {
        val env = System.getenv("BIMMERDATA_ECU_DIR")
        assumeTrue("needs the EDIABAS Ecu folder (BIMMERDATA_ECU_DIR)", env != null)
        val dir = File(env!!)
        val sim = VehicleSimulator.e90(engine = null)
        val fake = FakeInterface().apply { multiResponder = sim::respond }
        EdiabasJobRunner(dir, fake).use { runner ->
            runBlocking { runner.connect() }
            val tables = VehicleTables(dir)
            val vehicle = runBlocking { VehicleScanner(runner, tables).scan() }.vehicle!!.copy(label = "Demo car", vin = null, vinShort = null)
            val service = FaultService(runner, tables)
            val faults = runBlocking { service.readAll(vehicle) }
                .map { if (it.address == 0x64) it.copy(status = EcuFaultStatus.NotResponding, message = "No response") else it }
            val dme = vehicle.ecu(0x12)!!
            val detail = runBlocking { service.detail(dme, faults.first { it.address == 0x12 }.faults.first()) }.getOrThrow()
            return VehicleState(
                garage = Garage(listOf(vehicle), vehicle.id, mapOf(vehicle.id to faults), LinkMode.Usb),
                details = mapOf(VehicleState.detailKey(vehicle.id, 0x12, 0x2F08) to DetailState.Loaded(detail)),
            )
        }
    }

    /** English ECU texts from the repository's translation file, as the app downloads it. */
    private fun loadEnglish() {
        val file = File("../translations/en/msv70.csv")
        assumeTrue(file.isFile)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        TranslationSettings.get(app).update { it.copy(language = "en", selection = EcuSelection.Chosen, chosen = setOf("msv70")) }
        TranslationRepository.get(app)
        Translations.shared.reset("en", listOf(EcuTranslations("msv70", "en", TranslationCsv.parse(file.readText()).entries)))
    }

    @Test
    fun faults() {
        loadEnglish()
        val s = scanned()
        capture("faults.png") {
            VehicleOverview(
                state = s, onScan = {}, onReadFaults = {}, onClearAll = {}, onShare = {}, onCancel = {},
                onSelectVehicle = {}, onRename = { _, _ -> }, onForget = {}, onSeries = {}, seriesChoices = { emptyList() }, onOpenEcu = {},
                onDismissMessage = {}, connectionBar = { Pill() },
            )
        }
    }

    @Test
    fun faultDetail() {
        loadEnglish()
        val s = scanned()
        val v = s.current!!
        capture("fault_detail.png") { EcuDetailScreen(v, v.ecu(0x12)!!, s, {}, {}, {}, {}, {}, {}, initiallyExpanded = 0x2F08) }
    }

    @Test
    fun settings() = capture("settings.png") { SettingsScreen(initialPage = null) }

    /** A real job run on the simulated engine ECU, shown as Tool32 shows it (job and result part). */
    @Test
    @Config(qualifiers = "en-w411dp-h2000dp-xxhdpi")
    fun tool32() {
        val env = System.getenv("BIMMERDATA_ECU_DIR")
        assumeTrue(env != null)
        val ediabas = EdiabasNet("EcuPath=$env")
        val iface = EdInterfaceObd(SimulatedKLine.withEcu(false, respond = EcuSimulator(liveValues = true)::respond))
        ediabas.edInterfaceClass = iface
        check(iface.interfaceConnect())
        ediabas.resolveSgbdFile("msv70")
        ediabas.argString = "3;0x4402;0x4300;0x4807;0x4200"
        val start = System.nanoTime()
        ediabas.executeJob("STATUS_MESSWERTBLOCK_LESEN")
        val elapsed = (System.nanoTime() - start) / 1e6
        val sets = ediabas.resultSets!!
        val result = JobResultUi(
            title = "MSV70 · STATUS_MESSWERTBLOCK_LESEN",
            jobStatus = sets.last()["JOB_STATUS"]?.opData as? String, error = null,
            elapsedMs = elapsed.coerceAtLeast(118.0), telegrams = 3,
            sets = sets.mapIndexed { i, set ->
                ResultSetUi(i, set.values.filterNot { it.name.startsWith("_TEL") }.map { r ->
                    val unit = if (r.name.endsWith("_WERT")) set[r.name.removeSuffix("_WERT") + "_EINH"]?.opData as? String else null
                    ResultEntryUi(r.name, Tool32ViewModel.formatValue(r), unit?.takeIf { it.isNotBlank() }, r.resType.name.removePrefix("Type"))
                })
            },
            frames = emptyList(),
        )
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = Tool32ViewModel(app)
        vm.showForScreenshot(
            Tool32State(
                ignition = true, ecuFiles = listOf("msv70.prg"), sgbd = "msv70.prg", jobs = listOf("STATUS_MESSWERTBLOCK_LESEN"),
                job = "STATUS_MESSWERTBLOCK_LESEN", args = "3;0x4402;0x4300;0x4807;0x4200", result = result,
            ),
        )
        capture("tool32.png", heightDp = 1020, topDp = 452) { Tool32Screen(vm = vm, onBack = {}, connectionBar = { Pill() }) }
    }

    @Test
    fun androidAuto() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        save(CarScreen.render(context, Locale.ENGLISH, carPage, 0, 2, index, feed(), running, RecordingState()), "android_auto.png")
    }
}
