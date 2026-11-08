/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.CardSpecs
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.GaugeFonts
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeState
import io.github.jonathangtd.bimmerdata.live.AcquisitionState
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.RecordingState
import java.util.Locale

/** Connection line shared by the phone header and the car status line. */
object StatusLine {
    const val ACCENT = 0xFF5CC8FF.toInt()
    const val WARNING = 0xFFFFB547.toInt()
    const val DANGER = 0xFFFF5A5F.toInt()
    const val MUTED = 0xFF8E97A3.toInt()

    fun text(context: Context, status: AcquisitionStatus, format: GaugeFormat): String = when (status.state) {
        AcquisitionState.Running -> context.getString(
            R.string.status_running,
            status.message ?: context.getString(R.string.status_live),
            format.format(status.cyclesPerSecond, 1),
        )
        AcquisitionState.Connecting -> context.getString(R.string.status_connecting)
        AcquisitionState.Stopped -> context.getString(R.string.status_stopped)
        AcquisitionState.Error -> status.message ?: context.getString(R.string.status_error)
    }

    fun color(status: AcquisitionStatus): Int = when (status.state) {
        AcquisitionState.Running -> ACCENT
        AcquisitionState.Connecting -> WARNING
        AcquisitionState.Stopped -> MUTED
        AcquisitionState.Error -> DANGER
    }

    /** "12:34" or "1:02:03". */
    fun elapsed(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }
}

object CarScreen {
    fun chrome(
        context: Context, page: Page, pageIndex: Int, pageCount: Int,
        status: AcquisitionStatus, rec: RecordingState, nowMs: Long, format: GaugeFormat,
    ) = CarChrome(
        title = page.name,
        pageLabel = context.getString(R.string.page_x_of_y, pageIndex + 1, pageCount),
        statusText = StatusLine.text(context, status, format),
        statusColor = StatusLine.color(status),
        recordText = if (rec.recording) context.getString(R.string.rec_short) + " " + StatusLine.elapsed(nowMs - (rec.startedAtMs ?: nowMs)) else null,
        showArrows = pageCount > 1,
    )

    /**
     * Renders a car page as Android Auto would show it on a 1280×480 head unit, including a
     * stand-in for the system rail that the host draws itself (preview and debug only).
     */
    fun render(
        context: Context, locale: Locale, page: Page, pageIndex: Int, pageCount: Int,
        index: Map<String, Measurement>, feed: GaugeFeed, status: AcquisitionStatus, rec: RecordingState,
        width: Int = 1280, height: Int = 480, frameNanos: Long = System.nanoTime(),
    ): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val renderer = CarPageRenderer(context, locale)
        drawWithRail(canvas, width.toFloat(), height.toFloat(), renderer, context, page, pageIndex, pageCount, index, feed, status, rec, frameNanos, null)
        return bitmap
    }

    /** Draws rail + page into a [width]×[height] canvas; [states] lets a live preview keep needle state. */
    fun drawWithRail(
        canvas: Canvas, width: Float, height: Float, renderer: CarPageRenderer, context: Context,
        page: Page, pageIndex: Int, pageCount: Int, index: Map<String, Measurement>, feed: GaugeFeed,
        status: AcquisitionStatus, rec: RecordingState, frameNanos: Long, states: MutableMap<String, GaugeState>?,
    ): Boolean {
        val u = height / CarPageRenderer.REFERENCE_HEIGHT
        val rail = 88f * u
        drawRail(canvas, rail, height, u, context)
        val specs = page.cards.map { CardSpecs.build(it, index, renderer.gauges.format) }
        val st = page.cards.map { states?.getOrPut(it.id) { GaugeState() } ?: GaugeState() }
        val chrome = chrome(context, page, pageIndex, pageCount, status, rec, feed.nowMs, renderer.gauges.format)
        return renderer.draw(canvas, rail, 0f, width - rail, height, page, specs, st, feed, frameNanos, chrome)
    }

    private fun drawRail(canvas: Canvas, w: Float, h: Float, u: Float, context: Context) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF16181B.toInt()
        canvas.drawRect(0f, 0f, w, h, p)
        p.color = 0xFFB9C0C9.toInt()
        p.textSize = 18f * u
        p.typeface = GaugeFonts.get(context).semiBold
        p.textAlign = Paint.Align.CENTER
        canvas.drawText("18:42", w / 2f, 20f * u + 18f * u * 0.8f, p)
        p.color = 0xFF2A2E33.toInt()
        for (i in -1..1) canvas.drawCircle(w / 2f, h / 2f + i * 58f * u, 20f * u, p)
        val r = RectF(w / 2f - 18f * u, h - 56f * u, w / 2f + 18f * u, h - 20f * u)
        canvas.drawRoundRect(r, 10f * u, 10f * u, p)
    }
}
