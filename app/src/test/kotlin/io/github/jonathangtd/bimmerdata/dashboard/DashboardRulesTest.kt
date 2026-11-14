/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Android Auto shows exactly the phone's cards ([DashboardRules]). */
class DashboardRulesTest {
    private fun card(id: String, m: String, col: Int = 0, row: Int = 0, title: String? = null, type: GaugeType = GaugeType.BigNumber) =
        Card(id = id, type = type, measurementIds = listOf("msv70/$m"), title = title, col = col, row = row)

    private val phone = Page(
        id = "p", name = "Sport", columns = 2, rows = 3,
        cards = listOf(card("a", "0x4807", 0, 0, "Régime", GaugeType.Dial), card("b", "0x58D0", 1, 0, "Couple"), card("c", "0x4402", 0, 1, "Huile")),
    )

    /** Car card -> (phone card id, page name, col, row) for easy checks. */
    private fun Dashboard.carView() = carPages.flatMap { p -> p.cards.map { it.phoneCardId to "${p.name} ${it.col},${it.row}" } }

    @Test
    fun `phone cards missing on Android Auto get a page named after the phone page, linked`() {
        val d = DashboardRules.syncCar(Dashboard(phonePages = listOf(phone)))
        assertEquals(listOf("Sport"), d.carPages.map { it.name })
        assertEquals(3 to 1, d.carPages[0].let { it.columns to it.rows })
        assertEquals(listOf("a" to "Sport 0,0", "b" to "Sport 1,0", "c" to "Sport 2,0"), d.carView())
        assertTrue(DashboardRules.follows(d))
        assertSame(d, DashboardRules.syncCar(d), "a layout that follows the rule is left as it is")
        assertTrue(d.carPages[0].cards.all { it.id !in setOf("a", "b", "c") }, "own ids")
    }

    @Test
    fun `legacy Android Auto cards are matched by content, extras dropped, contents refreshed`() {
        val car = Page(
            id = "k", name = "Car", columns = 4, rows = 1,
            cards = listOf(
                card("x", "0x58D0", 3, 0, "Old title"), // same type + values as b: linked, title from the phone
                card("y", "0x9999", 1, 0), // not on the phone: dropped
                card("z", "0x4807", 0, 0, "Régime", GaugeType.Dial), // exactly a
            ),
        )
        val d = DashboardRules.syncCar(Dashboard(phonePages = listOf(phone), carPages = listOf(car)))
        val cards = d.carPages[0].cards
        assertEquals(listOf("x", "z", "c2"), cards.map { if (it.phoneCardId == "c") "c2" else it.id })
        assertEquals("Couple", cards.first { it.id == "x" }.title)
        assertEquals(3 to 0, cards.first { it.id == "x" }.let { it.col to it.row }, "Android Auto keeps its place")
        assertEquals(listOf("b", "a", "c"), cards.map { it.phoneCardId })
        assertEquals("Car 1,0", d.carView().last().second, "the missing card takes the first free cell of that page")
    }

    @Test
    fun `phone edits follow - changed, removed, added, a full page grows then a new page opens`() {
        var d = DashboardRules.syncCar(Dashboard(phonePages = listOf(phone)))
        // changed on the phone
        d = DashboardRules.syncCar(d.updatePage(Target.Phone, "p") { it.replace(it.card("b")!!.copy(title = "Nm", warnAbove = 300.0)) })
        assertEquals("Nm" to 300.0, d.carPages[0].cards.first { it.phoneCardId == "b" }.let { it.title to it.warnAbove })
        // removed on the phone
        d = DashboardRules.syncCar(d.updatePage(Target.Phone, "p") { p -> p.copy(cards = p.cards.filterNot { it.id == "a" }) })
        assertEquals(listOf("b", "c"), d.carPages[0].cards.map { it.phoneCardId })
        // added: the 3 x 1 page grows to 3 x 2, up to 4 x 2 and 8 cards, then "Sport 2"
        val big = phone.copy(rows = 8, columns = 2, cards = phone.cards.drop(1) + (0 until 8).map { card("n$it", "0x50${it}0", it % 2, 2 + it / 2) })
        d = DashboardRules.syncCar(d.withPages(Target.Phone, listOf(big)))
        assertEquals(listOf("Sport", "Sport 2"), d.carPages.map { it.name })
        assertEquals(8, d.carPages[0].cards.size)
        assertEquals(4 to 2, d.carPages[0].let { it.columns to it.rows })
        assertEquals(2, d.carPages[1].cards.size)
        assertEquals(10, d.carPages.sumOf { it.cards.size })
        assertTrue(DashboardRules.follows(d))
        assertTrue(LayoutOk.valid(d))
    }

