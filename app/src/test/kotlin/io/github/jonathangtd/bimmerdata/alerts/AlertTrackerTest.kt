/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.alerts

import io.github.jonathangtd.bimmerdata.gauges.AlertCondition
import io.github.jonathangtd.bimmerdata.gauges.AlertLevel
import io.github.jonathangtd.bimmerdata.gauges.AlertSoundGate
import io.github.jonathangtd.bimmerdata.gauges.CardAlert
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** When the alert tone plays ([AlertTracker]). */
class AlertTrackerTest {
    private val oil = WatchedCard(
        "oil", listOf(CardAlert(condition = AlertCondition.Below, threshold = 70.0, level = AlertLevel.Danger, sound = true)), 1.0, listOf("e/oil"),
    )
    private val trims = WatchedCard(
        "trims", listOf(CardAlert(condition = AlertCondition.Above, threshold = 10.0, sound = true)), 0.5, listOf("e/b1", "e/b2"),
    )
    private val none = emptyList<String>()

    @Test
    fun `plays when the alert starts, not while it lasts, again after it ended and the interval passed`() {
        val t = AlertTracker(AlertSoundGate(10_000))
        var oilValue = 60.0
        fun step(now: Long) = t.update(listOf(oil), { id -> if (id == "e/oil") oilValue else Double.NaN }, now)
        assertEquals(listOf("oil"), step(0))
        assertEquals(none, step(1_000), "still cold")
        oilValue = 70.5
        assertEquals(none, step(2_000), "within the margin: still in alert")
        oilValue = 71.0
        assertEquals(none, step(3_000), "ended")
        oilValue = 69.0
        assertEquals(none, step(4_000), "started again, but less than 10 s after the last tone")
        oilValue = 72.0
        step(20_000)
        oilValue = 65.0
        assertEquals(listOf("oil"), step(21_000))
    }

    @Test
    fun `each value of a card is watched, one tone per card`() {
        val t = AlertTracker(AlertSoundGate(10_000))
        val values = mutableMapOf("e/b1" to 0.0, "e/b2" to 0.0)
        assertEquals(none, t.update(listOf(trims), { values[it] ?: Double.NaN }, 0))
        values["e/b2"] = 12.0
        assertEquals(listOf("trims"), t.update(listOf(trims), { values[it] ?: Double.NaN }, 1_000))
        values["e/b1"] = 12.0
        assertEquals(none, t.update(listOf(trims), { values[it] ?: Double.NaN }, 2_000), "rate limited")
    }

    @Test
    fun `no value is no alert, and a reset starts from scratch`() {
        val t = AlertTracker(AlertSoundGate(0))
        assertEquals(none, t.update(listOf(oil), { Double.NaN }, 0))
        assertEquals(listOf("oil"), t.update(listOf(oil), { 50.0 }, 1))
        t.reset()
        assertEquals(listOf("oil"), t.update(listOf(oil), { 50.0 }, 2), "after a reset the running alert counts as new")
    }
}
