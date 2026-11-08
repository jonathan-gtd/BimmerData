/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeRenderer
import io.github.jonathangtd.bimmerdata.gauges.GaugeSpec
import io.github.jonathangtd.bimmerdata.gauges.GaugeState
import io.github.jonathangtd.bimmerdata.gauges.GaugeTheme
import io.github.jonathangtd.bimmerdata.gauges.GridGeometry
import java.util.Locale

/** Texts of the car screen frame, already localized by the caller. */
data class CarChrome(
    val title: String,
    /** "page 1 / 3". */
    val pageLabel: String,
    /** "Simulation · 8,4 lect/s". */
    val statusText: String,
    val statusColor: Int,
    /** "REC 12:34", or null when not recording. */
    val recordText: String?,
    val showArrows: Boolean = true,
)

/**
 * Draws one Android Auto page (mockup "AutoA"): title and page number, previous / next buttons,
 * the card grid and a status line, into the area of the car surface the app may use (the host
 * draws its own rail beside it). All sizes scale with the area height (480 px reference).
 */
class CarPageRenderer(context: Context, locale: Locale) {
    private val baseTheme = GaugeTheme.car(context, 1f)
    val gauges = GaugeRenderer(baseTheme, GaugeFormat(locale))
    private var unit = 1f

    private val bgPaint = Paint().apply { color = baseTheme.background }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = baseTheme.fonts.numbers; fontFeatureSettings = "tnum" }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = baseTheme.fonts.regular; fontFeatureSettings = "tnum" }
    private val rect = RectF()
    private val chevron = Path()

    /** Card rectangles are valid after [layout] for the same size. */
    private val grid = RectF()

    fun layout(left: Float, top: Float, width: Float, height: Float) {
        val u = (height / REFERENCE_HEIGHT).coerceAtLeast(0.2f)
        if (u != unit) {
            unit = u
            gauges.theme = baseTheme.withUnit(u)
        }
        grid.set(
            left + 28f * u, top + (24f + 32f + 14f) * u,
            left + width - 28f * u, top + height - (18f + 20f + 14f) * u,
        )
    }

    /** Rectangle of a card of [page] after [layout]. */
    fun cardRect(page: Page, col: Int, row: Int, colSpan: Int, rowSpan: Int, out: RectF): RectF =
        GridGeometry.spanRect(
            grid.left, grid.top, grid.width(), grid.height(), page.columns, page.rows, 18f * unit,
            col, row, colSpan, rowSpan, out,
        )

    val gridArea: RectF get() = grid
    val cardGap: Float get() = 18f * unit

    /** Draws background, title, arrows and status line (no cards). */
    fun drawChrome(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, chrome: CarChrome) {
        layout(left, top, width, height)
        val u = unit
        canvas.drawRect(left, top, left + width, top + height, bgPaint)

        // Title + page number, baseline aligned.
        titlePaint.textSize = 26f * u
        titlePaint.color = baseTheme.text
        val baseline = top + 24f * u + 26f * u * 0.82f
        val x0 = left + 28f * u
        val arrowsW = if (chrome.showArrows) 124f * u else 0f
        val titleMax = width - 56f * u - arrowsW
        val title = GaugeState.ellipsize(chrome.title, titlePaint, titleMax * 0.7f)
        canvas.drawText(title, x0, baseline, titlePaint)
        textPaint.textSize = 16f * u
        textPaint.color = baseTheme.muted
        canvas.drawText(chrome.pageLabel, x0 + titlePaint.measureText(title) + 14f * u, baseline, textPaint)

        if (chrome.showArrows) {
            val pillRight = left + width - 20f * u
            // Kept above the card grid (grid top = 70 units).
            val pillTop = top + 12f * u
            rect.set(pillRight - 104f * u, pillTop, pillRight, pillTop + 52f * u)
            fill.color = 0xFF1C1F24.toInt()
            canvas.drawRoundRect(rect, 26f * u, 26f * u, fill)
            for (i in 0..1) {
                val cx = rect.left + 4f * u + 22f * u + i * 52f * u
                val cy = rect.centerY()
                fill.color = 0xFF2A2E35.toInt()
                canvas.drawCircle(cx, cy, 22f * u, fill)
                stroke.color = baseTheme.text
                stroke.strokeWidth = 2.2f * u
                val dir = if (i == 0) 1f else -1f
                chevron.rewind()
                chevron.moveTo(cx + 2.5f * u * dir, cy - 6f * u)
                chevron.lineTo(cx - 3.5f * u * dir, cy)
                chevron.lineTo(cx + 2.5f * u * dir, cy + 6f * u)
                canvas.drawPath(chevron, stroke)
            }
        }

        // Status line.
        val statusBaseline = top + height - 18f * u - 3f * u
        textPaint.textSize = 16f * u
        fill.color = chrome.statusColor
        canvas.drawCircle(x0 + 5f * u, statusBaseline - 5.5f * u, 5f * u, fill)
        textPaint.color = baseTheme.muted
        canvas.drawText(chrome.statusText, x0 + 18f * u, statusBaseline, textPaint)
        chrome.recordText?.let { rec ->
            val right = left + width - 28f * u
            textPaint.color = baseTheme.recordText
            val w = textPaint.measureText(rec)
            canvas.drawText(rec, right - w, statusBaseline, textPaint)
            fill.color = baseTheme.record
            canvas.drawCircle(right - w - 13f * u, statusBaseline - 5.5f * u, 5f * u, fill)
        }
    }

    /** Draws a whole page; returns true while needles are still moving. */
    fun draw(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        page: Page, specs: List<GaugeSpec>, states: List<GaugeState>, feed: GaugeFeed, frameNanos: Long, chrome: CarChrome,
    ): Boolean {
        drawChrome(canvas, left, top, width, height, chrome)
        var animating = false
        page.cards.forEachIndexed { i, card ->
            cardRect(page, card.col, card.row, card.colSpan, card.rowSpan, rect)
            if (gauges.draw(canvas, rect, specs[i], states[i], feed, frameNanos)) animating = true
        }
        return animating
    }

    companion object {
        const val REFERENCE_HEIGHT = 480f

        /** Width of the mockup content area next to the Android Auto rail (1280 − 88). */
        const val REFERENCE_WIDTH = 1192f
    }
}
