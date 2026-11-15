/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import io.github.jonathangtd.bimmerdata.gauges.AlertCondition.Above
import io.github.jonathangtd.bimmerdata.gauges.AlertCondition.Below
import io.github.jonathangtd.bimmerdata.gauges.AlertLevel.Danger
import io.github.jonathangtd.bimmerdata.gauges.AlertLevel.Warning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Card alert rules ([Alerts]): below / above, hysteresis, levels, sounds, older thresholds. */
class AlertsTest {
    private val cold = CardAlert(condition = Below, threshold = 70.0, level = Danger)
    private val hot = CardAlert(condition = Above, threshold = 120.0, level = Warning, sound = true)

    /** Level after each of [values], fed one after the other. */
    private fun run(alerts: List<CardAlert>, margin: Double, vararg values: Double): List<AlertLevel?> {
        var mask = 0
        return values.map { v ->
            mask = Alerts.step(alerts, v, mask, margin)
            Alerts.levelOf(alerts, mask)
        }
    }

    @Test
    fun `below and above fire past the threshold, not on it`() {
        assertEquals(listOf(Danger, null, null, null, Warning), run(listOf(cold, hot), 0.0, 69.9, 70.0, 95.0, 120.0, 120.1))
    }

    @Test
    fun `hysteresis - an alert ends only once the value is back past the threshold by the margin`() {
        // oil warming up around 70 with a 1 degree margin: no flicker
        assertEquals(listOf(Danger, Danger, Danger, Danger, null, null, Danger), run(listOf(cold), 1.0, 69.0, 70.2, 69.8, 70.9, 71.0, 70.5, 69.9))
        assertEquals(listOf(Warning, Warning, null), run(listOf(hot), 2.0, 121.0, 118.5, 118.0))
    }

    @Test
    fun `disabled rules, no value and non finite thresholds never fire`() {
        assertEquals(listOf<AlertLevel?>(null), run(listOf(cold.copy(enabled = false)), 0.0, 10.0))
        assertEquals(listOf(Danger, null), run(listOf(cold), 0.0, 10.0, Double.NaN), "no data ends the alert")
        assertEquals(listOf<AlertLevel?>(null), run(listOf(cold.copy(threshold = Double.NaN)), 0.0, 10.0))
    }

    @Test
    fun `red wins over orange`() {
        val rules = listOf(CardAlert(condition = Above, threshold = 100.0), CardAlert(condition = Above, threshold = 110.0, level = Danger))
        assertEquals(listOf(Warning, Danger, Warning), run(rules, 0.0, 105.0, 115.0, 105.0))
        assertEquals(Danger, Alerts.max(Warning, Danger))
        assertEquals(Warning, Alerts.max(null, Warning))
        assertNull(Alerts.max(null, null))
        assertEquals(Danger, Alerts.levelFor(rules, 111.0))
    }

    @Test
    fun `a sound starts with a rule that has one, once`() {
        val rules = listOf(cold, hot)
        val m1 = Alerts.step(rules, 121.0, 0, 0.0)
        assertTrue(Alerts.soundStarts(rules, 0, m1))
        assertFalse(Alerts.soundStarts(rules, m1, Alerts.step(rules, 122.0, m1, 0.0)), "still the same alert")
        assertFalse(Alerts.soundStarts(rules, 0, Alerts.step(rules, 60.0, 0, 0.0)), "the cold rule has no sound")
    }

    @Test
    fun `margin - one step of the last digit or half a percent of the scale`() {
        assertEquals(1.0, Alerts.margin(40.0, 140.0, 0), 1e-9)
        assertEquals(0.5, Alerts.margin(0.0, 100.0, 1), 1e-9)
        assertEquals(35.0, Alerts.margin(0.0, 7000.0, 0), 1e-9)
        assertEquals(0.01, Alerts.margin(0.8, 1.2, 2), 1e-9)
    }

    @Test
    fun `older amber thresholds become orange rules without sound`() {
        val out = Alerts.withLegacy(emptyList(), 80.0, 130.0)
        assertEquals(listOf(CardAlert(condition = Below, threshold = 80.0), CardAlert(condition = Above, threshold = 130.0)), out)
        // replaces the orange rule of the same condition instead of piling up
        assertEquals(listOf(CardAlert(condition = Below, threshold = 80.0), CardAlert(condition = Above, threshold = 125.0)), Alerts.withLegacy(out, null, 125.0))
        // a red or sounding rule is kept, the threshold comes next to it
        assertEquals(listOf(hot, CardAlert(condition = Above, threshold = 100.0)), Alerts.withLegacy(listOf(hot), null, 100.0))
    }

    @Test
    fun `problems - too many rules, threshold not finite`() {
        assertNull(Alerts.problem(List(4) { cold }))
        assertNotNull(Alerts.problem(List(5) { cold }))
        assertNotNull(Alerts.problem(listOf(cold.copy(threshold = Double.POSITIVE_INFINITY))))
    }

    @Test
    fun `sound gate - at most once per interval per card`() {
        val g = AlertSoundGate(10_000)
        assertTrue(g.shouldPlay("a", 0))
        assertFalse(g.shouldPlay("a", 9_999))
        assertTrue(g.shouldPlay("b", 5_000), "another card")
        assertTrue(g.shouldPlay("a", 10_000))
    }
}
