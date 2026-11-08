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
import java.util.Locale
import kotlin.math.abs

class GaugeMathTest {
    private val fr = GaugeFormat(Locale.FRANCE)
    private val en = GaugeFormat(Locale.US)

    @Test
    fun `french numbers use a decimal comma and a real minus`() {
        assertEquals("104", fr.format(104.4, 0))
        assertEquals("2,3", fr.format(2.34, 1))
        assertEquals("+2,3", fr.format(2.34, 1, signed = true))
        assertEquals("−1,6", fr.format(-1.6, 1, signed = true))
        assertEquals("0,0", fr.format(-0.01, 1, signed = true), "no sign on a rounded zero")
        assertEquals("1,00", fr.format(0.999, 2))
        assertEquals("2350", fr.format(2350.0, 0), "no grouping")
        assertEquals("0.98", en.format(0.98, 2))
        assertEquals(GaugeFormat.NO_DATA, en.format(Double.NaN, 1))
    }

    @Test
    fun `units are shown the way drivers read them`() {
        assertEquals("tr/min", fr.displayUnit("rpm"))
        assertEquals("rpm", en.displayUnit("rpm"))
        assertEquals("°", fr.displayUnit("°CRK"))
        assertEquals("λ", fr.displayUnit("-", "Lambdawert vor Kat Bank 1"))
        assertEquals("", fr.displayUnit("-", "Status"))
        assertEquals("10,9°", fr.withUnit("10,9", "°"))
        assertEquals("+2,3 %", fr.withUnit("+2,3", "%"))
    }

    @Test
    fun `smoothing is frame rate independent and does not overshoot`() {
        fun run(stepMs: Long): Pair<Double, Double> {
            val s = SmoothValue(10.0)
            s.update(0.0, 1L)
            var t = 1L
            var max = 0.0
            while (t < 400_000_000L) {
                t += stepMs * 1_000_000L
                max = maxOf(max, s.update(100.0, t))
            }
            return s.value to max
        }
        val (at60, max60) = run(16)
        val (at120, max120) = run(8)
        val (atJank, maxJank) = run(50)
        assertTrue(abs(at60 - at120) < 1.0, "$at60 vs $at120")
        assertTrue(abs(at60 - atJank) < 2.0, "$at60 vs $atJank")
        assertTrue(maxOf(max60, max120, maxJank) <= 100.0 + 1e-9)
        assertTrue(at60 > 85.0, "close to the target after 0.4 s: $at60")
    }

    @Test
    fun `history keeps a window and the session extremes`() {
        val h = ValueHistory(capacity = 4)
        for (i in 0 until 6) h.add(1000L + i * 100, i.toDouble())
        assertEquals(4, h.size)
        assertEquals(2f, h.valueAt(0))
        assertEquals(0.0, h.sessionMin)
        assertEquals(5.0, h.sessionMax)
        assertEquals(2, h.firstIndexSince(1350))
        h.add(1100L, 99.0)
        assertEquals(4, h.size, "out of order samples are ignored")
    }

    @Test
    fun `card specs fall back to catalog metadata`() {
        val m = Measurement("msv70/0x5A81", "msv70", "Integrator Bank 1", "%", MeasurementSource.MeasurementTable(0x5A81, "STAT_INTEGRATOR_BANK1_WERT"))
        val card = Card(type = GaugeType.BipolarBar, measurementIds = listOf(m.id, "msv70/0xFFFF"))
        val spec = CardSpecs.build(card, mapOf(m.id to m), fr)
        assertEquals("Integrator Bank 1", spec.title)
        assertEquals(listOf("B1", "msv70/0xFFFF"), spec.channels.map { it.label })
        assertEquals(-25.0 to 25.0, spec.min to spec.max)
        assertEquals(1, spec.channels[0].decimals)
        assertTrue(spec.isWarning(Double.NaN).not())
    }
}
