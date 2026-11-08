/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

/**
 * Recent samples of one measurement for trend curves, in bounded memory, plus the session
 * minimum and maximum. Two levels:
 * - **raw**: a ring of the last samples (at most [capacity]; about 3 min at 20 reads/s with the
 *   default 4096), grown on demand;
 * - **buckets**: one min / max pair per [bucketMs] slot, kept for [retainMs] (the longest window a
 *   card shows, set by the feed). Long windows are drawn from the buckets, so a 30 min curve costs
 *   about 30 KB per measurement instead of 36 000 samples.
 *
 * Not thread safe: fed and read on one thread (UI thread, or the Android Auto render thread).
 */
class ValueHistory(
    /** Maximum number of raw samples kept. */
    val capacity: Int = DEFAULT_RAW_CAPACITY,
    val bucketMs: Long = 1000L,
    retainMs: Long = TrendWindow.DEFAULT_MS,
) {
    /** Receives the points of a time range, oldest first (no allocation per point). */
    interface PointSink {
        fun point(timeMs: Long, value: Float)
    }

    // ---- raw ring ----
    private var times = LongArray(minOf(capacity, INITIAL_RAW))
    private var values = FloatArray(times.size)
    private var start = 0

    /** Raw samples currently kept. */
    var size = 0
        private set

    /** True once a raw sample has been overwritten: the raw ring no longer holds the whole session. */
    private var rawDropped = false

    var lastTimestampMs = Long.MIN_VALUE
        private set
    var sessionMin = Double.NaN
        private set
    var sessionMax = Double.NaN
        private set

    // ---- buckets ring ----
    private var bStart = LongArray(0)
    private var bMin = FloatArray(0)
    private var bMax = FloatArray(0)
    private var bMinFirst = BooleanArray(0)
    private var bHead = 0
    private var bCount = 0

    /** How far back the buckets go; the bucket ring is resized (newest kept) when it changes. */
    var retainMs: Long = 0
        set(value) {
            val v = value.coerceAtLeast(bucketMs)
            if (v == field) return
            field = v
            resizeBuckets((v / bucketMs + 2).toInt())
        }

    init {
        this.retainMs = retainMs
    }

    val isEmpty: Boolean get() = size == 0

    /** Buckets currently kept (for tests and memory estimates). */
    val bucketCount: Int get() = bCount

    /** Approximate heap used by the sample arrays, in bytes. */
    val memoryBytes: Long get() = times.size * 12L + bStart.size * 17L

    fun add(timestampMs: Long, value: Double) {
        if (value.isNaN() || timestampMs <= lastTimestampMs) return
        lastTimestampMs = timestampMs
        val v = value.toFloat()
        addRaw(timestampMs, v)
        addBucket(timestampMs, v)
        if (sessionMin.isNaN() || value < sessionMin) sessionMin = value
        if (sessionMax.isNaN() || value > sessionMax) sessionMax = value
    }

    fun clear() {
        start = 0
        size = 0
        rawDropped = false
        bHead = 0
        bCount = 0
        lastTimestampMs = Long.MIN_VALUE
        sessionMin = Double.NaN
        sessionMax = Double.NaN
    }

    fun timeAt(i: Int): Long = times[(start + i) % times.size]
    fun valueAt(i: Int): Float = values[(start + i) % values.size]

    /** Index of the first raw sample at or after [timestampMs]. */
    fun firstIndexSince(timestampMs: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timeAt(mid) < timestampMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** True when the raw samples reach back to [fromMs] (or hold the whole session). */
    fun rawCovers(fromMs: Long): Boolean = size > 0 && (!rawDropped || timeAt(0) <= fromMs)

    /**
     * Sends the points from [fromMs] to now to [sink], oldest first, plus the last point before
     * [fromMs] so a curve enters from the left edge. Raw samples when they reach back far enough,
     * else the buckets: two points per bucket (its min and max, in the order they happened).
     */
    fun forEachPoint(fromMs: Long, sink: PointSink) {
        if (size == 0) return
        if (rawCovers(fromMs)) {
            var i = firstIndexSince(fromMs)
            if (i > 0) i--
            for (j in i until size) sink.point(timeAt(j), valueAt(j))
            return
        }
        if (bCount == 0) return
        // first bucket ending after fromMs, minus one for the entry point
        var k = 0
        while (k < bCount && bucketStartAt(k) + bucketMs <= fromMs) k++
        if (k > 0) k--
        val q1 = bucketMs / 4
        val q3 = bucketMs - q1
        for (j in k until bCount) {
            val idx = (bHead + j) % bStart.size
            val s = bStart[idx]
            val lo = bMin[idx]
            val hi = bMax[idx]
            val t2 = minOf(s + q3, lastTimestampMs)
            val t1 = minOf(s + q1, t2)
            if (lo == hi) sink.point(t1, lo)
            else if (bMinFirst[idx]) {
                sink.point(t1, lo); sink.point(t2, hi)
            } else {
                sink.point(t1, hi); sink.point(t2, lo)
            }
        }
    }

    private fun bucketStartAt(j: Int): Long = bStart[(bHead + j) % bStart.size]

    private fun addRaw(t: Long, v: Float) {
        if (size == times.size && times.size < capacity) grow(minOf(capacity, times.size * 2))
        val cap = times.size
        val index = (start + size) % cap
        times[index] = t
        values[index] = v
        if (size < cap) size++ else {
            start = (start + 1) % cap
            rawDropped = true
        }
    }

    private fun grow(newCap: Int) {
        val nt = LongArray(newCap)
        val nv = FloatArray(newCap)
        for (i in 0 until size) {
            nt[i] = timeAt(i)
            nv[i] = valueAt(i)
        }
        times = nt
        values = nv
        start = 0
    }

    private fun addBucket(t: Long, v: Float) {
        val cap = bStart.size
        if (cap == 0) return
        val key = Math.floorDiv(t, bucketMs) * bucketMs
        if (bCount > 0) {
            val newest = (bHead + bCount - 1) % cap
            if (bStart[newest] == key) {
                if (v < bMin[newest]) { bMin[newest] = v; bMinFirst[newest] = false }
                if (v > bMax[newest]) { bMax[newest] = v; bMinFirst[newest] = true }
                return
            }
        }
        val idx: Int
        if (bCount < cap) {
            idx = (bHead + bCount) % cap
            bCount++
        } else {
            idx = bHead
            bHead = (bHead + 1) % cap
        }
        bStart[idx] = key
        bMin[idx] = v
        bMax[idx] = v
        bMinFirst[idx] = true
    }

    private fun resizeBuckets(newCap: Int) {
        val keep = minOf(bCount, newCap)
        val ns = LongArray(newCap)
        val nmin = FloatArray(newCap)
        val nmax = FloatArray(newCap)
        val nf = BooleanArray(newCap)
        val old = bStart.size
        for (j in 0 until keep) {
            val src = (bHead + bCount - keep + j) % old
            ns[j] = bStart[src]
            nmin[j] = bMin[src]
            nmax[j] = bMax[src]
            nf[j] = bMinFirst[src]
        }
        bStart = ns
        bMin = nmin
        bMax = nmax
        bMinFirst = nf
        bHead = 0
        bCount = keep
    }

    companion object {
        const val DEFAULT_RAW_CAPACITY = 4096
        private const val INITIAL_RAW = 256
    }
}
