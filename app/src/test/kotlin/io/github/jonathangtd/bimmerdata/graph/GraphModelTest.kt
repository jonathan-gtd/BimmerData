/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph

import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.profiles.ProfileJson
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Graphs of a profile: operations, colors that follow their value, storage in the profile. */
class GraphModelTest {
    private fun one(vararg ids: String) = Graphs.add(emptyList(), "Lambda", ids.toList(), id = "g1")

    @Test
    fun `new values take the first free colors, in order`() {
        val g = one("a/1", "a/2", "a/3").single()
        assertEquals(listOf(0, 1, 2), g.series.map { it.color })
        assertEquals("g1", g.id)
    }

    @Test
    fun `removing a value does not repaint the others, a new one takes the freed color`() {
        var list = one("a/1", "a/2", "a/3")
        list = Graphs.setSeries(list, "g1", listOf("a/1", "a/3"))
        assertEquals(listOf(0, 2), list.single().series.map { it.color })
        list = Graphs.setSeries(list, "g1", listOf("a/1", "a/3", "a/4"))
        assertEquals(listOf(0, 2, 1), list.single().series.map { it.color })
    }

    @Test
    fun `values keep their color and visibility when reordered, duplicates and blanks are dropped`() {
        var list = one("a/1", "a/2")
        list = Graphs.toggleHidden(list, "g1", "a/2")
        list = Graphs.setColor(list, "g1", "a/1", 5)
        list = Graphs.setSeries(list, "g1", listOf("a/2", "a/1", "a/1", " "))
        val s = list.single().series
        assertEquals(listOf("a/2", "a/1"), s.map { it.measurementId })
        assertEquals(listOf(1, 5), s.map { it.color })
        assertEquals(listOf(true, false), s.map { it.hidden })
        assertEquals(listOf("a/1"), list.single().visibleIds())
    }

    @Test
    fun `at most one value per palette color`() {
        val ids = (1..12).map { "a/$it" }
        val g = Graphs.add(emptyList(), "All", ids).single()
        assertEquals(GraphPalette.size, g.series.size)
        assertEquals((0 until GraphPalette.size).toList(), g.series.map { it.color })
    }

    @Test
    fun `add, rename, move, delete and limits`() {
        var list = Graphs.add(emptyList(), "  A  ", id = "a")
        list = Graphs.add(list, "B", id = "b")
        assertEquals(list, Graphs.add(list, "again", id = "a"), "an existing id is not added twice")
        list = Graphs.rename(list, "a", "   ")
        assertEquals("A", list[0].name, "a blank name is refused")
        list = Graphs.rename(list, "a", "Knock  sensors")
        assertEquals("Knock sensors", list[0].name)
        list = Graphs.move(list, 0, 1)
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals(list, Graphs.move(list, 0, 5))
        list = Graphs.delete(list, "b")
        assertEquals(listOf("a"), list.map { it.id })
        var many = emptyList<Graph>()
        repeat(Graphs.MAX_GRAPHS + 3) { many = Graphs.add(many, "G$it") }
        assertEquals(Graphs.MAX_GRAPHS, many.size)
    }

    @Test
    fun `window is clamped to the trend card limits, axes mode is stored`() {
        var list = one("a/1")
        list = Graphs.setWindow(list, "g1", 2)
        assertEquals(TrendWindow.MIN_SECONDS, list.single().windowSeconds)
        list = Graphs.setWindow(list, "g1", 99_999)
        assertEquals(TrendWindow.MAX_SECONDS, list.single().windowSeconds)
        list = Graphs.setWindow(list, "g1", null)
        assertEquals(null, list.single().windowSeconds)
        list = Graphs.setAxes(list, "g1", GraphAxes.Bands)
        assertEquals(GraphAxes.Bands, list.single().axes)
    }

    @Test
    fun `sanitize repairs graphs read from a file`() {
        val dirty = listOf(
            Graph(id = "x", name = "  ", series = listOf(GraphSeries("a/1", color = 13), GraphSeries("a/1"), GraphSeries("")), windowSeconds = 1),
            Graph(id = "x", name = "dup"),
            Graph(id = "", name = "no id"),
        )
        val clean = Graphs.sanitize(dirty)
        assertEquals(1, clean.size)
        assertEquals("?", clean[0].name)
        assertEquals(listOf(GraphSeries("a/1", color = 13 % GraphPalette.size)), clean[0].series)
        assertEquals(null, clean[0].windowSeconds)
    }

    @Test
    fun `card values in page order without duplicates`() {
        val d = Dashboard(
            phonePages = listOf(Page(name = "P", columns = 2, rows = 2, cards = listOf(card("m/1"), card("m/2", "m/1")))),
            carPages = listOf(Page(name = "C", columns = 2, rows = 2, cards = listOf(card("m/3")))),
        )
        assertEquals(listOf("m/1", "m/2", "m/3"), Graphs.cardMeasurements(d))
    }

    @Test
    fun `graphs live in the profile and survive dashboard writes, export and import`() {
        var set = Profiles.create(ProfileSet(), "P", nowMs = 1).first
        val id = set.activeId!!
        set = Profiles.updateGraphs(set, id) { Graphs.add(it, "Lambda", listOf("m/1", "m/2"), id = "g") }
        // the dashboard editor writes the whole layout: graphs are not touched
        set = Profiles.writeDashboard(set, id, Dashboard(phonePages = listOf(Page(name = "P", columns = 1, rows = 1))))
        assertEquals(listOf("m/1", "m/2"), set.active!!.graphs.single().measurementIds())
        // own file round trip
        val back = ProfileJson.decode(ProfileJson.encode(set))
        assertEquals(set.active!!.graphs, back.active!!.graphs)
        // shared file: graphs travel with the profile
        val exported = ProfileJson.export(set.active!!)
        assertTrue(exported.contains("\"graphs\""))
        val (imported, p) = ProfileJson.importInto(ProfileSet(), ProfileJson.parseExport(exported), 5, "x")
        assertEquals(set.active!!.graphs, p.graphs)
        assertEquals(p.id, imported.activeId)
        // duplicating a profile copies its graphs
        val dup = Profiles.duplicate(set, id, 9)!!.second
        assertEquals(set.active!!.graphs, dup.graphs)
        // an unknown profile is left alone
        assertEquals(set, Profiles.updateGraphs(set, "nope") { emptyList() })
    }

    @Test
    fun `profile files written before graphs existed still load`() {
        val old = """{"formatVersion":1,"profiles":[{"id":"p1","name":"Old","dashboard":{"phonePages":[]}}],"activeId":"p1"}"""
        val set = ProfileJson.decode(old)
        assertTrue(set.active!!.graphs.isEmpty())
        assertFalse(ProfileJson.encode(set).contains("graphs"), "no empty graphs list written")
    }

    private fun card(vararg ids: String) = Card(type = if (ids.size == 1) GaugeType.Trend else GaugeType.MultiValue, measurementIds = ids.toList())
}
