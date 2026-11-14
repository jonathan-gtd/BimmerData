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

/** Profiles: model operations, profiles of one car, export / import, persistence. */
class ProfilesTest {
    private val layout = Msv70Example.create()

    private fun sample(): ProfileSet {
        var set = ProfileSet()
        set = Profiles.create(set, "E90 330i · Lambda", ProfileVehicle("E90", "330i", "AB12345"), listOf("MSV70.prg"), nowMs = 100, dashboard = layout, carId = "car-e90").first
        set = Profiles.create(set, "120d E87", ProfileVehicle("E87", "120d", "ZZ99999"), listOf("dde"), nowMs = 200, carId = "car-e87").first
        set = Profiles.create(set, "Mini R50", ProfileVehicle("R50"), listOf("kms2000"), nowMs = 300, carId = "car-r50").first
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
        val (twice, p) = Profiles.create(set, " e90 330i ·  LAMBDA ", nowMs = 400, carId = "car-e90")
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
        assertEquals("car-e90", copy.carId, "same car")
        assertEquals(src.ecus, copy.ecus)
        assertEquals(src.dashboard, copy.dashboard)
        assertEquals(1, after.profiles.indexOf(copy), "right after its source")
        // editing the copy leaves the source alone
        val edited = Profiles.writeDashboard(after, copy.id, Dashboard(name = "E90 330i · Knock"))
        assertEquals(layout, edited.profile(src.id)!!.dashboard)
        assertTrue(edited.profile(copy.id)!!.dashboard.phonePages.isEmpty())
    }

    @Test
    fun `writes go to the given profile, a new name renames it, delete picks the last used of the car`() {
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
        // delete the active one: another profile of its car becomes active, never one of another car
        set = Profiles.activate(set, lambda.id, nowMs = 900)
        set = Profiles.delete(set, lambda.id)
        assertNull(set.activeId, "Lambda was the only profile of its car")
        set = set.profiles.fold(set) { s, p -> Profiles.delete(s, p.id) }
        assertTrue(set.profiles.isEmpty())
    }

    @Test
    fun `a write without profile creates one only for a layout with content`() {
        val empty = ProfileSet()
        assertEquals(empty, Profiles.writeActiveDashboard(empty, Dashboard(), "My profile", 1, "car-1"))
        val created = Profiles.writeActiveDashboard(empty, layout, "My profile", 1, "car-1")
        assertEquals("My profile", created.active?.name)
        assertEquals("car-1", created.active?.carId, "on the selected car")
        assertEquals(layout, created.active?.dashboard)
        assertEquals("330i", Profiles.writeActiveDashboard(empty, Dashboard(name = "330i"), "My profile", 1, "car-1").active?.name)
    }

    @Test
    fun `the lists and the fallbacks of one car never show another car's profiles`() {
        var set = sample()
        val lambda = set.profiles[0]
        val (withKnock, knock) = Profiles.duplicate(set, lambda.id, nowMs = 1_000, name = "Knock")!!
        set = withKnock
        assertEquals(listOf("E90 330i · Lambda", "Knock"), set.forCar("car-e90").profiles.map { it.name })
        assertEquals(knock.id, set.forCar("car-e90").activeId)
        assertNull(set.forCar("car-r50").activeId, "the active profile is not one of that car")
        // Android Auto lists the active profile's car
        assertEquals(listOf("E90 330i · Lambda", "Knock"), Profiles.sameCar(set).map { it.name })
        // the active one deleted: the last used of the same car, not Mini R50 used more recently
        set = Profiles.activate(set, set.profiles.first { it.name == "Mini R50" }.id, nowMs = 2_000)
        set = Profiles.activate(set, knock.id, nowMs = 3_000)
        set = Profiles.delete(set, knock.id)
        assertEquals(lambda.id, set.activeId)
        set = Profiles.delete(set, lambda.id)
        assertNull(set.activeId, "its car has no profile left")
        assertTrue(Profiles.sameCar(set).isEmpty())
    }

