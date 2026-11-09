/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph

import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Scales, axis groups, the viewport (pause / zoom / scroll), the decimator and history ranges. */
class GraphMathTest {
    // ---------------------------------------------------------------- scales

    @Test
    fun `nice scales cover the data with round ticks`() {
        val rpm = NiceScale.of(780.0, 5940.0, maxTicks = 5)
        assertTrue(rpm.min <= 780.0 && rpm.max >= 5940.0)
        assertEquals(2000.0, rpm.step)
        assertEquals(listOf(0.0, 2000.0, 4000.0, 6000.0), rpm.ticks())
        assertEquals(0, rpm.decimals)

        val lambda = NiceScale.of(0.96, 1.04, maxTicks = 5)
        assertEquals(0.02, lambda.step, 1e-12)
        assertEquals(2, lambda.decimals)
        assertTrue(lambda.min <= 0.96 && lambda.max >= 1.04)

        val volts = NiceScale.of(0.1, 0.9, maxTicks = 5)
        assertEquals(0.25, volts.step, 1e-12)
        assertEquals(2, volts.decimals)
    }

    @Test
    fun `a flat curve is widened to the minimum span, odd inputs give a usable scale`() {
        val flat = NiceScale.of(90.0, 90.0, maxTicks = 5, minSpan = 6.0)
        assertTrue(flat.min <= 87.0 && flat.max >= 93.0, "$flat")
        assertTrue(flat.span >= 6.0)
        val nan = NiceScale.of(Double.NaN, 3.0)
        assertTrue(nan.max > nan.min)
        val reversed = NiceScale.of(10.0, -10.0)
        assertTrue(reversed.min <= -10.0 && reversed.max >= 10.0)
        assertEquals(0.5, NiceScale(0.0, 10.0, 5.0).fraction(5.0))
    }

    @Test
    fun `values share an axis per unit, values without unit get their own`() {
        val g = GraphMath.groups(listOf("°C", "tr/min", "°C", "", ""))
        assertEquals(listOf("°C", "tr/min", "", ""), g.map { it.unit })
        assertEquals(listOf(listOf(0, 2), listOf(1), listOf(3), listOf(4)), g.map { it.members })
    }

    // ---------------------------------------------------------------- viewport

    private val now = 1_000_000L

    @Test
    fun `live zoom stays on now, within the window limits`() {
        val v = GraphView(60_000).zoom(2f, 0.3f, now)
        assertTrue(v.live)
        assertEquals(30_000, v.spanMs)
        assertEquals(GraphView.MIN_SPAN_MS, GraphView(10_000).zoom(100f, 0.5f, now).spanMs)
        assertEquals(GraphView.MAX_SPAN_MS, GraphView(600_000).zoom(0.01f, 0.5f, now).spanMs)
    }

    @Test
    fun `paused zoom keeps the time under the fingers in place`() {
        val paused = GraphView(60_000, endMs = now - 100_000)
        val pivot = paused.start(now) + (paused.spanMs * 0.25).toLong()
        val z = paused.zoom(2f, 0.25f, now)
        assertEquals(30_000, z.spanMs)
        assertEquals(pivot, z.start(now) + (z.spanMs * 0.25).toLong())
        // anchored at the right edge the end stays; anchored on the left, zooming out reaches now: live again
        assertEquals(GraphView(120_000, endMs = now - 1_000), GraphView(60_000, endMs = now - 1_000).zoom(0.5f, 1f, now))
        assertTrue(GraphView(60_000, endMs = now - 1_000).zoom(0.5f, 0f, now).live)
    }

    @Test
    fun `scrolling pauses, stops at the oldest data and resumes live at now`() {
        val live = GraphView(60_000)
        val back = live.pan(0.5f, now, oldestMs = now - 600_000)
        assertFalse(back.live)
        assertEquals(now - 30_000, back.endMs)
        val far = back.pan(100f, now, oldestMs = now - 600_000)
        assertEquals(now - 600_000 + 12_000, far.endMs, "the oldest point stays on screen")
        assertTrue(back.pan(-1f, now, oldestMs = null).live)
        assertEquals(now - 5_000, live.pause(now - 5_000).endMs)
        assertTrue(back.resume().live)
        assertEquals(60, back.seconds)
    }

    // ---------------------------------------------------------------- decimator

    @Test
    fun `a reading gap breaks the curve`() {
        val d = SeriesDecimator(gapMs = 5_000)
        d.begin(fromMs = 0, windowMs = 60_000, columns = 1000)
        for (t in 0L..10_000L step 1000) d.point(t, 1f)
        for (t in 30_000L..40_000L step 1000) d.point(t, 2f)
        d.end()
        val breaks = (0 until d.count).count { d.xs[it].isNaN() }
        assertEquals(1, breaks)
        assertEquals(1f, d.visibleMin)
        assertEquals(2f, d.visibleMax)
    }

    @Test
    fun `points outside the range keep their real position, extremes come from inside`() {
        val d = SeriesDecimator()
        d.begin(fromMs = 1000, windowMs = 1000, columns = 100)
        d.point(900, 50f)
        d.point(1500, 1f)
        d.point(2100, -50f)
        d.end()
        assertEquals(3, d.count)
        assertTrue(d.xs[0] < 0f && d.xs[2] > 1f)
        assertEquals(1f, d.visibleMin)
        assertEquals(1f, d.visibleMax)
    }

    @Test
    fun `many points are reduced to at most a min and a max per column, spikes kept`() {
        val d = SeriesDecimator()
        d.begin(fromMs = 0, windowMs = 10_000, columns = 50)
        for (t in 0L until 10_000L step 5) d.point(t, if (t == 5_000L) 99f else 1f)
        d.end()
        assertTrue(d.count <= 50 * 2 + 2, "${d.count}")
        assertTrue((0 until d.count).any { d.vs[it] == 99f })
    }

    // ---------------------------------------------------------------- history ranges

    @Test
    fun `history range stops after the first point past the end`() {
        val h = ValueHistory(retainMs = 60_000)
        for (t in 0L..10_000L step 1000) h.add(t, t.toDouble())
        val times = ArrayList<Long>()
        h.forEachPoint(3_000, object : ValueHistory.PointSink {
            override fun point(timeMs: Long, value: Float) { times += timeMs }
        }, toMs = 6_500)
        assertEquals(listOf(2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L), times)
        assertEquals(0L, h.oldestTimestampMs)
    }

    @Test
    fun `value near a time - raw sample within the gap, else bucket min and max, else nothing`() {
        val out = FloatArray(2)
        val h = ValueHistory(capacity = 4, retainMs = 60_000)
        assertFalse(h.valueNear(0, 5_000, out))
        // 2 readings per second for 10 s: only the last 4 stay raw, older ones only as buckets
        for (i in 0..20) h.add(i * 500L, i.toDouble())
        assertTrue(h.valueNear(9_900, 5_000, out))
        assertEquals(20f, out[0])
        assertEquals(out[0], out[1])
        assertTrue(h.valueNear(2_300, 5_000, out), "before the raw samples: the 2-3 s bucket")
        assertEquals(4f, out[0])
        assertEquals(5f, out[1])
        assertFalse(h.valueNear(30_000, 5_000, out), "nothing read near that time")
        assertNull(ValueHistory().oldestTimestampMs)
    }
}
