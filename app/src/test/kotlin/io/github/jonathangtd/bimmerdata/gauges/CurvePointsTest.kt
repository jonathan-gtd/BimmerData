/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.CardSpecs
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CurvePointsTest {
    private fun history(vararg points: Pair<Long, Double>) = ValueHistory().apply { points.forEach { (t, v) -> add(t, v) } }

    @Test
    fun `each reading of Y is paired with the X reading nearest in time`() {
        val rpm = history(1_000L to 2000.0, 1_300L to 2500.0, 1_600L to 3000.0, 9_000L to 6000.0)
        val power = history(1_050L to 50.0, 1_550L to 90.0, 5_000L to 120.0, 9_100L to 200.0)
        val c = CurvePoints()
        c.collect(rpm, power, nowMs = 10_000L, windowMs = 30_000L)
        // 5 000: no engine speed within 2 s; the others take the nearest one
        assertEquals(3, c.count)
        assertEquals(listOf(2000f, 3000f, 6000f), (0 until c.count).map { c.xs[it] })
        assertEquals(listOf(50f, 90f, 200f), (0 until c.count).map { c.ys[it] })
        assertTrue(c.ages[0] > c.ages[2], "older points fade more")
        assertEquals(0.03f, c.ages[2], 1e-4f)
    }

    @Test
    fun `only the window, nothing without data, grows past its first size`() {
        val x = ValueHistory()
        val y = ValueHistory()
        for (i in 0 until 500) {
            x.add(i * 100L, i.toDouble())
            y.add(i * 100L + 10, 2.0 * i)
        }
        val c = CurvePoints()
        c.collect(x, y, nowMs = 50_000L, windowMs = 10_000L)
        assertEquals(100, c.count, "readings of the last 10 s")
        assertEquals(400f, c.xs[0])
        assertEquals(800f, c.ys[0])
        c.collect(x, y, nowMs = 50_000L, windowMs = 60_000L)
        assertEquals(500, c.count)
        c.collect(null, y, nowMs = 50_000L, windowMs = 60_000L)
        assertEquals(0, c.count)
    }

    @Test
    fun `a curve card takes its axis first, has no alert and a title saying what against what`() {
        val rpm = Measurement("ecu/0x4807", "ecu", "Engine speed", "rpm", MeasurementSource.MeasurementTable(0x4807, "STAT_RPM_WERT"))
        val oil = Measurement("ecu/0x4402", "ecu", "Oil", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OIL_WERT"))
        assertEquals(2..4, Card.measurementRange(GaugeType.Curve))
        val card = Card(type = GaugeType.Curve, measurementIds = listOf(rpm.id, oil.id), windowSeconds = 30, alerts = listOf(CardAlert(condition = AlertCondition.Above, threshold = 1.0)))
        val spec = CardSpecs.build(card, mapOf(rpm.id to rpm, oil.id to oil), GaugeFormat(java.util.Locale.ROOT))
        assertEquals("Oil / Engine speed", spec.title)
        assertTrue(spec.alerts.isEmpty())
        assertEquals(30_000L, spec.windowMs)
    }
}
