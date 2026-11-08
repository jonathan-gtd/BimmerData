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
import kotlin.math.sin

/** Trend cards with a short and a long time window, rendered to PNG for review (see ScreenshotTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TrendScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }
    private val id = "dme/0x4300"
    private val measurement = Measurement(id, "dme", "Coolant temperature", "°C", MeasurementSource.MeasurementTable(0x4300, "STAT_KUEHLMITTELTEMPERATUR_WERT"))

    /** 20 reads/s for 12 min: warm-up curve with a dip at −5 min and a spike at −20 s. */
    private class SyntheticFeed(override val nowMs: Long, id: String) : GaugeFeed {
        private val history = ValueHistory(retainMs = 15 * 60_000L)
        private val last: Sample

        init {
            val start = nowMs - 12 * 60_000L
            var t = start
            var v = 0.0
            while (t <= nowMs) {
                val ago = nowMs - t
                v = 40.0 + 50.0 * (1 - Math.exp(-(t - start) / 180_000.0)) + 1.5 * sin(t / 4_000.0) +
                    (if (ago in 295_000..305_000) -18.0 else 0.0) + (if (ago in 19_000..21_000) 9.0 else 0.0)
                history.add(t, v)
                t += 50
            }
            last = Sample(v, null, nowMs)
        }

        override fun sample(id: String): Sample? = last
        override fun history(id: String): ValueHistory = history
    }

    private fun render(name: String, locale: Locale, windowSeconds: Int, widthDp: Int, heightDp: Int) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = 3f
        val bitmap = Bitmap.createBitmap((widthDp * density).toInt(), (heightDp * density).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val theme = GaugeTheme.phone(context, density)
        canvas.drawColor(theme.background)
        val renderer = GaugeRenderer(theme, GaugeFormat(locale))
        val card = Card(type = GaugeType.Trend, measurementIds = listOf(id), windowSeconds = windowSeconds, min = 0.0, max = 120.0)
        val spec = CardSpecs.build(card, mapOf(id to measurement), renderer.format)
        val feed = SyntheticFeed(System.currentTimeMillis(), id)
        val pad = 8f * density
        renderer.draw(canvas, RectF(pad, pad, bitmap.width - pad, bitmap.height - pad), spec, GaugeState(), feed, System.nanoTime())
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    @Test
    fun trend30s() = render("trend_30s.png", Locale.US, 30, 395, 190)

    @Test
    fun trend10min() = render("trend_10min.png", Locale.US, 600, 395, 190)

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun trend30sFrench() = render("trend_30s_fr.png", Locale.FRANCE, 30, 395, 190)

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
    fun trend10minFrench() = render("trend_10min_fr.png", Locale.FRANCE, 600, 395, 190)

    @Test
    fun trendSmall() = render("trend_small_5min.png", Locale.US, 300, 190, 120)
}
