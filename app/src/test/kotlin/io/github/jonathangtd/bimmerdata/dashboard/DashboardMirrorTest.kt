/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.api.LayoutOps
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Android Auto pages rebuilt from the phone pages: same cards, valid Android Auto grids. */
class DashboardMirrorTest {
    private fun card(id: String, col: Int, row: Int, vararg ids: String, type: GaugeType = GaugeType.BigNumber, cs: Int = 1) =
        Card(id = id, type = type, measurementIds = ids.toList(), col = col, row = row, colSpan = cs, title = id)

    @Test
    fun `every phone card goes to Android Auto, four per page, in reading order`() {
        val engine = Page(
            id = "p1", name = "Engine", columns = 2, rows = 6,
            cards = listOf(
                card("rpm", 0, 0, "msv70/1", type = GaugeType.Dial), card("load", 1, 0, "msv70/2", type = GaugeType.Dial),
                card("stft", 0, 2, "msv70/3", "msv70/4", type = GaugeType.BipolarBar, cs = 2),
                card("a", 0, 3, "msv70/5"), card("b", 1, 3, "msv70/6"),
                card("cyl", 0, 4, "msv70/7", "msv70/8", "msv70/9", type = GaugeType.MultiValue, cs = 2),
            ),
        )
        val info = Page(id = "p2", name = "Info", columns = 2, rows = 6, cards = listOf(card("oil", 1, 0, "msv70/10")))
        val empty = Page(id = "p3", name = "Empty", columns = 2, rows = 6)
        val old = Page(id = "c1", name = "Old", columns = 4, rows = 1, cards = listOf(card("x", 0, 0, "msv70/99")))
        val d = Dashboard(phonePages = listOf(engine, info, empty), carPages = listOf(old))
        assertFalse(DashboardMirror.matches(d))

        val m = DashboardMirror.carFromPhone(d)
        assertEquals(listOf("Engine 1/2", "Engine 2/2", "Info"), m.carPages.map { it.name })
        assertEquals(listOf("rpm", "load", "stft", "a"), m.carPages[0].cards.map { it.title })
        assertEquals(listOf("b", "cyl"), m.carPages[1].cards.map { it.title })
        assertEquals(2, m.carPages[1].columns)
        assertTrue(m.carPages.flatMap { it.cards }.all { it.colSpan == 1 && it.rowSpan == 1 && it.row == 0 })
        assertEquals(d.phonePages, m.phonePages)
        assertTrue(DashboardMirror.matches(m))
        // a valid layout: Android Auto limits, ids unique across the dashboard
        assertEquals(emptyList<Any>(), LayoutOps.validate(m))
    }
}
