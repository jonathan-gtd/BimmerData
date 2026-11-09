/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withClip
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeTheme
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** One value of a graph as drawn: catalog label and unit, decimals and color. */
data class GraphSeriesSpec(
    val id: String,
    val label: String,
    /** Displayed unit ("°C", "tr/min"); empty if none. */
    val unit: String,
    val decimals: Int,
    /** ARGB. */
    val color: Int,
    /** Smallest vertical span shown (keeps sensor noise small), in the value's unit. */
    val minSpan: Double,
    val hidden: Boolean = false,
)

/** Texts drawn on the plot (localized by the caller). */
data class GraphTexts(
    /** Nothing read in the range shown. */
    val noData: String,
    /** Overlay with more units than axes: "+2 axes: see Bands" for the given count. */
    val moreAxes: (Int) -> String,
)

/** Everything one frame needs. [cursorMs] = time under the cursor line, null = no cursor. */
data class GraphFrame(
    val series: List<GraphSeriesSpec>,
    val axes: GraphAxes,
    val view: GraphView,
    val nowMs: Long,
    val cursorMs: Long? = null,
)

/**
 * Draws a graph on a native canvas: curves of several values on a common time axis, either
 * superimposed with one vertical axis per unit (left, then right) or in one band per value. The
 * scales follow what is visible. Allocation-light (buffers reused per value); one instance per
 * screen, used on one thread.
 */
