/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph

import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** A vertical scale with "nice" round ticks: [min], [min] + [step], ... [max]. */
data class NiceScale(val min: Double, val max: Double, val step: Double) {
    val span: Double get() = max - min

    /** Tick values from [min] to [max]. */
    fun ticks(): List<Double> {
        val n = ((max - min) / step).roundToLong().toInt().coerceIn(1, 50)
        return (0..n).map { min + it * step }
    }

    /** Decimals needed to print every tick exactly (0 for 500, 1 for 0.5, 2 for 0.25). */
    val decimals: Int
        get() {
            for (d in 0..4) {
                val scaled = step * 10.0.pow(d)
                if (abs(scaled - scaled.roundToLong()) < 1e-6 * scaled.coerceAtLeast(1.0)) return d
            }
            return 4
        }

    /** Fraction of the height (0 = bottom, 1 = top) of [value]. */
    fun fraction(value: Double): Double = (value - min) / (max - min)

    companion object {
        private val MANTISSAS = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)

        /**
         * Scale covering [lo]..[hi] with at most [maxTicks] ticks. A span below [minSpan] is
         * widened around its middle (sensor noise is not magnified into a full-height zigzag).
         */
        fun of(lo: Double, hi: Double, maxTicks: Int = 5, minSpan: Double = 0.0): NiceScale {
            var a = lo
            var b = hi
            if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) {
                a = 0.0; b = 1.0
            }
            if (a > b) a = b.also { b = a }
            val floorSpan = maxOf(minSpan, abs((a + b) / 2) * 1e-6, 1e-9)
            if (b - a < floorSpan) {
                val mid = (a + b) / 2
                a = mid - floorSpan / 2
                b = mid + floorSpan / 2
            }
            val intervals = (maxTicks - 1).coerceAtLeast(1)
            val raw = (b - a) / intervals
            val mag = 10.0.pow(floor(log10(raw)))
            for (m in MANTISSAS) {
                val step = m * mag
                val min = floor(a / step + 1e-9) * step
                val max = ceil(b / step - 1e-9) * step
                if ((max - min) / step <= intervals + 1e-9) return NiceScale(min, if (max > min) max else min + step, step)
            }
            val step = 10.0 * mag * 2
            return NiceScale(floor(a / step) * step, ceil(b / step) * step, step)
        }
    }
}

/** Values sharing one vertical axis in [GraphAxes.Overlay]: same displayed unit. */
data class AxisGroup(
    /** Displayed unit ("°C"); empty for a value without unit (such values never share an axis). */
    val unit: String,
    /** Indexes of the member series, in the order given. */
    val members: List<Int>,
)

object GraphMath {
    /** Read gaps longer than this break the curve (no line drawn across time with no reading). */
    const val GAP_MS = 5_000L

    /** Groups series by displayed unit, first appearance first; a blank unit is a group of its own. */
    fun groups(units: List<String>): List<AxisGroup> {
        val out = ArrayList<Pair<String, MutableList<Int>>>()
        units.forEachIndexed { i, raw ->
            val u = raw.trim()
            val existing = if (u.isEmpty()) null else out.firstOrNull { it.first == u }
            if (existing != null) existing.second += i else out += u to mutableListOf(i)
        }
        return out.map { AxisGroup(it.first, it.second) }
    }
}

/**
 * The time range a graph shows: [spanMs] wide, ending now while live ([endMs] null) or at
 * [endMs] once paused. Zooming and scrolling return a new view; scrolling back to the present
 * resumes live.
 */
data class GraphView(val spanMs: Long, val endMs: Long? = null) {
    val live: Boolean get() = endMs == null

    fun end(nowMs: Long): Long = endMs ?: nowMs

    fun start(nowMs: Long): Long = end(nowMs) - spanMs

    fun pause(nowMs: Long): GraphView = if (live) copy(endMs = nowMs) else this

    fun resume(): GraphView = copy(endMs = null)

    /**
     * Zoom by [factor] (> 1 = closer) around [anchor] (0 = left edge, 1 = right edge). Live views
     * stay anchored to now; a paused view keeps the time under the fingers in place.
     */
    fun zoom(factor: Float, anchor: Float, nowMs: Long): GraphView {
        if (factor <= 0f || factor.isNaN()) return this
        val newSpan = (spanMs / factor.toDouble()).roundToLong().coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
        if (live) return copy(spanMs = newSpan)
        val a = anchor.coerceIn(0f, 1f).toDouble()
        val end = end(nowMs)
        val pivot = end - spanMs * (1 - a)
        val newEnd = (pivot + newSpan * (1 - a)).roundToLong()
        return if (newEnd >= nowMs) GraphView(newSpan) else GraphView(newSpan, newEnd)
    }

    /**
     * Scroll by [dxFraction] of the width (> 0: the finger moves right, so older data comes in).
     * Never before [oldestMs] (the oldest point kept) and back to live when reaching now.
     */
    fun pan(dxFraction: Float, nowMs: Long, oldestMs: Long?): GraphView {
        val end = end(nowMs) - (dxFraction.toDouble() * spanMs).roundToLong()
        if (end >= nowMs) return GraphView(spanMs)
        val earliest = minOf(nowMs, (oldestMs ?: nowMs) + spanMs / 5)
        return GraphView(spanMs, end.coerceAtLeast(earliest))
    }

    /** The span in whole seconds, as stored in [Graph.windowSeconds]. */
    val seconds: Int get() = ((spanMs + 500) / 1000).toInt().coerceIn(TrendWindow.MIN_SECONDS, TrendWindow.MAX_SECONDS)

    companion object {
        const val MIN_SPAN_MS = TrendWindow.MIN_SECONDS * 1000L
        const val MAX_SPAN_MS = TrendWindow.MAX_SECONDS * 1000L

        fun of(windowSeconds: Int?): GraphView = GraphView(TrendWindow.ms(windowSeconds))
    }
}
