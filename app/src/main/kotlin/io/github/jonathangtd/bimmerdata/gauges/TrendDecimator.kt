/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

/**
 * Reduces the points of a time window to what a curve of [columns] pixels can show: at most the
 * minimum and the maximum of each pixel column, in the order they happened, so spikes stay visible
 * however long the window. Few points (≤ 2 per column) are kept as they are.
 *
 * Output: [count] points, x as a fraction of the width (0 = window start, 1 = now) in [xs] and
 * values in [vs]. Buffers are reused between frames (one instance per card); not thread safe.
 */
class TrendDecimator : ValueHistory.PointSink {
    var xs = FloatArray(64)
        private set
    var vs = FloatArray(64)
        private set
    var count = 0
        private set

    // raw input of the current frame (reused)
    private var inX = FloatArray(64)
    private var inV = FloatArray(64)
    private var inCount = 0

    private var fromMs = 0L
    private var windowMs = 1L
    private var columns = 1

    /** Starts a frame: points between [fromMs] and [fromMs] + [windowMs], drawn on [columns] pixels. */
    fun begin(fromMs: Long, windowMs: Long, columns: Int) {
        this.fromMs = fromMs
        this.windowMs = windowMs.coerceAtLeast(1)
        this.columns = columns.coerceAtLeast(1)
        inCount = 0
        count = 0
    }

    override fun point(timeMs: Long, value: Float) {
        if (inCount == inX.size) {
            inX = inX.copyOf(inCount * 2)
            inV = inV.copyOf(inCount * 2)
        }
        inX[inCount] = ((timeMs - fromMs).toDouble() / windowMs).toFloat().coerceIn(0f, 1f)
        inV[inCount] = value
        inCount++
    }

    /** Ends the frame and fills [xs] / [vs]. */
    fun end() {
        count = 0
        if (inCount <= columns * 2) {
            ensure(inCount)
            System.arraycopy(inX, 0, xs, 0, inCount)
            System.arraycopy(inV, 0, vs, 0, inCount)
            count = inCount
            return
        }
        ensure(columns * 2 + 2)
        var col = -1
        var minV = 0f
        var maxV = 0f
        var minI = 0
        var maxI = 0
        var n = 0
        for (i in 0 until inCount) {
            val c = (inX[i] * columns).toInt().coerceAtMost(columns - 1)
            if (c != col) {
                if (n > 0) flush(col, minV, maxV, minI, maxI, n)
                col = c
                minV = inV[i]; maxV = inV[i]; minI = i; maxI = i; n = 1
            } else {
                if (inV[i] < minV) { minV = inV[i]; minI = i }
                if (inV[i] > maxV) { maxV = inV[i]; maxI = i }
                n++
            }
        }
        if (n > 0) flush(col, minV, maxV, minI, maxI, n)
    }

    private fun flush(col: Int, minV: Float, maxV: Float, minI: Int, maxI: Int, n: Int) {
        if (n == 1 || minV == maxV) {
            add(inX[minI], minV)
            return
        }
        // Both extremes at their own x (clamped inside the column), in time order.
        if (minI < maxI) {
            add(inX[minI], minV); add(inX[maxI], maxV)
        } else {
            add(inX[maxI], maxV); add(inX[minI], minV)
        }
    }

    private fun add(x: Float, v: Float) {
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
