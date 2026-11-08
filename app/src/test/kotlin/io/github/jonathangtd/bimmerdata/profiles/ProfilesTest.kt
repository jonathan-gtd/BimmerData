/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.profiles

import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardJson
import io.github.jonathangtd.bimmerdata.dashboard.Msv70Example
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Profiles: model operations, automatic choice by car, export / import, persistence. */
class ProfilesTest {
    private val layout = Msv70Example.create()

    private fun sample(): ProfileSet {
        var set = ProfileSet()
        set = Profiles.create(set, "E90 330i · Lambda", ProfileVehicle("E90", "330i", "AB12345"), listOf("MSV70.prg"), nowMs = 100, dashboard = layout).first
        set = Profiles.create(set, "120d E87", ProfileVehicle("E87", "120d", "ZZ99999"), listOf("dde"), nowMs = 200).first
        set = Profiles.create(set, "Mini R50", ProfileVehicle("R50"), listOf("kms2000"), nowMs = 300).first
        return set
    }

    @Test
    fun `create makes the profile active, normalizes ECUs and keeps names unique`() {
        val set = sample()
        assertEquals(3, set.profiles.size)
        assertEquals("Mini R50", set.active?.name)
        val lambda = set.profiles[0]
        assertEquals(listOf("msv70"), lambda.ecus)
        assertEquals("E90 330i", lambda.vehicle.label)
        assertNull(lambda.dashboard.name, "the profile name is not duplicated in its layout")
        assertEquals(layout.copy(name = "E90 330i · Lambda"), set.copy(activeId = lambda.id).activeDashboard())
        val (twice, p) = Profiles.create(set, " e90 330i ·  LAMBDA ", nowMs = 400)
        assertEquals("e90 330i · LAMBDA (2)", p.name)
        assertEquals(p.id, twice.activeId)
        assertEquals(listOf("msv70", "egs"), Profiles.normalizeEcus(listOf(" MSV70.PRG", "msv70", "egs.grp", "", "../x")))
    }

    @Test
    fun `duplicate copies car, ECUs and pages into a new active profile`() {
        val set = sample()
        val src = set.profiles[0]
        val (after, copy) = Profiles.duplicate(set, src.id, nowMs = 500, name = "E90 330i · Knock")!!
        assertEquals(copy.id, after.activeId)
        assertNotEquals(src.id, copy.id)
        assertEquals("E90 330i · Knock", copy.name)
        assertEquals(src.vehicle, copy.vehicle)
        assertEquals(src.ecus, copy.ecus)
        assertEquals(src.dashboard, copy.dashboard)
        assertEquals(1, after.profiles.indexOf(copy), "right after its source")
        // editing the copy leaves the source alone
        val edited = Profiles.writeDashboard(after, copy.id, Dashboard(name = "E90 330i · Knock"))
        assertEquals(layout, edited.profile(src.id)!!.dashboard)
        assertTrue(edited.profile(copy.id)!!.dashboard.phonePages.isEmpty())
    }

    @Test
    fun `writes go to the given profile, a new name renames it, delete picks the last used`() {
        var set = sample()
        val lambda = set.profiles[0]
        // the editor of "Lambda" saves after another profile became active: the save stays in Lambda
        set = Profiles.writeDashboard(set, lambda.id, Dashboard(name = "Lambda B1"))
        assertEquals("Lambda B1", set.profile(lambda.id)!!.name)
        assertTrue(set.profile(lambda.id)!!.dashboard.phonePages.isEmpty())
        assertEquals("Mini R50", set.active?.name)
        set = Profiles.edit(set, lambda.id, name = "Mini R50", vehicle = ProfileVehicle(" E90 ", "", null), ecus = listOf("MSV70", "EGS"))
        assertEquals("Mini R50 (2)", set.profile(lambda.id)!!.name, "names stay unique")
        assertEquals(ProfileVehicle("E90", null, null), set.profile(lambda.id)!!.vehicle)
        assertEquals(listOf("msv70", "egs"), set.profile(lambda.id)!!.ecus)
        // delete the active one: the most recently used of the others becomes active
        set = Profiles.activate(set, lambda.id, nowMs = 900)
        set = Profiles.delete(set, lambda.id)
        assertEquals("Mini R50", set.active?.name)
        set = Profiles.delete(Profiles.delete(set, set.activeId!!), set.profiles.first().id)
        assertTrue(set.profiles.isEmpty())
        assertNull(set.activeId)
    }

