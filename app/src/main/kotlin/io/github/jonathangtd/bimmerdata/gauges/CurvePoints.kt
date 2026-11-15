/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

/**
 * Points of a [GaugeType.Curve] card: each recent reading of a value Y paired with the reading of the
 * value X nearest in time (at most [MAX_GAP_MS] apart), e.g. a power against the engine speed.
 * Reused from frame to frame (no allocation once grown). Not thread safe, like [ValueHistory].
 */
class CurvePoints {
    var count = 0
        private set
    var xs = FloatArray(64)
        private set
    var ys = FloatArray(64)
        private set

    /** Age of each point as a fraction of the window: 0 = now, 1 = the oldest kept. */
    var ages = FloatArray(64)
        private set

    private val near = FloatArray(2)

    /** Collects the readings of [y] from the last [windowMs] before [nowMs], each with the [x] reading nearest in time. */
    fun collect(x: ValueHistory?, y: ValueHistory?, nowMs: Long, windowMs: Long) {
        count = 0
        if (x == null || y == null || x.size == 0 || y.size == 0) return
        val from = nowMs - windowMs
        for (i in y.firstIndexSince(from) until y.size) {
            val t = y.timeAt(i)
            // a raw sample of x (bucket min / max pairs are not one reading)
            if (!x.valueNear(t, MAX_GAP_MS, near) || near[0] != near[1]) continue
            if (count == xs.size) grow()
            xs[count] = near[0]
            ys[count] = y.valueAt(i)
            ages[count] = ((nowMs - t).toFloat() / windowMs).coerceIn(0f, 1f)
            count++
        }
    }

    private fun grow() {
        xs = xs.copyOf(xs.size * 2)
        ys = ys.copyOf(ys.size * 2)
        ages = ages.copyOf(ages.size * 2)
    }

    companion object {
        /** Two readings further apart than this are not paired (same skew as the computed values). */
        const val MAX_GAP_MS = 2_000L
    }
}