    @Test
    fun `Android Auto lists the active profile first, then the most recently used, then the others in order`() {
        var set = sample()
        val lambda = set.profiles[0]
        set = Profiles.duplicate(set, lambda.id, nowMs = 0, name = "A")!!.first
        set = Profiles.duplicate(set, lambda.id, nowMs = 0, name = "B")!!.first
        set = Profiles.duplicate(set, lambda.id, nowMs = 0, name = "C")!!.first
        val id = { name: String -> set.profiles.first { it.name == name }.id }
        // never used profiles (lastUsedMs 0) keep the list order
        set = set.copy(profiles = set.profiles.map { if (it.carId == "car-e90") it.copy(lastUsedMs = 0) else it })
        set = Profiles.activate(set, id("C"), nowMs = 5_000)
        set = Profiles.activate(set, id("A"), nowMs = 6_000)
        set = Profiles.activate(set, id("Mini R50"), nowMs = 7_000) // another car: not listed
        set = Profiles.activate(set, id("E90 330i · Lambda"), nowMs = 8_000)
        assertEquals(listOf("E90 330i · Lambda", "A", "C", "B"), Profiles.carChoice(set).map { it.name })
        set = Profiles.activate(set, id("C"), nowMs = 9_000)
        assertEquals(listOf("C", "E90 330i · Lambda", "A", "B"), Profiles.carChoice(set).map { it.name })
    }

    @Test
    fun `an exported profile never contains the VIN link and imports as a new profile`() {
        val set = sample()
        val p = set.profiles[0]
        val text = ProfileJson.export(p)
        assertFalse(text.contains("AB12345"), "no VIN in a shared file")
        assertFalse(text.contains("vehicleId"))
        assertFalse(text.contains("carId"), "nor the user's car")
        assertFalse(text.contains("car-e90"))
        assertFalse(text.contains("lastUsedMs"))
        assertTrue(text.contains("\"kind\": \"bimmerdata-profile\""))
        val export = ProfileJson.parseExport(text)
        val (after, imported) = ProfileJson.importInto(set, export, nowMs = 7_000, fallbackName = "My profile", carId = "car-other")
        assertEquals("car-other", imported.carId)
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
        val created = store.update { Profiles.create(it, "E90 330i", ProfileVehicle("E90", "330i"), listOf("msv70"), nowMs = 42, carId = "car-1").first }!!.active!!
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
        val imported = store.update { ProfileJson.importInto(it, ProfileJson.parseExport(ProfileJson.export(created)), 42, "My profile", "car-1").first }!!.active!!
        assertEquals(3, store.load().profiles.size)
        assertEquals(imported.id, store.load().activeId)
        assertEquals(store.load().activeDashboard(), ProfileStore.activeDashboard.value, "the active layout is published")
        assertEquals(42, store.load().active?.lastUsedMs)
    }

    @Test
    fun `files written before cars still load, and the store gives their profiles a car at once`(@TempDir dir: File) {
        // a profiles.json of an older version: no carId, a link to a car of the Faults tab
        File(dir, ProfileStore.FILE_NAME).writeText(
            """{"formatVersion":1,"activeId":"p1","profiles":[{"id":"p1","name":"E90 330i · Lambda","vehicle":{"series":"E90","model":"330i","vehicleId":"AB12345"},"ecus":["msv70"]}]}""",
        )
        val store = ProfileStore(dir, warn = { _, e -> throw AssertionError(e) }, info = {}, carCheck = { set ->
            set.copy(profiles = set.profiles.map { if (it.carId.isEmpty()) it.copy(carId = "car-new") else it })
        })
        val set = store.load()
        assertEquals("car-new", set.active?.carId)
        assertEquals("AB12345", ProfileStore(dir, info = {}).load().active?.vehicle?.vehicleId, "read as before until the migration clears it")
        assertTrue(File(dir, ProfileStore.FILE_NAME).readText().contains("car-new"), "written back at once")
    }
}
