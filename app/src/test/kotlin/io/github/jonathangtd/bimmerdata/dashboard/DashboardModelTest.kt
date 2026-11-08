/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DashboardModelTest {
    private fun card(id: String, col: Int, row: Int, cs: Int = 1, rs: Int = 1) =
        Card(id = id, type = GaugeType.Trend, measurementIds = listOf("msv70/0x4807"), col = col, row = row, colSpan = cs, rowSpan = rs)

    private fun page(vararg cards: Card, columns: Int = 2, rows: Int = 6) = Page(id = "p", name = "P", columns = columns, rows = rows, cards = cards.toList())

    // ---- default layout ----

    @Test
    fun `default dashboard is valid and follows mockup A`() {
        val d = DefaultDashboard.create()
        assertEquals(3, d.phonePages.size)
        assertEquals(3, d.carPages.size)
        (d.phonePages + d.carPages).forEach { assertTrue(GridRules.isValid(it), "invalid page ${it.name}") }
        d.carPages.forEach { assertTrue(it.cards.size <= GridLimits.Car.maxCards) }
        val engine = d.phonePages[0]
        assertEquals(listOf(GaugeType.Dial, GaugeType.Dial, GaugeType.BipolarBar), engine.cards.take(3).map { it.type })
        assertEquals(listOf(Msv70.STFT_B1, Msv70.STFT_B2), engine.cards[2].measurementIds)
        assertEquals(listOf(Msv70.OIL_TEMP, Msv70.COOLANT_TEMP, Msv70.STFT_B1 + "|" + Msv70.STFT_B2, Msv70.RPM),
            d.carPages[0].cards.map { it.measurementIds.joinToString("|") })
        assertTrue(d.measurementIds().containsAll(listOf(Msv70.LAMBDA_B1, Msv70.LAMBDA_B2, Msv70.IGNITION_CYL1, Msv70.LOAD, Msv70.INTAKE_AIR)))
    }

    // ---- JSON ----

    @Test
    fun `json round trip keeps everything`() {
        val d = DefaultDashboard.create(DefaultLabels(oil = "Huile", pageEngine = "Moteur"))
        val text = DashboardJson.encode(d)
        val back = DashboardJson.decode(text)
        assertEquals(d, back)
        assertTrue(text.contains("\"Huile\""))
    }

    @Test
    fun `import ignores unknown keys and repairs bad layouts`() {
        val text = """
            {"formatVersion":1,"sharedBy":"someone",
             "phonePages":[{"id":"a","name":"","columns":9,"rows":2,"cards":[
                {"id":"x","type":"Dial","measurementIds":["msv70/0x4402"],"col":0,"row":0,"colSpan":5},
                {"id":"x","type":"Trend","measurementIds":["msv70/0x4807"],"col":0,"row":0},
                {"id":"e","type":"Trend","measurementIds":[],"col":1,"row":1}]}],
             "carPages":[{"id":"a","name":"Car","columns":4,"rows":5,"cards":[]}]}
        """.trimIndent()
        val d = DashboardJson.decode(text)
        val p = d.phonePages.single()
        assertEquals(GridLimits.Phone.maxColumns, p.columns)
        assertTrue(p.name.isNotBlank())
        assertTrue(GridRules.isValid(p))
        assertEquals(2, p.cards.size, "card without measurement dropped, overlapping one moved")
        assertEquals(2, p.cards.map { it.id }.toSet().size, "duplicate ids renamed")
        assertEquals(GridLimits.Car.maxRows, d.carPages.single().rows)
        assertTrue(d.carPages.single().id != p.id)
    }

    @Test
    fun `import rejects garbage and newer formats`() {
        assertThrows<IllegalArgumentException> { DashboardJson.decode("not json") }
        assertThrows<IllegalArgumentException> { DashboardJson.decode("""{"formatVersion":99,"phonePages":[{"name":"a","columns":1,"rows":1}]}""") }
        assertThrows<IllegalArgumentException> { DashboardJson.decode("""{"formatVersion":1}""") }
    }

    // ---- move ----

    @Test
    fun `move to a free cell`() {
        val p = page(card("a", 0, 0), card("b", 1, 0))
        val moved = GridRules.move(p, "a", 0, 3)!!
        assertEquals(0 to 3, moved.card("a")!!.let { it.col to it.row })
    }

    @Test
    fun `move onto a same size card swaps them`() {
        val p = page(card("a", 0, 4), card("b", 1, 5))
        val swapped = GridRules.move(p, "a", 1, 5)!!
        assertEquals(1 to 5, swapped.card("a")!!.let { it.col to it.row })
        assertEquals(0 to 4, swapped.card("b")!!.let { it.col to it.row })
        assertTrue(GridRules.isValid(swapped))
    }

    @Test
    fun `move refused when the swap would not fit`() {
        // A 2x2 card dropped on a 1x1 next to another card: the small one cannot take its place without overlap.
        val p = page(card("big", 0, 0, 2, 2), card("s1", 0, 2), card("s2", 1, 2), card("s3", 0, 3))
        assertNull(GridRules.move(p, "big", 0, 2))
        assertFalse(GridRules.canMove(p, "big", 0, 2))
    }

    @Test
    fun `move is clamped inside the grid`() {
        val p = page(card("a", 0, 0, 2, 2))
        val moved = GridRules.move(p, "a", 5, 9)!!
        assertEquals(0 to 4, moved.card("a")!!.let { it.col to it.row })
    }

    // ---- resize ----

    @Test
    fun `resize grows into free cells and is refused over a card`() {
        val p = page(card("a", 0, 0), card("b", 1, 1))
        val wide = GridRules.resize(p, "a", 2, 1)!!
        assertEquals(2, wide.card("a")!!.colSpan)
        assertNull(GridRules.resize(p, "a", 2, 2), "would cover b")
        val clamped = GridRules.resize(p, "a", 1, 99)!!
        assertEquals(6, clamped.card("a")!!.rowSpan, "clamped to the grid edge")
        val shrunk = GridRules.resize(wide, "a", 0, 0)!!
        assertEquals(1 to 1, shrunk.card("a")!!.let { it.colSpan to it.rowSpan })
    }

    // ---- add ----

    @Test
    fun `add uses the requested cell, then the first free slot, then 1x1`() {
        val p = page(card("a", 0, 0))
        val atCell = GridRules.add(p, card("n", 1, 3, 1, 2), GridLimits.Phone)!!
        assertEquals(1 to 3, atCell.card("n")!!.let { it.col to it.row })
        val elsewhere = GridRules.add(p, card("n", 0, 0, 1, 2), GridLimits.Phone)!!
        assertTrue(GridRules.isValid(elsewhere))
        assertEquals(1 to 0, elsewhere.card("n")!!.let { it.col to it.row })
        val full = page(card("a", 0, 0, 2, 5), card("b", 0, 5), columns = 2, rows = 6)
        val small = GridRules.add(full, card("n", 0, 0, 2, 2), GridLimits.Phone)!!
        assertEquals(Triple(1, 5, 1), small.card("n")!!.let { Triple(it.col, it.row, it.colSpan) })
        val reallyFull = small
        assertNull(GridRules.add(reallyFull, card("m", 0, 0), GridLimits.Phone))
    }

    @Test
    fun `car pages hold at most four cards`() {
        val p = page(card("a", 0, 0), card("b", 1, 0), card("c", 2, 0), card("d", 3, 0), columns = 4, rows = 2)
        assertNull(GridRules.add(p, card("e", 0, 1), GridLimits.Car))
        assertNotNull(GridRules.add(p, card("e", 0, 1), GridLimits.Phone))
    }

    // ---- grid size ----

    @Test
    fun `shrinking the grid moves or shrinks cards, refuses when impossible`() {
        val p = page(card("a", 0, 0, 2, 2), card("b", 0, 5), columns = 2, rows = 6)
        val smaller = GridRules.setGrid(p, 2, 3, GridLimits.Phone)!!
        assertTrue(GridRules.isValid(smaller))
        assertEquals(listOf("a", "b"), smaller.cards.map { it.id }, "order kept")
        assertEquals(0 to 2, smaller.card("b")!!.let { it.col to it.row })
        val tooSmall = page(card("a", 0, 0), card("b", 1, 0), card("c", 0, 1))
        assertNull(GridRules.setGrid(tooSmall, 1, 2, GridLimits.Phone))
        val limited = GridRules.setGrid(page(), 9, 9, GridLimits.Car)!!
        assertEquals(4 to 2, limited.columns to limited.rows)
    }

    // ---- presets ----

    @Test
    fun `presets are valid and respect the car limits`() {
        (LayoutPresets.phone + LayoutPresets.car).forEach { preset ->
            val cards = preset.slots.mapIndexed { i, s -> card("c$i", s.col, s.row, s.colSpan, s.rowSpan) }
            val p = Page(name = preset.id, columns = preset.columns, rows = preset.rows, cards = cards)
            assertTrue(GridRules.isValid(p), "preset ${preset.id}")
            val limits = GridLimits.of(preset.target)
            assertTrue(preset.columns <= limits.maxColumns && preset.rows <= limits.maxRows && preset.slots.size <= limits.maxCards, preset.id)
        }
    }

    @Test
    fun `applying a preset keeps reading order and drops extra cards`() {
        val p = DefaultDashboard.create().phonePages[0]
        val bigLeft = LayoutPresets.phone.first { it.id == "big_left" }
        assertEquals(p.cards.size - 3, GridRules.droppedByPreset(p, bigLeft))
        val applied = GridRules.applyPreset(p, bigLeft)
        assertEquals(3, applied.cards.size)
        assertEquals(GridRules.readingOrder(p).take(3).map { it.id }, applied.cards.map { it.id })
        assertTrue(GridRules.isValid(applied))
        assertEquals(1 to 2, applied.cards[0].let { it.colSpan to it.rowSpan })
    }

    // ---- page helpers ----

    @Test
    fun `dashboard page helpers`() {
        val d = DefaultDashboard.create()
        val id = d.carPages[1].id
        val renamed = d.updatePage(Target.Car, id) { it.copy(name = "X") }
        assertEquals("X", renamed.pages(Target.Car)[1].name)
        assertEquals(d.phonePages, renamed.phonePages)
        assertEquals(Card.measurementRange(GaugeType.MultiValue), 2..3)
    }
}
