/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws dashboard cards with plain [android.graphics.Canvas], so the same code renders the phone
 * dashboard (through a Compose wrapper) and the Android Auto surface. Sizes are derived from the
 * card rectangle (text auto-fits), [GaugeTheme.unit] only sets readable minimums.
 *
 * Not thread safe: use one renderer per drawing thread.
 */
class GaugeRenderer(theme: GaugeTheme, val format: GaugeFormat) {
    var theme: GaugeTheme = theme
        set(value) {
            field = value
            applyTheme()
        }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val mutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }

    private val rr = RectF()
    private val arcRect = RectF()
    private var areaShaderTop = Float.NaN
    private var areaShaderBottom = Float.NaN
    private var areaShaderColor = 0

    init {
        applyTheme()
    }

    private fun applyTheme() {
        val t = theme
        cardPaint.color = t.card
        trackPaint.color = t.track
        numberPaint.typeface = t.fonts.numbers
        labelPaint.typeface = t.fonts.semiBold
        mutedPaint.typeface = t.fonts.regular
        areaShaderTop = Float.NaN
    }

    /**
     * Draws [spec] into [rect] (card background included). Returns true while the needle is still
     * moving, so the host knows it should keep requesting frames.
     */
    fun draw(canvas: Canvas, rect: RectF, spec: GaugeSpec, state: GaugeState, feed: GaugeFeed, frameNanos: Long): Boolean {
        state.sync(spec, feed, frameNanos)
        val radius = min(theme.cornerRadius * theme.unit, min(rect.width(), rect.height()) * 0.25f)
        canvas.drawRoundRect(rect, radius, radius, cardPaint)
        if (rect.width() < 8f || rect.height() < 8f) return state.animating
        when (spec.type) {
            GaugeType.Dial -> drawDial(canvas, rect, spec, state)
            GaugeType.BipolarBar -> if (theme.car || rect.height() > rect.width() * 0.42f) {
                drawBipolarStacked(canvas, rect, spec, state)
            } else {
                drawBipolarInline(canvas, rect, spec, state)
            }
            GaugeType.BigNumber -> drawBigNumber(canvas, rect, spec, state, feed)
            GaugeType.Trend -> drawTrend(canvas, rect, spec, state, feed)
            GaugeType.MultiValue -> drawMultiValue(canvas, rect, spec, state)
            GaugeType.Status -> drawStatus(canvas, rect, spec, state)
        }
        return state.animating
    }

    // ---------------------------------------------------------------- Dial

    private fun drawDial(c: Canvas, r: RectF, spec: GaugeSpec, st: GaugeState) {
        val u = theme.unit
        val car = theme.car
        val w = r.width()
        val h = r.height()
        val pad = (min(w, h) * 0.07f).coerceIn(6f * u, 20f * u)
        val showRange = !car
        val labelK = if (car) 0.105f else 0.115f
        val rangeK = 0.09f
        // Total block height relative to the dial size d: dial + label (+ range line).
        val blockK = 1.04f + labelK * 0.78f + (if (showRange) rangeK * 1.45f else 0f) + 0.03f
        val d = max(0f, min(w - 2 * pad, (h - 2 * pad) / blockK))
        val cx = r.centerX()
        val top = r.top + (h - d * blockK) / 2f
        val cy = top + d / 2f
        val radius = d * 0.40f
        val stroke = d * 0.075f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        trackPaint.strokeWidth = stroke
        c.drawArc(arcRect, 135f, 270f, false, trackPaint)

        val target = st.target(0)
        val v = st.smoothed(0)
        val hasData = st.hasData(0)
        if (!v.isNaN() && spec.max > spec.min) {
            val f = ((v - spec.min) / (spec.max - spec.min)).coerceIn(0.0, 1.0).toFloat()
            if (f > 0.001f) {
                arcPaint.strokeWidth = stroke
                arcPaint.color = if (spec.isWarning(target)) theme.warning else theme.accent
                c.drawArc(arcRect, 135f, 270f * f, false, arcPaint)
            }
        }

        // Value and unit in the middle.
        val channel = spec.channels.firstOrNull()
        val text = st.valueText(0, format, channel?.decimals ?: 0)
        val inner = (radius - stroke) * 2f * 0.92f
        numberPaint.color = if (hasData) theme.text else theme.muted
        val numSize = fit(numberPaint, text, inner, d * 0.29f, 8f * u)
        val unitText = channel?.unit.orEmpty()
        mutedPaint.color = theme.muted
        mutedPaint.textSize = max(d * 0.10f, 9f * u)
        val capH = numSize * CAP
        val gap = d * 0.035f
        val unitH = if (unitText.isEmpty()) 0f else mutedPaint.textSize * CAP + gap
        val valueBaseline = cy + d * 0.03f - (capH + unitH) / 2f + capH
        numberPaint.textAlign = Paint.Align.CENTER
        c.drawText(text, cx, valueBaseline, numberPaint)
        if (unitText.isNotEmpty()) {
            mutedPaint.textAlign = Paint.Align.CENTER
            c.drawText(st.ellipsized(0, unitText, mutedPaint, inner), cx, valueBaseline + unitH, mutedPaint)
        }

        // Label and range below.
        labelPaint.color = if (hasData) theme.text else theme.muted
        labelPaint.textSize = max(d * labelK, 11f * u)
        labelPaint.textAlign = Paint.Align.CENTER
        val labelBaseline = top + d * 1.04f + labelPaint.textSize * 0.78f
        c.drawText(st.ellipsized(1, spec.title, labelPaint, w - 2 * pad), cx, labelBaseline, labelPaint)
        if (showRange) {
            mutedPaint.textSize = max(d * rangeK, 10f * u)
            val range = spec.caption ?: rangeText(spec)
            c.drawText(st.ellipsized(2, range, mutedPaint, w - 2 * pad), cx, labelBaseline + mutedPaint.textSize * 1.45f, mutedPaint)
        }
        mutedPaint.textAlign = Paint.Align.LEFT
        labelPaint.textAlign = Paint.Align.LEFT
        numberPaint.textAlign = Paint.Align.LEFT
    }

    private fun rangeText(spec: GaugeSpec): String {
        val dec = if (spec.min % 1.0 == 0.0 && spec.max % 1.0 == 0.0) 0 else 1
        val unit = spec.channels.firstOrNull()?.unit.orEmpty()
        return format.withUnit(format.format(spec.min, dec) + " · " + format.format(spec.max, dec), unit)
    }

    // ---------------------------------------------------------------- Bipolar bars

    private fun maxAbs(spec: GaugeSpec): Double = max(abs(spec.min), abs(spec.max)).takeIf { it > 0 } ?: 25.0

    /** Phone mockup layout: title row, then "label | bar | value" rows. */
    private fun drawBipolarInline(c: Canvas, r: RectF, spec: GaugeSpec, st: GaugeState) {
        val u = theme.unit
        val n = spec.channels.size.coerceAtLeast(1)
        val designH = 14f + 18f + 10f + n * 22f + (n - 1) * 10f + 14f
        val s = (min(r.height() / (designH * u), r.width() / (280f * u))).coerceIn(0.7f, 1.8f) * u
        val padH = 16f * s
        val padV = 14f * s
        val left = r.left + padH
        val right = r.right - padH

        labelPaint.textSize = 15f * s
        labelPaint.color = theme.text
        val titleBaseline = r.top + padV + 15f * s * 0.82f
        mutedPaint.textSize = 12f * s
        mutedPaint.color = theme.muted
        val caption = spec.caption ?: ("±" + format.withUnit(format.format(maxAbs(spec), 0), spec.channels.firstOrNull()?.unit.orEmpty()))
        val captionW = mutedPaint.measureText(caption)
        c.drawText(st.ellipsized(0, spec.title, labelPaint, right - left - captionW - 12f * s), left, titleBaseline, labelPaint)
        mutedPaint.textAlign = Paint.Align.RIGHT
        c.drawText(caption, right, titleBaseline, mutedPaint)
        mutedPaint.textAlign = Paint.Align.LEFT

        val rowsTop = r.top + padV + 18f * s + 10f * s
        val rowsBottom = r.bottom - padV
        val pitch = (rowsBottom - rowsTop) / n
        val labelCol = 56f * s
        val valueCol = 66f * s
        val barH = 12f * s
        for (i in 0 until n) {
            val cy = rowsTop + pitch * (i + 0.5f)
            val ch = spec.channels[i]
            mutedPaint.textSize = 13f * s
            mutedPaint.color = theme.muted
            c.drawText(st.ellipsized(3 + i.coerceAtMost(3), ch.label, mutedPaint, labelCol - 6f * s), left, cy + 13f * s * CAP / 2f, mutedPaint)
            val target = st.target(i)
            drawBipolarTrack(c, left + labelCol, right - valueCol - 10f * s, cy, barH, s, st.smoothed(i), maxAbs(spec), spec.isWarning(target))
            val text = format.withUnit(st.valueText(i, format, ch.decimals, signed = true), if (target.isNaN()) "" else ch.unit)
            numberPaint.color = if (st.hasData(i)) theme.text else theme.muted
            val size = fit(numberPaint, text, valueCol, 20f * s, 9f * u)
            numberPaint.textAlign = Paint.Align.RIGHT
            c.drawText(text, right, cy + size * CAP / 2f, numberPaint)
            numberPaint.textAlign = Paint.Align.LEFT
        }
    }

    /** Car mockup layout: centered title, then per row "label … big value" over a bar. */
    private fun drawBipolarStacked(c: Canvas, r: RectF, spec: GaugeSpec, st: GaugeState) {
        val u = theme.unit
        val n = spec.channels.size.coerceAtLeast(1)
        val designH = 24f + 26f + 22f + n * 68f + (n - 1) * 22f + 24f
        val s = (min(r.height() / (designH * u), r.width() / (230f * u))).coerceIn(0.45f, 1.7f) * u
        val padH = max(18f * s, 10f * u)
        val left = r.left + padH
        val right = r.right - padH
        val titleSize = max(22f * s, 12f * u)
        val valueSize = 48f * s
        val rowH = valueSize + 8f * s + 12f * s
        val blockH = titleSize * 1.2f + 22f * s + n * rowH + (n - 1) * 22f * s
        var y = r.top + (r.height() - blockH) / 2f

        labelPaint.textSize = titleSize
        labelPaint.color = theme.text
        labelPaint.textAlign = Paint.Align.CENTER
        c.drawText(st.ellipsized(0, spec.title, labelPaint, right - left), r.centerX(), y + titleSize * 0.85f, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        y += titleSize * 1.2f + 22f * s

        for (i in 0 until n) {
            val ch = spec.channels[i]
            val target = st.target(i)
            val baseline = y + valueSize * CAP + (valueSize * (1f - CAP)) * 0.3f
            mutedPaint.textSize = max(18f * s, 11f * u)
            mutedPaint.color = theme.muted
            val labelW = min(mutedPaint.measureText(ch.label), (right - left) * 0.3f)
            c.drawText(st.ellipsized(3 + i.coerceAtMost(3), ch.label, mutedPaint, labelW + 1f), left, baseline, mutedPaint)
            val text = format.withUnit(st.valueText(i, format, ch.decimals, signed = true), if (target.isNaN()) "" else ch.unit)
            numberPaint.color = if (st.hasData(i)) theme.text else theme.muted
            fit(numberPaint, text, right - left - labelW - 10f * s, valueSize, 9f * u)
            numberPaint.textAlign = Paint.Align.RIGHT
            c.drawText(text, right, baseline, numberPaint)
            numberPaint.textAlign = Paint.Align.LEFT
            val barCy = y + valueSize + 8f * s + 6f * s
            drawBipolarTrack(c, left, right, barCy, 12f * s, s, st.smoothed(i), maxAbs(spec), spec.isWarning(target))
            y += rowH + 22f * s
        }
    }

    private fun drawBipolarTrack(
        c: Canvas, left: Float, right: Float, cy: Float, barH: Float, s: Float, v: Double, maxAbs: Double, warn: Boolean,
    ) {
        if (right <= left) return
        val half = barH / 2f
        rr.set(left, cy - half, right, cy + half)
        fillPaint.color = theme.track
        c.drawRoundRect(rr, half, half, fillPaint)
        val center = (left + right) / 2f
        if (!v.isNaN()) {
            val len = ((abs(v) / maxAbs).coerceAtMost(1.0) * (right - left) / 2.0).toFloat()
            if (len > 0.5f) {
                if (v >= 0) rr.set(center, cy - half, center + len, cy + half) else rr.set(center - len, cy - half, center, cy + half)
                fillPaint.color = if (warn) theme.warning else theme.accent
                c.drawRoundRect(rr, half, half, fillPaint)
            }
        }
        fillPaint.color = theme.centerTick
        rr.set(center - 1f * s, cy - half - 3f * s, center + 1f * s, cy + half + 3f * s)
        c.drawRect(rr, fillPaint)
    }

    // ---------------------------------------------------------------- Big number

    private fun drawBigNumber(c: Canvas, r: RectF, spec: GaugeSpec, st: GaugeState, feed: GaugeFeed) {
        val u = theme.unit
        val s = (min(r.height() / (120f * u), r.width() / (150f * u))).coerceIn(0.7f, 4f) * u
        val ls = min(s, 1.5f * u) * if (theme.car) 1.4f else 1f
        val pad = (14f * s).coerceIn(10f * u, 28f * u)
        val left = r.left + pad
        val right = r.right - pad
        val ch = spec.channels.firstOrNull()
        val target = st.target(0)
        val hasData = st.hasData(0)

        mutedPaint.color = theme.muted
        mutedPaint.textSize = 14f * ls
        labelPaint.textSize = 14f * ls
        labelPaint.color = if (hasData) theme.text else theme.muted
        val titleBaseline = r.top + pad + labelPaint.textSize * 0.82f
        c.drawText(st.ellipsized(0, spec.title, labelPaint, right - left), left, titleBaseline, labelPaint)

        // Session min / max line at the bottom.
        val history = ch?.let { feed.history(it.measurementId) }
        val footSize = 12f * ls
        var bottom = r.bottom - pad
        if (history != null && !history.sessionMin.isNaN() && r.height() > 90f * u) {
            mutedPaint.textSize = footSize
            val dec = ch.decimals
            val foot = MIN_LABEL + " " + format.format(history.sessionMin, dec) + "   " + MAX_LABEL + " " + format.format(history.sessionMax, dec)
            c.drawText(st.ellipsized(1, foot, mutedPaint, right - left), left, bottom, mutedPaint)
            bottom -= footSize * 1.6f
        }

        val boxTop = titleBaseline + 6f * ls
        val boxH = bottom - boxTop
        if (boxH <= 4f) return
        val text = st.valueText(0, format, ch?.decimals ?: 1)
        val unit = if (target.isNaN()) "" else ch?.unit.orEmpty()
        numberPaint.color = when {
            !hasData -> theme.muted
            spec.isWarning(target) -> theme.warning
            else -> theme.text
        }
        // Large but calm: the digits fill about half of the free height.
        var size = min(boxH / CAP * 0.5f, 96f * s)
        numberPaint.textSize = size
        mutedPaint.textSize = size * 0.30f
        val gapK = if (unit == "°") 0.02f else 0.08f
        val gap = size * gapK
        var total = numberPaint.measureText(text) + if (unit.isEmpty()) 0f else gap + mutedPaint.measureText(unit)
        val avail = right - left
        if (total > avail) {
            size *= avail / total
            numberPaint.textSize = size
            mutedPaint.textSize = size * 0.30f
            total = avail
        }
        val x = r.centerX() - total / 2f
        val baseline = boxTop + boxH / 2f + size * CAP / 2f
        c.drawText(text, x, baseline, numberPaint)
        if (unit == "°") c.drawText(unit, x + numberPaint.measureText(text), baseline, numberPaint)
        else if (unit.isNotEmpty()) c.drawText(unit, x + numberPaint.measureText(text) + size * gapK, baseline, mutedPaint)
    }

    // ---------------------------------------------------------------- Trend

    private fun drawTrend(c: Canvas, r: RectF, spec: GaugeSpec, st: GaugeState, feed: GaugeFeed) {
        val u = theme.unit
        val s = (min(r.height() / (84f * u), r.width() / (150f * u))).coerceIn(0.75f, 2.4f) * u
        val ls = min(s, 1.4f * u) * if (theme.car) 1.4f else 1f
        val padH = 14f * min(s, 1.6f * u)
        val padV = 10f * min(s, 1.6f * u)
        val left = r.left + padH
        val right = r.right - padH
        val ch = spec.channels.firstOrNull()
        val target = st.target(0)
        val hasData = st.hasData(0)

        mutedPaint.color = theme.muted
        mutedPaint.textSize = 13f * ls
        var y = r.top + padV + 13f * ls * 0.82f
        c.drawText(st.ellipsized(0, spec.title, mutedPaint, right - left), left, y, mutedPaint)

        val valueSize = 26f * s
        y += 9f * ls + valueSize * CAP
        val text = st.valueText(0, format, ch?.decimals ?: 1)
        val unit = if (target.isNaN()) "" else ch?.unit.orEmpty()
        val warn = spec.isWarning(target)
        numberPaint.color = when {
            !hasData -> theme.muted
            warn -> theme.warning
            else -> theme.text
        }
        mutedPaint.textSize = max(13f * ls, valueSize * 0.4f)
        val unitGap = if (unit == "°") 1f * s else 4f * s
        val unitW = if (unit.isEmpty()) 0f else mutedPaint.measureText(unit) + unitGap
        fit(numberPaint, text, right - left - unitW, valueSize, 9f * u)
        c.drawText(text, left, y, numberPaint)
        // A lone degree sign belongs to the digits ("10,9°"), other units are smaller and muted.
        if (unit == "°") c.drawText(unit, left + numberPaint.measureText(text), y, numberPaint)
        else if (unit.isNotEmpty()) c.drawText(unit, left + numberPaint.measureText(text) + unitGap, y, mutedPaint)

        val sparkTop = y + 8f * min(s, 1.6f * u)
        val sparkBottom = r.bottom - padV
        if (sparkBottom - sparkTop >= 10f * u && ch != null) {
            drawSparkline(c, left, sparkTop, right, sparkBottom, feed.history(ch.measurementId), feed.nowMs, spec, st, warn, 1.6f * u)
        }
    }

    private fun drawSparkline(
        c: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        history: ValueHistory?, nowMs: Long, spec: GaugeSpec, st: GaugeState, warn: Boolean, strokeWidth: Float,
    ) {
        if (history == null || history.size < 2) {
            fillPaint.color = theme.track
            c.drawRect(left, bottom - strokeWidth / 2f, right, bottom + strokeWidth / 2f, fillPaint)
            return
        }
        val from = nowMs - TREND_WINDOW_MS
        var first = history.firstIndexSince(from)
        if (first > 0) first-- // keep the line entering from the left edge
        val last = history.size - 1
        if (last - first < 1) return
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in first..last) {
            val v = history.valueAt(i)
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        // Do not amplify sensor noise: show at least 4 % of the card range.
        val minSpan = ((spec.max - spec.min) * 0.04).toFloat().coerceAtLeast(1e-3f)
        if (hi - lo < minSpan) {
            val mid = (hi + lo) / 2f
            lo = mid - minSpan / 2f
            hi = mid + minSpan / 2f
        }
        val inset = strokeWidth
        val h = bottom - top - 2 * inset
        val w = right - left
        val path = st.path
        val fill = st.fillPath
        path.rewind()
        fill.rewind()
        var lastX = left
        for (i in first..last) {
            val x = (left + w * ((history.timeAt(i) - from).toFloat() / TREND_WINDOW_MS)).coerceIn(left, right)
            val y = top + inset + h * (1f - (history.valueAt(i) - lo) / (hi - lo))
            if (i == first) {
                path.moveTo(x, y)
                fill.moveTo(x, bottom)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
            lastX = x
        }
        fill.lineTo(lastX, bottom)
        fill.close()
        val color = if (warn) theme.warning else theme.accent
        if (areaShaderTop != top || areaShaderBottom != bottom || areaShaderColor != color) {
            areaPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                (color and 0x00FFFFFF) or 0x33000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP,
            )
            areaShaderTop = top
            areaShaderBottom = bottom
            areaShaderColor = color
        }
        c.drawPath(fill, areaPaint)
        linePaint.color = color
        linePaint.strokeWidth = strokeWidth
        c.drawPath(path, linePaint)
    }

    // ---------------------------------------------------------------- Multi value

    private fun drawMultiValue(c: Canvas, r: RectF, spec: GaugeSpec, st: GaugeState) {
        val u = theme.unit
        val n = spec.channels.size.coerceAtLeast(1)
        val s = (min(r.height() / (84f * u), r.width() / (60f * n * u + 40f * u))).coerceIn(0.75f, 2.6f) * u
        val ls = min(s, 1.4f * u) * if (theme.car) 1.4f else 1f
        val padH = 14f * min(s, 1.6f * u)
        val padV = 10f * min(s, 1.6f * u)
        val left = r.left + padH
        val right = r.right - padH

        // Title row, with the shared unit on the right.
        mutedPaint.color = theme.muted
        mutedPaint.textSize = 13f * ls
        val units = spec.channels.map { it.unit }.distinct()
        val sharedUnit = units.singleOrNull().orEmpty()
        val titleBaseline = r.top + padV + 13f * ls * 0.82f
        val unitW = if (sharedUnit.isEmpty()) 0f else mutedPaint.measureText(sharedUnit) + 8f * ls
        c.drawText(st.ellipsized(0, spec.title, mutedPaint, right - left - unitW), left, titleBaseline, mutedPaint)
        if (sharedUnit.isNotEmpty()) {
            mutedPaint.textAlign = Paint.Align.RIGHT
            c.drawText(sharedUnit, right, titleBaseline, mutedPaint)
            mutedPaint.textAlign = Paint.Align.LEFT
        }

        val areaTop = titleBaseline + 4f * ls
        val areaBottom = r.bottom - padV
        val chanSize = 11f * ls
        val colW = (right - left) / n
        var valueSize = min(26f * s, (areaBottom - areaTop - chanSize * 1.5f) / CAP * 0.9f)
        // One common size so the values line up.
        for (i in 0 until n) {
            val ch = spec.channels[i]
            val t = st.valueText(i, format, ch.decimals)
            numberPaint.textSize = valueSize
            val tw = numberPaint.measureText(if (sharedUnit.isEmpty() && !st.target(i).isNaN()) format.withUnit(t, ch.unit) else t)
            if (tw > colW - 10f * s) valueSize *= (colW - 10f * s) / tw
        }
        valueSize = max(valueSize, 8f * u)
        val blockH = valueSize * CAP + chanSize * 1.5f
        val baseline = areaTop + (areaBottom - areaTop - blockH) / 2f + valueSize * CAP
        numberPaint.textSize = valueSize
        numberPaint.textAlign = Paint.Align.CENTER
        mutedPaint.textSize = chanSize
        mutedPaint.textAlign = Paint.Align.CENTER
        fillPaint.color = theme.track
        for (i in 0 until n) {
            val ch = spec.channels[i]
            val cx = left + colW * (i + 0.5f)
            val target = st.target(i)
            val t = st.valueText(i, format, ch.decimals)
            numberPaint.color = when {
                !st.hasData(i) -> theme.muted
                spec.isWarning(target) -> theme.warning
                else -> theme.text
            }
            c.drawText(if (sharedUnit.isEmpty() && !target.isNaN()) format.withUnit(t, ch.unit) else t, cx, baseline, numberPaint)
            c.drawText(st.ellipsized(3 + i.coerceAtMost(3), ch.label, mutedPaint, colW - 8f * s), cx, baseline + chanSize * 1.45f, mutedPaint)
            if (i > 0) {
                val x = left + colW * i
                c.drawRect(x - 0.5f * u, baseline - valueSize * CAP, x + 0.5f * u, baseline + chanSize * 1.45f, fillPaint)
            }
        }
        numberPaint.textAlign = Paint.Align.LEFT
        mutedPaint.textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- Status

    private fun drawStatus(c: Canvas, r: RectF, spec: GaugeSpec, st: GaugeState) {
        val u = theme.unit
        val s = (min(r.height() / (84f * u), r.width() / (150f * u))).coerceIn(0.75f, 3f) * u
        val ls = min(s, 1.4f * u) * if (theme.car) 1.4f else 1f
        val pad = 14f * min(s, 1.6f * u)
        val left = r.left + pad
        val right = r.right - pad
        mutedPaint.color = theme.muted
        mutedPaint.textSize = 13f * ls
        val titleBaseline = r.top + pad + 13f * ls * 0.82f
        c.drawText(st.ellipsized(0, spec.title, mutedPaint, right - left), left, titleBaseline, mutedPaint)

        val ch = spec.channels.firstOrNull()
        val target = st.target(0)
        val raw = st.text(0)?.takeIf { it.isNotBlank() }
            ?: format.withUnit(st.valueText(0, format, ch?.decimals ?: 0), if (target.isNaN()) "" else ch?.unit.orEmpty())
        labelPaint.color = when {
            !st.hasData(0) -> theme.muted
            spec.isWarning(target) -> theme.warning
            else -> theme.text
        }
        val areaTop = titleBaseline + 4f * ls
        val areaH = r.bottom - pad - areaTop
        val size = min(22f * s, areaH / CAP * 0.7f).coerceAtLeast(9f * u)
        labelPaint.textSize = size
        val shown = st.ellipsized(1, raw, labelPaint, right - left)
        c.drawText(shown, left, areaTop + areaH / 2f + size * CAP / 2f, labelPaint)
    }

    // ---------------------------------------------------------------- helpers

    /** Sets [paint]'s size to [size], shrunk so [text] fits [maxWidth] (not below [minSize]). */
    private fun fit(paint: Paint, text: String, maxWidth: Float, size: Float, minSize: Float): Float {
        paint.textSize = size
        val w = paint.measureText(text)
        if (w > maxWidth && w > 0f) paint.textSize = max(minSize, size * maxWidth / w)
        return paint.textSize
    }

    companion object {
        /** Cap height of Barlow / Barlow Semi Condensed digits, relative to the font size. */
        const val CAP = 0.70f
        const val TREND_WINDOW_MS = 45_000L

        /** Same word in English and French. */
        private const val MIN_LABEL = "min"
        private const val MAX_LABEL = "max"
    }
}