class GraphRenderer(
    private val theme: GaugeTheme,
    val format: GaugeFormat,
    private val texts: GraphTexts,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val u = theme.unit
    private val decimators = HashMap<String, SeriesDecimator>()
    private val path = Path()
    private val near = FloatArray(2)

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.card }
    private val gridPaint = Paint().apply { color = theme.track; strokeWidth = max(1f, 0.8f * u) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * u; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.muted; textSize = 11f * u; typeface = theme.fonts.medium }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.text; textSize = 12f * u; typeface = theme.fonts.medium }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.text; textSize = 13f * u; typeface = theme.fonts.numbers }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (theme.text and 0x00FFFFFF) or 0x99000000.toInt(); strokeWidth = 1.2f * u }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.track }

    /** Time labels are not drawn between these x (the cursor time pill sits there). */
    private var blockedLeft = Float.NaN
    private var blockedRight = Float.NaN

    private val clockSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val clockMinutes = DateTimeFormatter.ofPattern("HH:mm")

    /** Plot area of the last frame (for gesture math): x from [plotLeft] to [plotRight]. */
    var plotLeft = 0f
        private set
    var plotRight = 0f
        private set

    fun draw(c: Canvas, r: RectF, frame: GraphFrame, history: (String) -> ValueHistory?) {
        c.drawRoundRect(r, theme.cornerRadius * u, theme.cornerRadius * u, cardPaint)
        val pad = 12f * u
        val inner = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val view = frame.view
        val from = view.start(frame.nowMs)
        val to = view.end(frame.nowMs)
        val visible = frame.series.filterNot { it.hidden }
        // Decimate first: the scales follow what is visible.
        val cols = inner.width().toInt().coerceAtLeast(1)
        var anyPoint = false
        for (s in visible) {
            val dec = decimators.getOrPut(s.id) { SeriesDecimator() }
            dec.begin(from, view.spanMs, cols)
            history(s.id)?.forEachPoint(from, dec, to)
            dec.end()
            if (!dec.visibleMin.isNaN()) anyPoint = true
        }
        if (frame.axes == GraphAxes.Bands && visible.isNotEmpty()) drawBands(c, inner, frame, visible, from, history)
        else drawOverlay(c, inner, frame, visible, from, history)
        if (!anyPoint) {
            tickPaint.textAlign = Paint.Align.CENTER
            c.drawText(texts.noData, (plotLeft + plotRight) / 2f, inner.centerY(), tickPaint)
            tickPaint.textAlign = Paint.Align.LEFT
        }
    }

    // ---------------------------------------------------------------- overlay

    private fun drawOverlay(c: Canvas, r: RectF, frame: GraphFrame, visible: List<GraphSeriesSpec>, from: Long, history: (String) -> ValueHistory?) {
        val headerH = 18f * u
        val timeH = 18f * u
        val top = r.top + headerH
        val bottom = r.bottom - timeH
        val groups = GraphMath.groups(visible.map { it.unit })
        val maxTicks = ((bottom - top) / (44f * u)).toInt().coerceIn(2, 7)
        val scales = groups.map { g -> scaleFor(g.members.map { visible[it] }, maxTicks) }
        val leftScale = scales.getOrNull(0)
        val rightScale = scales.getOrNull(1)
        val leftW = leftScale?.let { axisWidth(it) } ?: 0f
        val rightW = rightScale?.let { axisWidth(it) } ?: 0f
        val left = r.left + leftW
        val right = r.right - rightW
        plotLeft = left
        plotRight = right

        // horizontal grid from the left axis, then the time grid
        if (leftScale != null) {
            for (t in leftScale.ticks()) {
                val y = bottom - (bottom - top) * leftScale.fraction(t).toFloat()
                c.drawLine(left, y, right, y, gridPaint)
            }
        } else {
            c.drawLine(left, bottom, right, bottom, gridPaint)
        }
        drawTimeAxis(c, left, top, right, bottom, frame, from)

        // axes: ticks + unit header with the colors of its values
        if (leftScale != null) drawAxis(c, leftScale, left - 6f * u, top, bottom, Paint.Align.RIGHT)
        if (rightScale != null) drawAxis(c, rightScale, right + 6f * u, top, bottom, Paint.Align.LEFT)
        if (groups.isNotEmpty()) drawUnitHeader(c, groups[0], visible, r.left, r.top + 12f * u, Paint.Align.LEFT)
        if (groups.size > 1) drawUnitHeader(c, groups[1], visible, r.right, r.top + 12f * u, Paint.Align.RIGHT)
        if (groups.size > 2) {
            tickPaint.textAlign = Paint.Align.CENTER
            c.drawText(texts.moreAxes(groups.size - 2), (left + right) / 2f, r.top + 12f * u, tickPaint)
            tickPaint.textAlign = Paint.Align.LEFT
        }

        // curves, each group on its own scale
        c.withClip(left, top - 2f * u, right, bottom + 2f * u) {
            groups.forEachIndexed { gi, g ->
                for (i in g.members) drawCurve(c, visible[i], scales[gi], left, top, right, bottom)
            }
        }

        frame.cursorMs?.let { t ->
            val x = xOf(t, from, frame.view.spanMs, left, right) ?: return@let
            c.drawLine(x, top, x, bottom, cursorPaint)
            groups.forEachIndexed { gi, g ->
                for (i in g.members) drawCursorDot(c, visible[i], scales[gi], t, x, top, bottom, history)
            }
            drawCursorTime(c, t, x, top, left, right)
        }
    }

    // ---------------------------------------------------------------- bands

    private fun drawBands(c: Canvas, r: RectF, frame: GraphFrame, visible: List<GraphSeriesSpec>, from: Long, history: (String) -> ValueHistory?) {
        val timeH = 18f * u
        val bottom = r.bottom - timeH
        val n = visible.size
        val gap = 8f * u
        val laneH = ((bottom - r.top) - gap * (n - 1)) / n
        val labelH = 18f * u
        val maxTicks = if (laneH - labelH >= 70f * u) 3 else 2
        val scales = visible.map { scaleFor(listOf(it), maxTicks) }
        val axisW = scales.maxOf { axisWidth(it) }
        val left = r.left + axisW
        val right = r.right
        plotLeft = left
        plotRight = right
        blockedLeft = Float.NaN
        frame.cursorMs?.let { t -> xOf(t, from, frame.view.spanMs, left, right)?.let { x -> blockCursorPill(t, x, left, right) } }
        drawTimeAxis(c, left, r.top, right, bottom, frame, from)
        blockedLeft = Float.NaN
        visible.forEachIndexed { i, s ->
            val laneTop = r.top + i * (laneH + gap)
            val plotTop = laneTop + labelH
            val plotBottom = laneTop + laneH
            val scale = scales[i]
            for (t in scale.ticks()) {
                val y = plotBottom - (plotBottom - plotTop) * scale.fraction(t).toFloat()
                c.drawLine(left, y, right, y, gridPaint)
            }
            drawAxis(c, scale, left - 6f * u, plotTop, plotBottom, Paint.Align.RIGHT)
            // swatch + name + value (text in text colors, the swatch carries the identity)
            val baseline = laneTop + 12f * u
            dotPaint.color = s.color
            c.drawCircle(left + 4f * u, baseline - 4f * u, 4f * u, dotPaint)
            val value = laneValue(s, frame.cursorMs, history)
            val valueW = valuePaint.measureText(value)
            val labelMax = right - left - 16f * u - valueW - 10f * u
            c.drawText(ellipsize(s.label, labelPaint, labelMax), left + 14f * u, baseline, labelPaint)
            valuePaint.textAlign = Paint.Align.RIGHT
            c.drawText(value, right, baseline, valuePaint)
            valuePaint.textAlign = Paint.Align.LEFT
            c.withClip(left, plotTop - 2f * u, right, plotBottom + 2f * u) { drawCurve(c, s, scale, left, plotTop, right, plotBottom) }
            frame.cursorMs?.let { t ->
                val x = xOf(t, from, frame.view.spanMs, left, right) ?: return@let
                c.drawLine(x, plotTop, x, plotBottom, cursorPaint)
                drawCursorDot(c, s, scale, t, x, plotTop, plotBottom, history)
            }
        }
        frame.cursorMs?.let { t ->
            val x = xOf(t, from, frame.view.spanMs, left, right) ?: return@let
            drawCursorTime(c, t, x, bottom + 2f * u, left, right, below = true)
        }
    }

    private fun laneValue(s: GraphSeriesSpec, cursorMs: Long?, history: (String) -> ValueHistory?): String {
        val h = history(s.id) ?: return GaugeFormat.NO_DATA
        val text = if (cursorMs != null) {
            if (!h.valueNear(cursorMs, GraphMath.GAP_MS, near)) return GaugeFormat.NO_DATA
            if (near[0] == near[1]) format.format(near[0].toDouble(), s.decimals)
            else format.format(near[0].toDouble(), s.decimals) + "…" + format.format(near[1].toDouble(), s.decimals)
        } else {
            if (h.isEmpty) return GaugeFormat.NO_DATA
            format.format(h.valueAt(h.size - 1).toDouble(), s.decimals)
        }
        return format.withUnit(text, s.unit)
    }

    // ---------------------------------------------------------------- pieces

    private fun scaleFor(members: List<GraphSeriesSpec>, maxTicks: Int): NiceScale {
        var lo = Float.NaN
        var hi = Float.NaN
        for (s in members) {
            val d = decimators[s.id] ?: continue
            var a = d.visibleMin
            var b = d.visibleMax
            if (a.isNaN()) {
                // nothing inside the range: the points around it still give the level
                for (k in 0 until d.count) {
                    val v = d.vs[k]
                    if (d.xs[k].isNaN()) continue
                    if (a.isNaN() || v < a) a = v
                    if (b.isNaN() || v > b) b = v
                }
            }
            if (a.isNaN()) continue
            if (lo.isNaN() || a < lo) lo = a
            if (hi.isNaN() || b > hi) hi = b
        }
        val minSpan = members.maxOf { it.minSpan }
        if (lo.isNaN()) return NiceScale.of(0.0, minSpan.coerceAtLeast(1.0), maxTicks)
        return NiceScale.of(lo.toDouble(), hi.toDouble(), maxTicks, minSpan)
    }

    private fun axisWidth(scale: NiceScale): Float {
        var w = 0f
        for (t in scale.ticks()) w = max(w, tickPaint.measureText(format.format(t, scale.decimals)))
        return w + 10f * u
    }

    private fun drawAxis(c: Canvas, scale: NiceScale, x: Float, top: Float, bottom: Float, align: Paint.Align) {
        tickPaint.textAlign = align
        val half = tickPaint.textSize * 0.35f
        for (t in scale.ticks()) {
            val y = bottom - (bottom - top) * scale.fraction(t).toFloat()
            c.drawText(format.format(t, scale.decimals), x, (y + half).coerceIn(top + half * 2, bottom + half), tickPaint)
        }
        tickPaint.textAlign = Paint.Align.LEFT
    }

    /** "●● °C" at the top of an axis: the colors of the values on it, then the unit. */
    private fun drawUnitHeader(c: Canvas, g: AxisGroup, visible: List<GraphSeriesSpec>, x: Float, baseline: Float, align: Paint.Align) {
        val unit = g.unit.ifEmpty { visible[g.members.first()].label }
        val dots = g.members.take(4)
        val dotStep = 10f * u
        val unitText = ellipsize(unit, tickPaint, 120f * u)
        val textW = tickPaint.measureText(unitText)
        val total = dots.size * dotStep + 2f * u + textW
        var cx = if (align == Paint.Align.LEFT) x else x - total
        for (i in dots) {
            dotPaint.color = visible[i].color
            c.drawCircle(cx + 4f * u, baseline - 4f * u, 4f * u, dotPaint)
            cx += dotStep
        }
        c.drawText(unitText, cx + 2f * u, baseline, tickPaint)
    }

    private fun drawCurve(c: Canvas, s: GraphSeriesSpec, scale: NiceScale, left: Float, top: Float, right: Float, bottom: Float) {
        val d = decimators[s.id] ?: return
        if (d.count == 0) return
        val w = right - left
        val h = bottom - top
        path.rewind()
        var pen = false
        var lastX = 0f
        var lastY = 0f
        var segmentPoints = 0
        for (k in 0 until d.count) {
            val xf = d.xs[k]
            if (xf.isNaN()) {
                if (segmentPoints == 1) path.lineTo(lastX + 0.01f, lastY) // a lone point still shows
                pen = false
                segmentPoints = 0
                continue
            }
            val x = left + w * xf
            val y = bottom - h * scale.fraction(d.vs[k].toDouble()).toFloat()
            if (!pen) {
                path.moveTo(x, y)
                pen = true
            } else {
                path.lineTo(x, y)
            }
            lastX = x
            lastY = y
            segmentPoints++
        }
        if (segmentPoints == 1) path.lineTo(lastX + 0.01f, lastY)
        linePaint.color = s.color
        c.drawPath(path, linePaint)
    }

    private fun drawCursorDot(
        c: Canvas, s: GraphSeriesSpec, scale: NiceScale, t: Long, x: Float, top: Float, bottom: Float, history: (String) -> ValueHistory?,
    ) {
        val h = history(s.id) ?: return
        if (!h.valueNear(t, GraphMath.GAP_MS, near)) return
        val v = (near[0] + near[1]) / 2.0
        val y = (bottom - (bottom - top) * scale.fraction(v).toFloat()).coerceIn(top, bottom)
        dotPaint.color = theme.card
        c.drawCircle(x, y, 6f * u, dotPaint)
        dotPaint.color = s.color
        c.drawCircle(x, y, 4f * u, dotPaint)
    }

    private fun blockCursorPill(t: Long, x: Float, left: Float, right: Float) {
        val w = labelPaint.measureText(clockSeconds.format(Instant.ofEpochMilli(t).atZone(zone))) + 12f * u
        blockedLeft = (x - w / 2f).coerceIn(left, max(left, right - w)) - 4f * u
        blockedRight = blockedLeft + w + 8f * u
    }

    private fun drawCursorTime(c: Canvas, t: Long, x: Float, top: Float, left: Float, right: Float, below: Boolean = false) {
        val text = clockSeconds.format(Instant.ofEpochMilli(t).atZone(zone))
        val w = labelPaint.measureText(text) + 12f * u
        val h = 18f * u
        val l = (x - w / 2f).coerceIn(left, max(left, right - w))
        val tp = if (below) top else top - h - 2f * u
        c.drawRoundRect(l, tp, l + w, tp + h, 6f * u, 6f * u, pillPaint)
        c.drawText(text, l + 6f * u, tp + h - 5f * u, labelPaint)
    }

    /**
     * Vertical lines at "nice" steps; labels relative to now while live ("−30 s"), clock times
     * once paused ("14:32:10").
     */
    private fun drawTimeAxis(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, frame: GraphFrame, from: Long) {
        val w = right - left
        val span = frame.view.spanMs
        val maxLines = (w / (70f * u)).toInt().coerceIn(1, 6)
        val step = TrendWindow.gridStepMs(span, maxLines)
        val labelY = bottom + 14f * u
        c.drawLine(left, bottom, right, bottom, gridPaint)
        if (frame.view.live) {
            var ago = step
            while (ago < span) {
                val x = right - w * (ago.toFloat() / span)
                c.drawLine(x, top, x, bottom, gridPaint)
                tickPaint.textAlign = Paint.Align.CENTER
                if (x - left > 24f * u) c.drawText(TrendWindow.tickLabel(ago), x, labelY, tickPaint)
                ago += step
            }
            tickPaint.textAlign = Paint.Align.LEFT
            return
        }
        val end = from + span
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(end)).totalSeconds * 1000L
        var t = Math.floorDiv(from + offset, step) * step - offset
        if (t < from) t += step
        val fmt = if (step >= 60_000L) clockMinutes else clockSeconds
        tickPaint.textAlign = Paint.Align.CENTER
        while (t <= end) {
            val x = left + w * ((t - from).toFloat() / span)
            c.drawLine(x, top, x, bottom, gridPaint)
            val label = fmt.format(Instant.ofEpochMilli(t).atZone(zone))
            val half = tickPaint.measureText(label) / 2f
            val clear = blockedLeft.isNaN() || x + half < blockedLeft || x - half > blockedRight
            if (clear && x - half >= left - 4f * u && x + half <= right + 4f * u) c.drawText(label, x, labelY, tickPaint)
            t += step
        }
        tickPaint.textAlign = Paint.Align.LEFT
    }

    private fun xOf(t: Long, from: Long, span: Long, left: Float, right: Float): Float? {
        val f = (t - from).toFloat() / span
        if (f < 0f || f > 1f) return null
        return left + (right - left) * f
    }

    private fun ellipsize(text: String, paint: Paint, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (paint.measureText(text) <= maxW) return text
        var n = text.length
        while (n > 1 && paint.measureText(text, 0, n) + paint.measureText("…") > maxW) n--
        return text.take(n) + "…"
    }

    /** Time at screen x (px) on the last frame's plot, for the cursor. */
    fun timeAt(x: Float, view: GraphView, nowMs: Long): Long {
        val w = (plotRight - plotLeft).coerceAtLeast(1f)
        val f = ((x - plotLeft) / w).coerceIn(0f, 1f)
        return view.start(nowMs) + (view.spanMs * f).toLong()
    }

    /** Fraction of the plot width under x (0..1), for zoom anchors. */
    fun fractionAt(x: Float): Float {
        val w = (plotRight - plotLeft).coerceAtLeast(1f)
        return ((x - plotLeft) / w).coerceIn(0f, 1f)
    }

    /** Plot width in px (scrolling converts finger motion with it). */
    val plotWidth: Float get() = (plotRight - plotLeft).coerceAtLeast(1f)

    companion object {
        /** Smallest span shown for a value: 4 % of its usual range. */
        fun minSpan(min: Double?, max: Double?, unit: String): Double {
            val (a, b) = if (min != null && max != null && max > min) min to max else GaugeFormat.defaultRange(unit)
            return ((b - a) * 0.04).coerceAtLeast(1e-3)
        }
    }
}
