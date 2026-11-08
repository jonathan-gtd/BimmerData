/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeRenderer
import io.github.jonathangtd.bimmerdata.gauges.GaugeSpec
import io.github.jonathangtd.bimmerdata.gauges.GaugeState
import io.github.jonathangtd.bimmerdata.gauges.GaugeTheme
import io.github.jonathangtd.bimmerdata.gauges.GridGeometry
import java.util.Locale
import kotlin.math.min

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
    /** Shown in the card area when the page has no card ("add them on the phone"); null = nothing. */
    val emptyText: String? = null,
)

/**
 * Draws one Android Auto page (mockup "AutoA"): title and page number, previous / next buttons,
 * the card grid and a status line, into the area of the car surface the app may use (the host
 * draws its own rail beside it). All sizes scale with the area height (480 px reference).
 */
class CarPageRenderer(context: Context, locale: Locale, night: Boolean = false) {
    private val baseTheme = GaugeTheme.car(context, 1f, night)
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

    private fun setUnit(u: Float) {
        if (u != unit) {
            unit = u
            gauges.theme = baseTheme.withUnit(u)
        }
    }

    /** Background color of the car screen. */
    val background: Int get() = baseTheme.background

    fun layout(left: Float, top: Float, width: Float, height: Float) {
        val u = (height / REFERENCE_HEIGHT).coerceAtLeast(0.2f)
        setUnit(u)
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

    /**
     * Android Auto layout: the header line (title, page, status) in [header], the cards in [grid].
     * On a full-screen Android Auto surface the host keeps a band at the top for its action strip
     * (right side, hidden after a few seconds): [header] is that band and [reserveRight] (mockup
     * pixels) keeps the text clear of the strip. Sizes scale with [surfaceHeight] (480 px
     * reference). Returns true while needles are still moving.
     */
    fun drawHosted(
        canvas: Canvas, header: RectF, grid: RectF, surfaceHeight: Float, reserveRight: Float,
        page: Page?, specs: List<GaugeSpec>, states: List<GaugeState>, feed: GaugeFeed, frameNanos: Long, chrome: CarChrome,
    ): Boolean {
        setUnit((surfaceHeight / REFERENCE_HEIGHT).coerceAtLeast(0.2f))
        this.grid.set(grid)
        drawHeaderLine(canvas, header, chrome, reserveRight)
        if (page == null) return false
        if (page.cards.isEmpty()) drawEmpty(canvas, grid, chrome.emptyText)
        var animating = false
        page.cards.forEachIndexed { i, card ->
            cardRect(page, card.col, card.row, card.colSpan, card.rowSpan, rect)
            if (gauges.draw(canvas, rect, specs[i], states[i], feed, frameNanos)) animating = true
        }
        return animating
    }

    /** "Engine  1 / 3   ● Simulation · 8,4 reads/s   ● REC 01:23" on one line, left part of [band]. */
    private fun drawHeaderLine(canvas: Canvas, band: RectF, chrome: CarChrome, reserveRight: Float) {
        val u = unit
        // Keep clear of the host's action strip on the right of the band.
        val right = if (reserveRight <= 0f) band.right - 6f * u
        else min(band.left + band.width() * HEADER_WIDTH, band.right - reserveRight * u)
        val titleSize = 30f * u
        val baseline = band.centerY() + titleSize * GaugeRenderer.CAP / 2f
        var x = band.left + 6f * u
        titlePaint.textSize = titleSize
        titlePaint.color = baseTheme.text
        val title = GaugeState.ellipsize(chrome.title, titlePaint, (right - x) * 0.45f)
        canvas.drawText(title, x, baseline, titlePaint)
        x += titlePaint.measureText(title) + 14f * u
        textPaint.textSize = 19f * u
        textPaint.color = baseTheme.muted
        if (chrome.pageLabel.isNotEmpty()) {
            canvas.drawText(chrome.pageLabel, x, baseline, textPaint)
            x += textPaint.measureText(chrome.pageLabel) + 30f * u
        }
        val dotY = baseline - 19f * u * GaugeRenderer.CAP / 2f
        fill.color = chrome.statusColor
        canvas.drawCircle(x + 5f * u, dotY, 5f * u, fill)
        x += 18f * u
        val rec = chrome.recordText
        val recW = if (rec != null) textPaint.measureText(rec) + 48f * u else 0f
        val status = GaugeState.ellipsize(chrome.statusText, textPaint, right - x - recW)
        canvas.drawText(status, x, baseline, textPaint)
        x += textPaint.measureText(status)
        if (rec != null) {
            x += 30f * u
            fill.color = baseTheme.record
            canvas.drawCircle(x + 5f * u, dotY, 5f * u, fill)
            textPaint.color = baseTheme.recordText
            canvas.drawText(rec, x + 18f * u, baseline, textPaint)
        }
    }

    /** Draws a whole page; returns true while needles are still moving. */
    fun draw(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        page: Page, specs: List<GaugeSpec>, states: List<GaugeState>, feed: GaugeFeed, frameNanos: Long, chrome: CarChrome,
    ): Boolean {
        drawChrome(canvas, left, top, width, height, chrome)
        if (page.cards.isEmpty()) drawEmpty(canvas, grid, chrome.emptyText)
        var animating = false
        page.cards.forEachIndexed { i, card ->
            cardRect(page, card.col, card.row, card.colSpan, card.rowSpan, rect)
            if (gauges.draw(canvas, rect, specs[i], states[i], feed, frameNanos)) animating = true
        }
        return animating
    }

    private val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = baseTheme.fonts.regular }
    private var emptyLayout: StaticLayout? = null
    private var emptyKey: Triple<String, Int, Float>? = null

    /** Empty page: a dashed frame over the card area with [text] centered in it. */
    private fun drawEmpty(canvas: Canvas, area: RectF, text: String?) {
        if (text == null || area.width() <= 0f || area.height() <= 0f) return
        val u = unit
        stroke.color = baseTheme.muted
        stroke.alpha = 90
        stroke.strokeWidth = 2f * u
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * u, 8f * u), 0f)
        rect.set(area)
        rect.inset(stroke.strokeWidth, stroke.strokeWidth)
        canvas.drawRoundRect(rect, 26f * u, 26f * u, stroke)
        stroke.pathEffect = null
        stroke.alpha = 255
        emptyPaint.textSize = 24f * u
        emptyPaint.color = baseTheme.muted
        val width = (area.width() * 0.7f).toInt().coerceAtLeast(1)
        val key = Triple(text, width, emptyPaint.textSize)
        val layout = emptyLayout.takeIf { emptyKey == key } ?: StaticLayout.Builder.obtain(text, 0, text.length, emptyPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build().also {
                emptyLayout = it
                emptyKey = key
            }
        canvas.withTranslation(area.centerX() - width / 2f, area.centerY() - layout.height / 2f) { layout.draw(this) }
    }

    companion object {
        const val REFERENCE_HEIGHT = 480f

        /** Part of the action strip band the header line may use (the strip takes up to ~35 %). */
        const val HEADER_WIDTH = 0.6f

        /** Widest Android Auto action strip we show (previous, next, "Connect"), in mockup pixels. */
        const val ACTION_STRIP_WIDTH = 370f

        /** Width of the mockup content area next to the Android Auto rail (1280 − 88). */
        const val REFERENCE_WIDTH = 1192f
    }
}
