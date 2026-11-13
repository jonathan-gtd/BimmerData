/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.profiles

import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.graph.Graph
import io.github.jonathangtd.bimmerdata.graph.GraphSeries
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ComputedProfilesTest {
    private val power = ComputedValue("power", "Power", mapOf("torque" to "ecu/0x58D0", "rpm" to "ecu/0x4807"), "Power")

    private fun card(vararg ids: String, col: Int = 0) = Card(type = if (ids.size > 1) GaugeType.MultiValue else GaugeType.BigNumber, measurementIds = ids.toList(), col = col)

    private fun profile(computed: List<ComputedValue> = listOf(power)) = Profile(
        id = "p1", name = "Sport", carId = "car-1", ecus = listOf("ecu"),
        dashboard = Dashboard(phonePages = listOf(Page(id = "a", name = "Sport", columns = 2, rows = 6, cards = listOf(card("calc/power"), card("ecu/0x4807", col = 1))))),
        graphs = listOf(Graph(id = "g", name = "Power", series = listOf(GraphSeries("calc/power", 0), GraphSeries("ecu/0x4807", 1)))),
        computed = computed,
    )

    @Test
    fun `profile file round trip with and without computed values`() {
        val set = ProfileSet(profiles = listOf(profile()), activeId = "p1")
        val back = ProfileJson.decode(ProfileJson.encode(set))
        assertEquals(listOf(power), back.active!!.computed)
        assertTrue(ProfileJson.encode(set).contains("\"computed\""))

        // a file written before computed values existed still loads, with none
        val old = ProfileJson.decode("""{"formatVersion":1,"profiles":[{"id":"x","name":"Old","carId":"c"}],"activeId":"x"}""")
        assertEquals(emptyList<ComputedValue>(), old.active!!.computed)
        assertFalse(ProfileJson.encode(old).contains("computed"), "no empty field written")

        // export / import keep them
        val export = ProfileJson.parseExport(ProfileJson.export(profile()))
        assertEquals(listOf(power), export.computed)
        val (imported, p) = ProfileJson.importInto(ProfileSet(), export, 1L, "x", "car-2")
        assertEquals(listOf(power), p.computed)
        assertEquals(listOf(power), imported.active!!.computed)
        // an exported file without the field
        assertEquals(emptyList<ComputedValue>(), ProfileJson.parseExport("""{"kind":"bimmerdata-profile","name":"x"}""").computed)
    }

    @Test
    fun `stored lists are repaired`() {
        val dirty = listOf(
            power.copy(key = " Power "), power, // duplicate once lower-cased
            power.copy(key = "bad key!"),
            power.copy(key = "p2", label = "x".repeat(60)),
        )
        val clean = Computeds.sanitize(dirty)
        assertEquals(listOf("power", "p2"), clean.map { it.key })
        assertEquals(40, clean[1].label.length)
        assertEquals(Computeds.MAX, Computeds.sanitize((1..20).map { power.copy(key = "k$it") }).size)
    }

    @Test
    fun `validation and new keys`() {
        assertNull(Computeds.problem(power))
        assertNotNull(Computeds.problem(power.copy(kind = "Nope")))
        assertNotNull(Computeds.problem(power.copy(inputs = mapOf("torque" to "ecu/0x58D0"))), "rpm missing")
        assertNotNull(Computeds.problem(power.copy(inputs = power.inputs + ("rpm" to "0x4807"))), "not an id")
        assertNotNull(Computeds.problem(power.copy(inputs = power.inputs + ("rpm" to "calc/other"))), "no computed input")
        assertNotNull(Computeds.problem(power.copy(inputs = power.inputs + ("boost" to "ecu/0x1"))), "unknown role")
        assertNotNull(Computeds.problem(power.copy(key = "A B")))
        assertEquals("power", Computeds.newKey(emptyList(), io.github.jonathangtd.bimmerdata.live.ComputedKind.Power))
        assertEquals("power_2", Computeds.newKey(listOf(power), io.github.jonathangtd.bimmerdata.live.ComputedKind.Power))
    }

    @Test
    fun `a computed value costs its inputs in the profile's values`() {
        val d = profile().dashboard
        assertEquals(2, d.measurementIds().size)
        assertEquals(2, d.valueCount(listOf(power)), "calc/power = torque + rpm, rpm already on a card")
        assertEquals(2, d.valueCount(), "without the definitions it counts as itself")
        // a layout at the limit: adding the power (2 new inputs) is refused, adding a card of a value already read is not
        val full = Dashboard(phonePages = listOf(Page(id = "a", name = "a", columns = 4, rows = 8, cards = (0 until Dashboard.MAX_VALUES - 1).map { Card(type = GaugeType.BigNumber, measurementIds = listOf("ecu/0x${1000 + it}")) })))
        val withPower = full.copy(phonePages = listOf(full.phonePages[0].copy(cards = full.phonePages[0].cards + card("calc/power"))))
        assertTrue(Dashboard.exceedsValues(full, withPower, listOf(power)))
        assertFalse(Dashboard.exceedsValues(full, withPower), "unknown definition: counted as one value")
        val tooBig = ProfileJson.export(Profile(name = "x", dashboard = withPower, computed = listOf(power)))
        assertThrows<IllegalArgumentException> { ProfileJson.parseExport(tooBig) }
    }

    @Test
    fun `removing a computed value removes its cards and graph values`() {
        val p = profile()
        assertEquals(1 to 1, Computeds.uses(p, "calc/power"))
        val after = Computeds.remove(p, "power")
        assertTrue(after.computed.isEmpty())
        assertEquals(listOf(listOf("ecu/0x4807")), after.dashboard.phonePages[0].cards.map { it.measurementIds })
        assertEquals(listOf("ecu/0x4807"), after.graphs[0].series.map { it.measurementId })
        val set = ProfileSet(profiles = listOf(p), activeId = "p1")
        assertEquals(listOf(power, power.copy(key = "p2")), Profiles.updateComputed(set, "p1") { it + power.copy(key = "p2") }.active!!.computed)
        assertEquals(set, Profiles.updateComputed(set, "nope") { emptyList() })
        // duplicating a profile keeps them
        assertEquals(listOf(power), Profiles.duplicate(set, "p1", 2L)!!.second.computed)
    }
}