    @Test
    fun `an Android Auto page of another phone page is not filled with foreign cards`() {
        val other = Page(id = "q", name = "Temps", columns = 2, rows = 2, cards = listOf(card("t", "0x4300")))
        var d = DashboardRules.syncCar(Dashboard(phonePages = listOf(phone, other)))
        assertEquals(listOf("Sport", "Temps"), d.carPages.map { it.name })
        d = DashboardRules.syncCar(d.updatePage(Target.Phone, "q") { it.copy(cards = it.cards + card("u", "0x4301", 1, 0)) })
        assertEquals(listOf("t", "u"), d.carPages[1].cards.map { it.phoneCardId })
        assertEquals(3, d.carPages[0].cards.size)
    }

    @Test
    fun `write checks - place changes allowed, own cards and changed contents refused`() {
        val before = DashboardRules.syncCar(Dashboard(phonePages = listOf(phone)))
        val carPage = before.carPages[0]
        // moved, resized, regrouped on another Android Auto page: allowed
        val moved = before.copy(carPages = listOf(
            carPage.copy(columns = 4, rows = 2, cards = carPage.cards.drop(1).mapIndexed { i, c -> c.copy(col = 3 - i, rowSpan = 2) }),
            Page(id = "k2", name = "Other", columns = 1, rows = 1, cards = listOf(carPage.cards[0])),
        ))
        assertTrue(DashboardRules.carViolations(before, moved).isEmpty)
        // a phone card not on Android Auto yet (added with this write): allowed, placed by syncCar
        val added = moved.updatePage(Target.Phone, "p") { it.copy(cards = it.cards + card("d", "0x4300", 1, 1)) }
        assertTrue(DashboardRules.carViolations(before, added).isEmpty)
        // a phone card removed with its Android Auto card left in the request: allowed, it follows
        val removed = before.updatePage(Target.Phone, "p") { p -> p.copy(cards = p.cards.filterNot { it.id == "a" }) }
        assertTrue(DashboardRules.carViolations(before, removed).isEmpty)
        assertEquals(2, DashboardRules.syncCar(removed).carPages[0].cards.size)
        // a phone card edited with the stale Android Auto copy: allowed
        val edited = before.updatePage(Target.Phone, "p") { it.replace(it.card("b")!!.copy(title = "Nm")) }
        assertTrue(DashboardRules.carViolations(before, edited).isEmpty)

        // refused: a card of Android Auto's own, a card taken off Android Auto, a content changed there
        val own = before.updatePage(Target.Car, carPage.id) { it.copy(cards = it.cards + card("own", "0x9999", 0, 0).copy(row = 0, col = 0)) }
        assertEquals(listOf("own"), DashboardRules.carViolations(before, own).extra.map { it.id })
        val takenOff = before.updatePage(Target.Car, carPage.id) { it.copy(cards = it.cards.drop(1)) }
        assertEquals(listOf("a"), DashboardRules.carViolations(before, takenOff).missing.map { it.id })
        val changed = before.updatePage(Target.Car, carPage.id) { it.replace(it.cards[1].copy(warnAbove = 1.0)) }
        assertEquals(1, DashboardRules.carViolations(before, changed).changed.size)
        assertFalse(DashboardRules.carViolations(before, changed).isEmpty)
        // a second Android Auto copy of one phone card
        val twice = before.updatePage(Target.Car, carPage.id) { it.copy(columns = 4, cards = it.cards + it.cards[0].copy(id = "dup", col = 3)) }
        assertEquals(listOf("dup"), DashboardRules.carViolations(before, twice).extra.map { it.id })
    }

    @Test
    fun `place only - anything but the place of an Android Auto card is its phone card's`() {
        val c = card("x", "0x4402").copy(phoneCardId = "c")
        assertTrue(DashboardRules.placeOnly(c, c.copy(col = 2, row = 1, colSpan = 2)))
        assertFalse(DashboardRules.placeOnly(c, c.copy(title = "x")))
        assertFalse(DashboardRules.placeOnly(c, c.copy(phoneCardId = "a")))
        assertFalse(DashboardRules.placeOnly(c, c.copy(measurementIds = listOf("msv70/0x4300"))))
    }

    @Test
    fun `the mirror links its cards`() {
        val d = DashboardMirror.carFromPhone(Dashboard(phonePages = listOf(phone)))
        assertEquals(listOf("a", "b", "c"), d.carPages[0].cards.map { it.phoneCardId })
        assertSame(d, DashboardRules.syncCar(d))
    }

    /** Grid validity without the API (no overlap, inside the grid, 8 cards max). */
    private object LayoutOk {
        fun valid(d: Dashboard) = d.carPages.all { p ->
            p.cards.size <= GridLimits.Car.maxCards && p.columns <= 4 && p.rows <= 2 &&
                p.cards.all { GridRules.inBounds(p, it) } &&
                p.cards.indices.all { i -> (0 until i).none { j -> GridRules.overlaps(p.cards[i], p.cards[j]) } }
        }
    }
}
