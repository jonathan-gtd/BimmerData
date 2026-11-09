/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.jonathangtd.bimmerdata.dashboard.ui.SegmentedTabs
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeTheme
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import io.github.jonathangtd.bimmerdata.graph.ui.GraphPanel
import io.github.jonathangtd.bimmerdata.ui.AppTab
import io.github.jonathangtd.bimmerdata.ui.BimmerDataTheme
import io.github.jonathangtd.bimmerdata.ui.BottomBar
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
import kotlin.math.sin

/** Graphs rendered to PNG for review (see dashboard ScreenshotTest): overlay, bands, paused cursor, gaps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class GraphScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }
    private val now = System.currentTimeMillis()

    /** 10 min of synthetic readings at 10 reads/s, with a 40 s gap in the coolant curve. */
    private fun histories(): Map<String, ValueHistory> {
        val rpm = ValueHistory(capacity = 16_384, retainMs = 3_600_000)
        val cool = ValueHistory(capacity = 16_384, retainMs = 3_600_000)
        val lambda = ValueHistory(capacity = 16_384, retainMs = 3_600_000)
        val iat = ValueHistory(capacity = 16_384, retainMs = 3_600_000)
        var t = now - 600_000
        while (t <= now) {
            val s = (t - now) / 1000.0
            rpm.add(t, 2200 + 1400 * sin(s / 9.0) + 300 * sin(s / 1.7))
            if ((now - t) !in 70_000..110_000) cool.add(t, 88 + 4 * sin(s / 60.0))
            lambda.add(t, 1.0 + 0.03 * sin(s * 2.1))
            iat.add(t, 31 + 2 * sin(s / 40.0))
            t += 100
        }
        return mapOf("d/rpm" to rpm, "d/cool" to cool, "d/lambda" to lambda, "d/iat" to iat)
    }

    private val specs = listOf(
        GraphSeriesSpec("d/rpm", "Engine speed", "rpm", 0, GraphPalette.color(0), 280.0),
        GraphSeriesSpec("d/cool", "Coolant temperature", "°C", 0, GraphPalette.color(1), 6.0),
        GraphSeriesSpec("d/iat", "Intake air temperature", "°C", 0, GraphPalette.color(2), 6.0),
        GraphSeriesSpec("d/lambda", "Lambda bank 1", "λ", 2, GraphPalette.color(3), 0.016),
    )

    private fun render(name: String, frame: GraphFrame, locale: Locale = Locale.US, widthDp: Int = 395, heightDp: Int = 420) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = 3f
        val bitmap = Bitmap.createBitmap((widthDp * density).toInt(), (heightDp * density).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val theme = GaugeTheme.phone(context, density)
        canvas.drawColor(theme.background)
        val r = GraphRenderer(theme, GaugeFormat(locale), GraphTexts("No value read in this period") { n -> if (n == 1) "+1 axis: see Bands" else "+$n axes: see Bands" })
        val h = histories()
        r.draw(canvas, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), frame, h::get)
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    @Test
    fun overlayTwoUnits() = render("graph_overlay_2units.png", GraphFrame(specs.take(3), GraphAxes.Overlay, GraphView(120_000), now))

    @Test
    fun overlayFourValues() = render("graph_overlay_4values.png", GraphFrame(specs, GraphAxes.Overlay, GraphView(60_000), now))

    @Test
    fun bandsLive() = render("graph_bands.png", GraphFrame(specs, GraphAxes.Bands, GraphView(300_000), now))

    @Test
    fun pausedCursorGap() = render(
        "graph_paused_cursor.png",
        GraphFrame(specs.take(3), GraphAxes.Overlay, GraphView(120_000, endMs = now - 30_000), now, cursorMs = now - 100_000),
    )

    @Test
    fun bandsPausedCursor() = render(
        "graph_bands_cursor.png",
        GraphFrame(specs, GraphAxes.Bands, GraphView(60_000, endMs = now - 60_000), now, cursorMs = now - 85_000),
    )

    @Test
    fun hiddenAndEmpty() = render(
        "graph_no_data.png",
        GraphFrame(listOf(specs[1]), GraphAxes.Overlay, GraphView(20_000, endMs = now - 80_000), now),
    )

    /** Landscape squeezed the plot to a few dp: drawing must never crash, whatever the size. */
    @Test
    fun tinySizesDoNotCrash() {
        for (h in listOf(1, 20, 40, 60, 75, 90, 130)) for (axes in GraphAxes.entries) {
            render("graph_tiny_${axes}_$h.png", GraphFrame(specs, axes, GraphView(60_000), now, cursorMs = now - 10_000), heightDp = h)
        }
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun overlayFrench() = render("graph_overlay_fr.png", GraphFrame(specs.take(3), GraphAxes.Overlay, GraphView(1_800_000), now), Locale.FRANCE)

    // ---------------------------------------------------------------- whole tab

    private fun capture(name: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BimmerDataTheme { content() } }
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    private fun tab(name: String) {
        SharedHistory.clear()
        histories().forEach { (id, h) -> for (i in 0 until h.size) SharedHistory.add(id, h.timeAt(i), h.valueAt(i).toDouble()) }
        val graph = Graphs.add(emptyList(), "Warm-up", specs.take(3).map { it.id }, id = "g").single().copy(windowSeconds = 120)
        val format = GaugeFormat(Locale.getDefault())
        capture(name) {
            Column(Modifier.fillMaxSize().background(Cockpit.Background)) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
                    SegmentedTabs(labels = listOf("Warm-up", "Lambda", "Knock"), selected = 0, onSelect = {})
                    Spacer(Modifier.height(12.dp))
                    GraphPanel(graph, specs.take(3), format, onToggle = {}, onWindow = {}, onAxes = {}, modifier = Modifier.weight(1f), clock = { now })
                }
                BottomBar(AppTab.Graph) {}
            }
        }
    }

    @Test
    @Config(qualifiers = "fr-w891dp-h411dp-land-xxhdpi")
    fun graphLandscape() {
        SharedHistory.clear()
        histories().forEach { (id, h) -> for (i in 0 until h.size) SharedHistory.add(id, h.timeAt(i), h.valueAt(i).toDouble()) }
        val graph = Graphs.add(emptyList(), "Warm-up", specs.map { it.id }, id = "g").single().copy(windowSeconds = 300)
        val format = GaugeFormat(Locale.FRANCE)
        capture("graph_landscape_fr.png") {
            Column(Modifier.fillMaxSize().background(Cockpit.Background).padding(8.dp)) {
                GraphPanel(graph, specs, format, onToggle = {}, onWindow = {}, onAxes = {}, modifier = Modifier.fillMaxSize(), landscape = true, clock = { now })
            }
        }
    }

    @Test
    fun graphTab() = tab("graph_tab.png")

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun graphTabFrench() = tab("graph_tab_fr.png")
}