    @Test
    fun `a write without profile creates one only for a layout with content`() {
        val empty = ProfileSet()
        assertEquals(empty, Profiles.writeActiveDashboard(empty, Dashboard(), "My profile", 1))
        val created = Profiles.writeActiveDashboard(empty, layout, "My profile", 1)
        assertEquals("My profile", created.active?.name)
        assertEquals(layout, created.active?.dashboard)
        assertEquals("330i", Profiles.writeActiveDashboard(empty, Dashboard(name = "330i"), "My profile", 1).active?.name)
    }

    @Test
    fun `the identified car picks its last used profile, never when the active one fits`() {
        var set = sample()
        assertNull(Profiles.autoSelect(set, "NOPE000"), "car without profile: nothing to do")
        val lambda = set.profiles[0]
        val knock = Profiles.duplicate(set, lambda.id, nowMs = 1_000, name = "Knock")!!
        set = knock.first
        assertNull(Profiles.autoSelect(set, "AB12345"), "the active profile (Knock) is already one of this car")
        set = Profiles.activate(set, set.profiles.first { it.name == "120d E87" }.id, nowMs = 2_000)
        assertEquals("Knock", Profiles.autoSelect(set, "ab12345")?.name, "the one used last for the car, VIN case ignored")
        set = Profiles.activate(set, lambda.id, nowMs = 3_000)
        set = Profiles.activate(set, set.profiles.first { it.name == "Mini R50" }.id, nowMs = 4_000)
        assertEquals(lambda.id, Profiles.autoSelect(set, "AB12345")?.id)
        // Android Auto list: the active profile's car, else every profile
        set = Profiles.activate(set, lambda.id, nowMs = 5_000)
        assertEquals(listOf("E90 330i · Lambda", "Knock"), Profiles.sameCar(set).map { it.name })
        set = Profiles.activate(set, set.profiles.first { it.name == "Mini R50" }.id, nowMs = 6_000)
        assertEquals(4, Profiles.sameCar(Profiles.edit(set, set.activeId!!, vehicle = ProfileVehicle("R50"))).size)
    }

    @Test
    fun `an exported profile never contains the VIN link and imports as a new profile`() {
        val set = sample()
        val p = set.profiles[0]
        val text = ProfileJson.export(p)
        assertFalse(text.contains("AB12345"), "no VIN in a shared file")
        assertFalse(text.contains("vehicleId"))
        assertFalse(text.contains("lastUsedMs"))
        assertTrue(text.contains("\"kind\": \"bimmerdata-profile\""))
        val export = ProfileJson.parseExport(text)
        val (after, imported) = ProfileJson.importInto(set, export, nowMs = 7_000, fallbackName = "My profile")
        assertEquals("E90 330i · Lambda (2)", imported.name)
        assertEquals(imported.id, after.activeId)
        assertEquals(ProfileVehicle("E90", "330i", null), imported.vehicle)
        assertEquals(listOf("msv70"), imported.ecus)
        assertEquals(p.dashboard, imported.dashboard)
        // an older layout file is accepted too
        val fromLayout = ProfileJson.parseExport(DashboardJson.encode(layout.copy(name = "Old")))
        assertEquals("Old", fromLayout.name)
        assertEquals(layout, fromLayout.dashboard)
        assertThrows<IllegalArgumentException> { ProfileJson.parseExport("""{"kind":"something-else","name":"x"}""") }
        assertThrows<IllegalArgumentException> { ProfileJson.parseExport("not json") }
    }

    @Test
    fun `the store persists profiles, publishes them and restores a deleted one`(@TempDir dir: File) {
        val store = ProfileStore(dir, { "My profile" }, { _, e -> throw AssertionError(e) }, clock = { 42 }, info = {})
        val created = store.create("E90 330i", ProfileVehicle("E90", "330i"), listOf("msv70"))!!
        assertEquals(created.id, ProfileStore.state.value?.activeId)
        val copy = store.duplicate(created.id)!!
        assertEquals("E90 330i (2)", copy.name)
        val before = store.load()
        store.delete(copy.id)
        assertEquals(listOf(created.id), ProfileStore(dir, info = {}).load().profiles.map { it.id })
        store.restore(before)
        val back = ProfileStore(dir, info = {}).load()
        assertEquals(before, back)
        assertEquals(copy.id, back.activeId)
        val imported = store.import(ProfileJson.export(created), "My profile")
        assertEquals(3, store.load().profiles.size)
        assertEquals(imported.id, store.load().activeId)
        assertEquals(store.load().activeDashboard(), ProfileStore.activeDashboard.value, "the active layout is published")
        assertEquals(42, store.load().active?.lastUsedMs)
    }
}
