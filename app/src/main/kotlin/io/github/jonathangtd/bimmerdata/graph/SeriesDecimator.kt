/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph

import io.github.jonathangtd.bimmerdata.gauges.ValueHistory

/**
 * Reduces the points of one curve in a time range to what [columns] pixels can show (the minimum
 * and the maximum of each pixel column, in time order, so spikes stay visible), and cuts the curve
 * where nothing was read for more than [gapMs]: a break is an output point with x = NaN.
 *
 * Unlike the trend card decimator, x is not clamped: the point before the range has x < 0 and the
 * one after it x > 1 (the renderer clips), so the curve meets both edges at the right height.
 * [visibleMin] / [visibleMax] are the extremes of the points inside the range (for the scale).
 * Buffers are reused between frames; not thread safe.
 */
class SeriesDecimator(var gapMs: Long = GraphMath.GAP_MS) : ValueHistory.PointSink {
    var xs = FloatArray(64)
        private set
    var vs = FloatArray(64)
        private set
    var count = 0
        private set
    var visibleMin = Float.NaN
        private set
    var visibleMax = Float.NaN
        private set

    private var inX = FloatArray(64)
    private var inV = FloatArray(64)
    private var inCount = 0
    private var lastT = Long.MIN_VALUE
    private var fromMs = 0L
    private var windowMs = 1L
    private var columns = 1

    fun begin(fromMs: Long, windowMs: Long, columns: Int) {
        this.fromMs = fromMs
        this.windowMs = windowMs.coerceAtLeast(1)
        this.columns = columns.coerceAtLeast(1)
        inCount = 0
        count = 0
        lastT = Long.MIN_VALUE
        visibleMin = Float.NaN
        visibleMax = Float.NaN
    }

    override fun point(timeMs: Long, value: Float) {
        if (lastT != Long.MIN_VALUE && timeMs - lastT > gapMs) addInput(Float.NaN, 0f)
        lastT = timeMs
        val x = ((timeMs - fromMs).toDouble() / windowMs).toFloat()
        addInput(x, value)
        if (x in 0f..1f) {
            if (visibleMin.isNaN() || value < visibleMin) visibleMin = value
            if (visibleMax.isNaN() || value > visibleMax) visibleMax = value
        }
    }

    fun end() {
        count = 0
        ensure(inCount + 2)
        if (inCount <= columns * 2) {
            System.arraycopy(inX, 0, xs, 0, inCount)
            System.arraycopy(inV, 0, vs, 0, inCount)
            count = inCount
            return
        }
        var col = Int.MIN_VALUE
        var minV = 0f
        var maxV = 0f
        var minI = 0
        var maxI = 0
        var n = 0
        for (i in 0 until inCount) {
            val x = inX[i]
            if (x.isNaN()) {
                if (n > 0) flush(minV, maxV, minI, maxI, n)
                n = 0
                col = Int.MIN_VALUE
                add(Float.NaN, 0f)
                continue
            }
            // points outside the range keep their own column so the edges stay exact
            val c = if (x < 0f) -1 else if (x > 1f) columns else (x * columns).toInt().coerceAtMost(columns - 1)
            if (c != col) {
                if (n > 0) flush(minV, maxV, minI, maxI, n)
                col = c
                minV = inV[i]; maxV = inV[i]; minI = i; maxI = i; n = 1
            } else {
                if (inV[i] < minV) { minV = inV[i]; minI = i }
                if (inV[i] > maxV) { maxV = inV[i]; maxI = i }
                n++
            }
        }
        if (n > 0) flush(minV, maxV, minI, maxI, n)
    }

    private fun flush(minV: Float, maxV: Float, minI: Int, maxI: Int, n: Int) {
        if (n == 1 || minV == maxV) {
            add(inX[minI], minV)
        } else if (minI < maxI) {
            add(inX[minI], minV); add(inX[maxI], maxV)
        } else {
            add(inX[maxI], maxV); add(inX[minI], minV)
        }
    }

    private fun addInput(x: Float, v: Float) {
        if (inCount == inX.size) {
            inX = inX.copyOf(inCount * 2)
            inV = inV.copyOf(inCount * 2)
        }
        inX[inCount] = x
        inV[inCount] = v
        inCount++
    }

    private fun add(x: Float, v: Float) {
        if (count == xs.size) {
            xs = xs.copyOf(count * 2)
            vs = vs.copyOf(count * 2)
        }
        xs[count] = x
        vs[count] = v
        count++
    }

    private fun ensure(n: Int) {
        if (xs.size < n) {
            xs = FloatArray(n)
            vs = FloatArray(n)
        }
    }
}
