/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.CardSpecs
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.Sample
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/** Cards without alert, in orange and in red alert, phone and Android Auto themes, to PNG for review (see ScreenshotTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AlertScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }
    private val id = "ecu/0x1"
    private val m = Measurement(id, "ecu", "Oil temperature", "°C", MeasurementSource.MeasurementTable(1, "STAT_X"), min = 0.0, max = 150.0)
    private val rules = listOf(
        CardAlert(condition = AlertCondition.Below, threshold = 70.0, level = AlertLevel.Danger, sound = true),
        CardAlert(condition = AlertCondition.Above, threshold = 120.0),
    )

    private class Feed(private val v: Double) : GaugeFeed {
        override val nowMs = System.currentTimeMillis()
        private val h = ValueHistory(retainMs = 120_000).apply { for (i in 0..60) add(nowMs - (60 - i) * 1000L, v - 5 + i / 12.0) }
        override fun sample(id: String) = Sample(v, null, nowMs)
        override fun history(id: String) = h
    }

    private fun render(name: String, car: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = 3f
        val types = listOf(GaugeType.Dial, GaugeType.BigNumber, GaugeType.Trend)
        val values = listOf(90.0, 125.0, 55.0)
        val cw = 130f * density
        val ch = 130f * density
        val bitmap = Bitmap.createBitmap((cw * values.size + 8 * density * (values.size + 1)).toInt(), (ch * types.size + 8 * density * (types.size + 1)).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val theme = if (car) GaugeTheme.car(context, density) else GaugeTheme.phone(context, density)
        canvas.drawColor(theme.background)
        val renderer = GaugeRenderer(theme, GaugeFormat(Locale.FRANCE))
        types.forEachIndexed { r, t ->
            values.forEachIndexed { c, v ->
                val card = Card(type = t, measurementIds = listOf(id), title = "Huile", decimals = 0, alerts = rules, windowSeconds = 60)
                val spec = CardSpecs.build(card, mapOf(id to m), renderer.format)
                val x = 8 * density + c * (cw + 8 * density)
                val y = 8 * density + r * (ch + 8 * density)
                renderer.draw(canvas, RectF(x, y, x + cw, y + ch), spec, GaugeState(), Feed(v), System.nanoTime())
            }
        }
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    @Test
    fun phone() = render("alerts_phone.png", car = false)

    @Test
    fun car() = render("alerts_car.png", car = true)
}
