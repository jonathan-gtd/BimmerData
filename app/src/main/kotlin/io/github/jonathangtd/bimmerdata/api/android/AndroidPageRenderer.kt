/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.api.RenderRequest
import io.github.jonathangtd.bimmerdata.dashboard.CardSpecs
import io.github.jonathangtd.bimmerdata.dashboard.Target
import io.github.jonathangtd.bimmerdata.dashboard.car.CarScreen
import io.github.jonathangtd.bimmerdata.dashboard.car.StatusLine
import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.GaugeFonts
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeRenderer
import io.github.jonathangtd.bimmerdata.gauges.GaugeState
import io.github.jonathangtd.bimmerdata.gauges.GaugeTheme
import io.github.jonathangtd.bimmerdata.gauges.GridGeometry
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import io.github.jonathangtd.bimmerdata.live.AcquisitionState
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.Sample
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Renders dashboard pages to PNG with the app's own Canvas renderers: Android Auto pages through
 * [CarScreen.render] (1280×480 head unit with the rail stand-in), phone pages with the phone
 * [GaugeTheme] on a portrait canvas. Safe off the main thread (one renderer per call).
 */
class AndroidPageRenderer(private val context: Context) {
    fun render(r: RenderRequest): ByteArray {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val feed = SnapshotFeed(r)
        val status = r.status.acquisition.let { a ->
            AcquisitionStatus(runCatching { AcquisitionState.valueOf(a.state) }.getOrDefault(AcquisitionState.Stopped), a.readsPerSecond, a.errorCount, a.message)
        }
        val bitmap = if (r.target == Target.Car) {
            CarScreen.render(context, locale, r.page, r.pageIndex, r.pageCount, r.index, feed, status, r.recording, r.width, r.height)
        } else {
            renderPhone(r, locale, feed, status)
        }
        return try {
            ByteArrayOutputStream(64 * 1024).also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPhone(r: RenderRequest, locale: Locale, feed: GaugeFeed, status: AcquisitionStatus): Bitmap {
        val bitmap = createBitmap(r.width, r.height)
        val canvas = Canvas(bitmap)
        // Same density as a ~411 dp wide phone, whatever the bitmap width.
        val dp = r.width / 411f
        val theme = GaugeTheme.phone(context, dp)
        val renderer = GaugeRenderer(theme, GaugeFormat(locale))
        canvas.drawColor(theme.background)

        val fonts = GaugeFonts.get(context)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.numbers; textSize = 24f * dp; color = theme.text; fontFeatureSettings = "tnum" }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.regular; textSize = 14f * dp; color = theme.muted; fontFeatureSettings = "tnum" }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = 16f * dp
        val baseline = 40f * dp
        val name = GaugeState.ellipsize(r.page.name, title, r.width * 0.6f)
        canvas.drawText(name, left, baseline, title)
        val pageLabel = context.getString(R.string.page_x_of_y, r.pageIndex + 1, r.pageCount)
        canvas.drawText(pageLabel, left + title.measureText(name) + 10f * dp, baseline, small)
        dot.color = StatusLine.color(status)
        val statusBaseline = baseline + 24f * dp
        canvas.drawCircle(left + 4f * dp, statusBaseline - 4.5f * dp, 4f * dp, dot)
        canvas.drawText(GaugeState.ellipsize(StatusLine.text(context, status, renderer.format), small, r.width - 3 * left), left + 14f * dp, statusBaseline, small)

        val top = statusBaseline + 16f * dp
        val gap = 12f * dp
        val rect = RectF()
        val now = System.nanoTime()
        for (card in r.page.cards) {
            GridGeometry.spanRect(left, top, r.width - 2 * left, r.height - top - left, r.page.columns, r.page.rows, gap,
                card.col, card.row, card.colSpan, card.rowSpan, rect)
            renderer.draw(canvas, rect, CardSpecs.build(card, r.index, renderer.format), GaugeState(), feed, now)
        }
        return bitmap
    }

    /** Fixed snapshot of samples plus the API's history, for one frame. */
    private class SnapshotFeed(private val r: RenderRequest) : GaugeFeed {
        private val histories = HashMap<String, ValueHistory>()
        override val nowMs: Long = System.currentTimeMillis()
        override fun sample(id: String): Sample? = r.samples[id]
        override fun history(id: String): ValueHistory? = histories.getOrPut(id) {
            ValueHistory().also { h -> r.history.points(id).forEach { p -> p.value?.let { h.add(p.timestampMs, it) } } }
        }.takeIf { it.size > 0 }
    }
}
