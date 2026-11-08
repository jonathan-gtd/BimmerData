/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import io.github.jonathangtd.bimmerdata.api.LayoutOps
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.CardSpecs
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardJson
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.dashboard.longestTrendWindowMs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class TrendWindowTest {
    private class Points : ValueHistory.PointSink {
        val t = ArrayList<Long>()
        val v = ArrayList<Float>()
        override fun point(timeMs: Long, value: Float) {
            t += timeMs; v += value
        }
    }

    @Test
    fun `windows - default, bounds, quick cycle and labels`() {
        assertEquals(45_000L, TrendWindow.ms(null))
        assertEquals(5_000L, TrendWindow.ms(1))
        assertEquals(3_600_000L, TrendWindow.ms(99_999))
        assertEquals(60, TrendWindow.next(30))
        assertEquals(60, TrendWindow.next(null)) // 45 s → 1 min
        assertEquals(10, TrendWindow.next(1800))
        assertEquals("30 s", TrendWindow.duration(30))
        assertEquals("5 min", TrendWindow.duration(300))
        assertEquals("1 min 30 s", TrendWindow.duration(90))
        assertEquals("−30 s", TrendWindow.tickLabel(30_000))
        assertEquals(10_000L, TrendWindow.gridStepMs(30_000, 3))
        assertEquals(120_000L, TrendWindow.gridStepMs(600_000, 5))
        assertTrue(TrendWindow.isValid(5) && TrendWindow.isValid(3600))
        assertFalse(TrendWindow.isValid(4) || TrendWindow.isValid(3601))
    }

    @Test
    fun `layout JSON keeps the window and old layouts keep the 45 s default`() {
        val card = Card(id = "c1", type = GaugeType.Trend, measurementIds = listOf("dme/0x4402"), windowSeconds = 600)
        val d = Dashboard(phonePages = listOf(Page(id = "p", name = "P", columns = 2, rows = 6, cards = listOf(card))))
        val back = DashboardJson.decode(DashboardJson.encode(d))
        assertEquals(600, back.phonePages[0].cards[0].windowSeconds)
        val old = """{"formatVersion":1,"phonePages":[{"id":"p","name":"P","columns":2,"rows":6,"cards":[
            {"id":"c1","type":"Trend","measurementIds":["dme/0x4402"]}]}],"carPages":[]}"""
        val oldCard = DashboardJson.decode(old).phonePages[0].cards[0]
        assertNull(oldCard.windowSeconds)
        assertEquals(TrendWindow.DEFAULT_MS, CardSpecs.build(oldCard, emptyMap(), GaugeFormat(Locale.US)).windowMs)
        assertEquals(600_000L, CardSpecs.build(card, emptyMap(), GaugeFormat(Locale.US)).windowMs)
        assertEquals(600_000L, longestTrendWindowMs(d.phonePages))
        assertEquals(TrendWindow.DEFAULT_MS, longestTrendWindowMs(emptyList()))
    }

    @Test
    fun `API validation refuses windows out of range`() {
        val ok = Card(type = GaugeType.Trend, measurementIds = listOf("dme/0x4402"), windowSeconds = 300)
        assertTrue(LayoutOps.cardIssues(ok).isEmpty())
        val bad = ok.copy(windowSeconds = 2)
        assertEquals(listOf("card.windowSeconds" to "invalid_window"), LayoutOps.cardIssues(bad).map { it.path to it.code })
    }

    @Test
    fun `short windows use the raw samples`() {
        val h = ValueHistory(capacity = 100)
        for (i in 0 until 50) h.add(10_000L + i * 100, i.toDouble())
        assertTrue(h.rawCovers(12_000))
        val p = Points()
        h.forEachPoint(12_000, p)
        assertEquals(11_900L, p.t.first()) // entry point just before the window
        assertEquals(31, p.t.size) // 30 in the window + the entry point
    }

    @Test
    fun `long windows use min max buckets in the order they happened`() {
        // 20 reads/s for 10 min, raw ring of 1000 samples (50 s): buckets must cover the rest.
        val h = ValueHistory(capacity = 1000, bucketMs = 1000, retainMs = 600_000)
        val t0 = 1_000_000L
        for (i in 0 until 12_000) {
            val t = t0 + i * 50L
            // a short spike up at 100 s and down at 200 s, flat 10 elsewhere
            val v = when (i) { 2000 -> 99.0; 4000 -> -50.0; else -> 10.0 }
            h.add(t, v)
        }
        val now = t0 + 12_000 * 50L
        assertFalse(h.rawCovers(now - 600_000))
        assertTrue(h.bucketCount in 590..602, "buckets ${h.bucketCount}")
        val p = Points()
        h.forEachPoint(now - 600_000, p)
        assertEquals(99f, p.v.max())
        assertEquals(-50f, p.v.min())
        assertTrue(p.t.zipWithNext().all { (a, b) -> a <= b }, "time ordered")
        assertTrue(p.t.size <= 2 * 602)
        assertTrue(h.memoryBytes < 40_000, "memory ${h.memoryBytes}")
    }

    @Test
    fun `retention follows the longest window and keeps the newest buckets`() {
        val h = ValueHistory(capacity = 10, bucketMs = 1000, retainMs = 60_000)
        for (i in 0 until 300) h.add(i * 1000L, i.toDouble())
        assertTrue(h.bucketCount <= 62)
        h.retainMs = 10_000
        assertTrue(h.bucketCount <= 12)
        val p = Points()
        h.forEachPoint(0, p)
        assertEquals(299f, p.v.last())
        h.retainMs = 1_800_000
        for (i in 300 until 2300) h.add(i * 1000L, i.toDouble())
        assertTrue(h.bucketCount in 1800..1802, "buckets ${h.bucketCount}")
    }

    @Test
    fun `decimation keeps each pixel column's extremes and passes few points through`() {
        val d = TrendDecimator()
        d.begin(0, 1000, 10)
        for (i in 0 until 5) d.point(i * 200L, i.toFloat())
        d.end()
        assertEquals(5, d.count)

        d.begin(0, 10_000, 10) // 10 columns of 1 s
        for (i in 0 until 10_000) {
            val v = when (i) { 3_500 -> 80f; 3_600 -> -80f; else -> (i % 7).toFloat() }
            d.point(i.toLong(), v)
        }
        d.end()
        assertTrue(d.count <= 20, "count ${d.count}")
        val vs = d.vs.copyOf(d.count)
        assertEquals(80f, vs.max())
        assertEquals(-80f, vs.min())
        val iMax = vs.indexOfFirst { it == 80f }
        val iMin = vs.indexOfFirst { it == -80f }
        assertTrue(iMax < iMin, "spike up came first")
        val xs = d.xs.copyOf(d.count)
        assertTrue(xs.all { it in 0f..1f })
        assertTrue(xs.toList().zipWithNext().all { (a, b) -> a <= b })
    }
}
