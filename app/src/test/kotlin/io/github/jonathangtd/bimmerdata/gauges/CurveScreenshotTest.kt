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
import kotlin.math.max

/** Curve cards (one and two values against an engine speed), phone and Android Auto themes, to PNG for review (see ScreenshotTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CurveScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }
    private val rpm = Measurement("ecu/0x4807", "ecu", "Régime", "rpm", MeasurementSource.MeasurementTable(0x4807, "STAT_RPM"))
    private val power = Measurement("ecu/power", "ecu", "Puissance", "ch", MeasurementSource.MeasurementTable(1, "X"))
    private val torque = Measurement("ecu/0x58D0", "ecu", "Couple", "Nm", MeasurementSource.MeasurementTable(0x58D0, "STAT_TQ"))

    /** A pull from 1500 to 6000 rpm over the last 12 s, 4 readings a second. */
    private class Feed : GaugeFeed {
        override val nowMs = System.currentTimeMillis()
        private val h = HashMap<String, ValueHistory>()

        init {
            val r = ValueHistory(); val p = ValueHistory(); val t = ValueHistory()
            for (i in 0..48) {
                val time = nowMs - (48 - i) * 250L
                val n = 1500.0 + i * 93.75
                val tq = 285.0 + max(0.0, n - 3500) * 0.015
                r.add(time, n); t.add(time + 20, tq); p.add(time + 40, tq * n / 9549.3 * 1.35962)
            }
            h["ecu/0x4807"] = r; h["ecu/power"] = p; h["ecu/0x58D0"] = t
        }

        override fun sample(id: String): Sample? = h[id]?.let { Sample(it.valueAt(it.size - 1).toDouble(), null, nowMs) }
        override fun history(id: String) = h[id]
    }

    private fun render(name: String, car: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = 3f
        val cw = 280f * density
        val ch = 150f * density
        val cards = listOf(listOf(rpm.id, power.id), listOf(rpm.id, power.id, torque.id))
        val bitmap = Bitmap.createBitmap((cw + 16 * density).toInt(), (ch * cards.size + 8 * density * (cards.size + 1)).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val theme = if (car) GaugeTheme.car(context, density) else GaugeTheme.phone(context, density)
        canvas.drawColor(theme.background)
        val renderer = GaugeRenderer(theme, GaugeFormat(Locale.FRANCE))
        val index = listOf(rpm, power, torque).associateBy { it.id }
        cards.forEachIndexed { i, ids ->
            val spec = CardSpecs.build(Card(type = GaugeType.Curve, measurementIds = ids, windowSeconds = 30), index, renderer.format)
            val y = 8 * density + i * (ch + 8 * density)
            renderer.draw(canvas, RectF(8 * density, y, 8 * density + cw, y + ch), spec, GaugeState(), Feed(), System.nanoTime())
        }
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    @Test
    fun phone() = render("curve_phone.png", car = false)

    @Test
    fun car() = render("curve_car.png", car = true)
}
