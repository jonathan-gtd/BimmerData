/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.AlertCondition
import io.github.jonathangtd.bimmerdata.gauges.AlertLevel
import io.github.jonathangtd.bimmerdata.gauges.CardAlert
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Alert rules in the layout: JSON, older thresholds, Android Auto copies. */
class CardAlertsModelTest {
    private val red = CardAlert(condition = AlertCondition.Below, threshold = 70.0, level = AlertLevel.Danger, sound = true)
    private fun card(id: String, alerts: List<CardAlert> = emptyList()) =
        Card(id = id, type = GaugeType.BigNumber, measurementIds = listOf("ecu/0x1"), alerts = alerts)

    @Test
    fun `alert rules go through JSON unchanged, without the older fields`() {
        val d = Dashboard(phonePages = listOf(Page(id = "p", name = "P", columns = 2, rows = 2, cards = listOf(card("a", listOf(red, CardAlert(enabled = false, condition = AlertCondition.Above, threshold = 120.5)))))))
        val text = DashboardJson.encode(d)
        assertFalse("warnBelow" in text || "warnAbove" in text)
        assertEquals(d.phonePages[0].cards[0].alerts, DashboardJson.decode(text).phonePages[0].cards[0].alerts)
    }

    @Test
    fun `older files - amber thresholds become orange rules without sound`() {
        val old = """{"phonePages":[{"id":"p","name":"P","columns":1,"rows":1,"cards":[{"id":"a","type":"Dial","measurementIds":["ecu/0x1"],"warnBelow":80,"warnAbove":130}]}]}"""
        val c = DashboardJson.decode(old).phonePages[0].cards[0]
        assertEquals(listOf(CardAlert(condition = AlertCondition.Below, threshold = 80.0), CardAlert(condition = AlertCondition.Above, threshold = 130.0)), c.alerts)
        assertNull(c.warnBelow)
        assertNull(c.warnAbove)
    }

    @Test
    fun `decoded files keep at most 4 rules`() {
        val rules = (1..6).joinToString(",") { """{"condition":"Above","threshold":$it}""" }
        val text = """{"phonePages":[{"id":"p","name":"P","columns":1,"rows":1,"cards":[{"id":"a","type":"Dial","measurementIds":["ecu/0x1"],"alerts":[$rules]}]}]}"""
        assertEquals(CardAlert.MAX_PER_CARD, DashboardJson.decode(text).phonePages[0].cards[0].alerts.size)
    }

    @Test
    fun `Android Auto cards take the alert rules of their phone card, and cannot have their own`() {
        val phone = Page(id = "p", name = "P", columns = 2, rows = 1, cards = listOf(card("a")))
        var d = DashboardRules.syncCar(Dashboard(phonePages = listOf(phone)))
        d = DashboardRules.syncCar(d.updatePage(Target.Phone, "p") { it.replace(it.card("a")!!.copy(alerts = listOf(red))) })
        assertEquals(listOf(red), d.carPages.single().cards.single().alerts, "follows the phone")
        val carPage = d.carPages.single()
        val own = d.updatePage(Target.Car, carPage.id) { it.replace(it.cards[0].copy(alerts = emptyList())) }
        assertEquals(1, DashboardRules.carViolations(d, own).changed.size, "alerts are content")
        assertFalse(DashboardRules.placeOnly(carPage.cards[0], carPage.cards[0].copy(alerts = emptyList())))
        assertTrue(DashboardRules.follows(d))
    }

    @Test
    fun `a layout without older fields is left as it is`() {
        val d = Dashboard(phonePages = listOf(Page(id = "p", name = "P", columns = 1, rows = 1, cards = listOf(card("a", listOf(red))))))
        assertSame(d, d.withLegacyAlerts())
        val legacy = d.updatePage(Target.Phone, "p") { it.replace(it.cards[0].copy(warnAbove = 100.0)) }
        assertEquals(2, legacy.withLegacyAlerts().phonePages[0].cards[0].alerts.size)
    }
}
