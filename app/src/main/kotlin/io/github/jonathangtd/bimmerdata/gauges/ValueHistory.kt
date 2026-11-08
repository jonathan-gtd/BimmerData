/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

/**
 * Fixed-size ring buffer of recent numeric samples of one measurement (for sparklines), plus
 * the session minimum and maximum. Not thread safe: fed and read on the UI thread.
 */
class ValueHistory(capacity: Int = 1024) {
    private val times = LongArray(capacity)
    private val values = FloatArray(capacity)
    private var start = 0
    var size = 0
        private set
    var lastTimestampMs = Long.MIN_VALUE
        private set
    var sessionMin = Double.NaN
        private set
    var sessionMax = Double.NaN
        private set

    fun add(timestampMs: Long, value: Double) {
        if (value.isNaN() || timestampMs <= lastTimestampMs) return
        lastTimestampMs = timestampMs
        val cap = times.size
        val index = (start + size) % cap
        times[index] = timestampMs
        values[index] = value.toFloat()
        if (size < cap) size++ else start = (start + 1) % cap
        if (sessionMin.isNaN() || value < sessionMin) sessionMin = value
        if (sessionMax.isNaN() || value > sessionMax) sessionMax = value
    }

    fun clear() {
        start = 0
        size = 0
        lastTimestampMs = Long.MIN_VALUE
        sessionMin = Double.NaN
        sessionMax = Double.NaN
    }

    fun timeAt(i: Int): Long = times[(start + i) % times.size]
    fun valueAt(i: Int): Float = values[(start + i) % values.size]

    /** Index of the first sample at or after [timestampMs]. */
    fun firstIndexSince(timestampMs: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timeAt(mid) < timestampMs) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
