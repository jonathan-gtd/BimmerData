/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.cars

import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.graph.Graph
import io.github.jonathangtd.bimmerdata.graph.GraphSeries
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileJson
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Cars and their profiles: migration, the VIN at connection, deletion, copy, ECU lists. */
class CarsTest {
    private fun profile(id: String, name: String, vehicle: ProfileVehicle, ecus: List<String>, carId: String = "", used: Long = 0, dashboard: Dashboard = Dashboard()) =
        Profile(id = id, name = name, carId = carId, vehicle = vehicle, ecus = ecus, lastUsedMs = used, dashboard = dashboard)

    private fun card(id: String) = Card(type = GaugeType.Dial, measurementIds = listOf(id))

    /** The owner's phone before cars: 12 profiles "E90 330i · …" (msv70, one with egs), an empty garage. */
    private fun ownerPhone(): CarSpace {
        val e90 = ProfileVehicle("E90", "330i")
        val profiles = (1..12).map { i -> profile("p$i", "E90 330i · $i", e90, if (i == 12) listOf("msv70", "egs") else listOf("msv70"), used = i.toLong()) }
        return CarSpace(Garage(), ProfileSet(profiles = profiles, activeId = "p5"))
    }

    @Test
    fun `migration - profiles grouped by car name get one car each, idempotent`() {
        val before = ownerPhone()
        val after = Cars.repair(before, "My car", nowMs = 100)
        assertEquals(1, after.cars.size)
        val car = after.cars.single()
        assertEquals("E90 330i", car.label)
        assertEquals("E90", car.series)
        assertEquals("330i", car.model)
        assertTrue(!car.hasVin, "made by hand: the VIN comes at the first connection")
        assertEquals(listOf("msv70", "egs"), car.sgbds(), "the union of its profiles' ECUs")
        assertTrue(after.profiles.profiles.all { it.carId == car.id })
        assertEquals(car.id, after.garage.currentId, "the active profile's car")
        assertEquals("p5", after.profiles.activeId, "the active profile stays")
        // idempotent: a second pass changes nothing
        assertSame(after, Cars.repair(after, "My car", nowMs = 200))
        // through the store's JSON (the file written then read back)
        val back = ProfileJson.decode(ProfileJson.encode(after.profiles))
        assertEquals(back, Cars.repair(CarSpace(after.garage, back), "My car", 300).profiles)
    }

    @Test
    fun `migration - the old link to a scanned car wins, other names and empty names get their own car`() {
        val scanned = Vehicle("AB12345", "Daily", vinShort = "AB12345", ecus = listOf(VehicleEcu(0x12, "DME", sgbd = "msv70")), scannedAtMs = 1)
        val set = ProfileSet(
            profiles = listOf(
                profile("a", "Lambda", ProfileVehicle("E90", "330i", vehicleId = "ab12345"), listOf("msv70", "egs"), used = 3),
                profile("b", "Knock", ProfileVehicle("E90", "330i"), listOf("msv70"), used = 1),
                profile("c", "Mini", ProfileVehicle("R50"), listOf("kms2000"), used = 2),
                profile("d", "Unnamed", ProfileVehicle(), emptyList(), used = 4),
            ),
            activeId = "c",
        )
        val after = Cars.repair(CarSpace(Garage(listOf(scanned), "AB12345"), set), "My car", 10)
        assertEquals(listOf("Daily", "E90 330i", "R50", "My car"), after.cars.map { it.label })
        val p = after.profiles
        assertEquals("AB12345", p.profile("a")!!.carId, "linked by the old vehicleId")
        assertNull(p.profile("a")!!.vehicle.vehicleId, "the old link is cleared")
        assertEquals(listOf("msv70", "egs"), after.car("AB12345")!!.sgbds(), "the linked car gets the ECUs it lacked")
        assertEquals(after.cars[1].id, p.profile("b")!!.carId)
        assertEquals(after.cars[2].id, after.garage.currentId, "the active profile (Mini) decides the selected car")
        assertEquals("c", p.activeId)
    }

    @Test
    fun `the active profile is always one of the selected car's`() {
        val space = Cars.repair(ownerPhone(), "My car", 1)
        val (withCar, other) = Cars.create(space, "Track car", nowMs = 2)
        assertEquals(space.garage.currentId, withCar.garage.currentId, "adding a car does not select it")
        val switched = Cars.select(withCar, other.id, 3)
        assertNull(switched.profiles.activeId, "a car without profile: none active")
        val back = Cars.select(switched, space.garage.currentId!!, 1_000)
        assertEquals("p12", back.profiles.activeId, "its last used profile")
        // the store's safety net puts it right if a write left the car without its active profile
        val wrong = back.copy(profiles = back.profiles.copy(activeId = null))
        assertEquals("p12", Cars.repair(wrong, "My car", 1_001).profiles.activeId)
    }

    @Test
    fun `the VIN at connection - switch, suggest while recording, attach, new car`() {
        val known = Vehicle("AB12345", "Daily", vinShort = "AB12345")
        val hand = Vehicle("car-hand", "Project")
        val set = ProfileSet(
            profiles = listOf(profile("d1", "Daily · 1", ProfileVehicle(), emptyList(), "AB12345", used = 5), profile("h1", "Project · 1", ProfileVehicle(), emptyList(), "car-hand", used = 9)),
            activeId = "h1",
        )
        val space = CarSpace(Garage(listOf(known, hand), "car-hand"), set)

        val (switched, s) = Cars.onVin(space, "AB12345", recording = false, newLabel = "Car AB12345", nowMs = 10)
        assertEquals(VinOutcome.Switched(known, switched.profiles.profile("d1")), s)
        assertEquals("AB12345", switched.garage.currentId)
        assertEquals("d1", switched.profiles.activeId)

        val (same, suggested) = Cars.onVin(space, "AB12345", recording = true, newLabel = "x", nowMs = 10)
        assertEquals(VinOutcome.Suggested(known, set.profile("d1")!!), suggested)
        assertSame(space, same, "nothing changes while recording")

        val (attached, a) = Cars.onVin(space, "NEW0001", recording = false, newLabel = "Car NEW0001", nowMs = 10)
        assertTrue(a is VinOutcome.Attached)
        assertEquals("NEW0001", attached.car("car-hand")!!.vinShort, "first connection of the car added by hand: its id stays")
        assertEquals(2, attached.cars.size)

        val fromKnown = Cars.select(space, "AB12345", 11)
        val (created, c) = Cars.onVin(fromKnown, "NEW0002", recording = false, newLabel = "Car NEW0002", nowMs = 12)
        assertEquals(VinOutcome.Created(created.car("NEW0002")!!, true), c)
        assertEquals("Car NEW0002", created.car("NEW0002")!!.label)
        assertEquals("NEW0002", created.garage.currentId)
        assertNull(created.profiles.activeId, "a new car has no profile yet")

        val (createdRec, cr) = Cars.onVin(fromKnown, "NEW0003", recording = true, newLabel = "Car NEW0003", nowMs = 12)
        assertEquals(VinOutcome.Created(createdRec.car("NEW0003")!!, false), cr)
        assertEquals("AB12345", createdRec.garage.currentId, "not selected during a recording")

        assertEquals(VinOutcome.Unchanged, Cars.onVin(fromKnown, "ab12345", recording = false, newLabel = "x", nowMs = 13).second)
        // first car ever, from the VIN
        val (first, f) = Cars.onVin(CarSpace(), "FIRST01", recording = false, newLabel = "Car FIRST01", nowMs = 1)
        assertEquals("FIRST01", first.garage.currentId)
        assertTrue(f is VinOutcome.Created)
    }

    @Test
    fun `deleting a car deletes its profiles and fault read, the next car is selected`() {
        val a = Vehicle("car-a", "A")
        val b = Vehicle("car-b", "B")
        val set = ProfileSet(
            profiles = listOf(profile("a1", "A1", ProfileVehicle(), emptyList(), "car-a", 1), profile("b1", "B1", ProfileVehicle(), emptyList(), "car-b", 2), profile("b2", "B2", ProfileVehicle(), emptyList(), "car-b", 3)),
            activeId = "a1",
        )
        val faults = mapOf("car-a" to listOf(EcuFaults(0x12, EcuFaultStatus.Ok)), "car-b" to listOf(EcuFaults(0x12, EcuFaultStatus.Ok)))
        val space = CarSpace(Garage(listOf(a, b), "car-a", faults), set)
        val other = Cars.delete(space, "car-b", 5)
        assertEquals(listOf("a1"), other.profiles.profiles.map { it.id })
        assertEquals(setOf("car-a"), other.garage.faults.keys)
        assertEquals("a1", other.profiles.activeId)
        val selected = Cars.delete(space, "car-a", 5)
        assertEquals("car-b", selected.garage.currentId)
        assertEquals("b2", selected.profiles.activeId, "the next car's last used profile")
        assertSame(space, Cars.delete(space, "nope", 5))
    }

    @Test
    fun `copy to another car - pages and graphs kept, ECUs brought to the car, not activated`() {
        val a = Vehicle("car-a", "A", addedSgbds = listOf("msv70", "egs"))
        val b = Vehicle("car-b", "B", addedSgbds = listOf("msv70"))
        val pages = Dashboard(phonePages = listOf(Page(name = "P", columns = 1, rows = 1, cards = listOf(card("egs/0x01")))))
        val src = profile("a1", "Gearbox", ProfileVehicle("E90"), listOf("msv70", "egs"), "car-a", 1, pages).copy(graphs = listOf(Graph(name = "G")))
        val space = CarSpace(Garage(listOf(a, b), "car-a"), ProfileSet(profiles = listOf(src), activeId = "a1"))
        val (after, copy) = Cars.copyProfile(space, "a1", "car-b", 9)!!
        assertEquals("car-b", copy.carId)
        assertEquals("Gearbox (2)", copy.name)
        assertEquals(pages, copy.dashboard)
        assertEquals(src.graphs, copy.graphs)
        assertEquals(listOf("msv70", "egs"), copy.ecus, "nothing removed from the profile")
        assertEquals(listOf("msv70", "egs"), after.car("car-b")!!.sgbds(), "the car gets the ECU it lacked")
        assertEquals("a1", after.profiles.activeId)
        assertNull(Cars.copyProfile(space, "a1", "nope", 9))
        // onto the selected car without profile: it is shown
        val (onEmpty, c2) = Cars.copyProfile(Cars.select(space, "car-b", 10), "a1", "car-b", 11)!!
        assertEquals(c2.id, onEmpty.profiles.activeId)
    }

    @Test
    fun `ECU lists - foreign ECUs refused, a car without list takes them, removal asked then done`() {
        val scannedCar = Vehicle("car-a", "A", ecus = listOf(VehicleEcu(0x12, "DME", sgbd = "msv70")), addedSgbds = listOf("egs"))
        assertEquals(listOf("dde"), Cars.foreignEcus(scannedCar, listOf("msv70", "dde")))
        assertTrue(Cars.foreignEcus(Vehicle("car-b", "B"), listOf("dde")).isEmpty(), "no list yet")

        val layout = Dashboard(
            phonePages = listOf(Page(name = "P", columns = 2, rows = 1, cards = listOf(card("egs/0x01"), card("msv70/1")))),
            carPages = listOf(Page(name = "C", columns = 2, rows = 1, cards = listOf(card("egs/0x01"), card("gps/speed")))),
        )
        val p = profile("p", "P", ProfileVehicle(), listOf("msv70", "egs"), "car-a", 1, layout).copy(graphs = listOf(Graph(name = "G", series = listOf(GraphSeries("egs/0x01")))))
        val space = CarSpace(Garage(listOf(scannedCar), "car-a"), ProfileSet(profiles = listOf(p), activeId = "p"))
        val uses = Cars.ecusInUse(space, "car-a", listOf("egs", "kombi"))
        assertEquals(setOf("egs"), uses.keys)
        assertEquals(EcuUse(p, inEcus = true, cards = 2, graphSeries = 1), uses["egs"]!!.single())
        assertEquals(setOf("msv70", "egs", "gps"), EcuUsage.usedSgbds(listOf(p)))

        val removed = Cars.removeEcu(space, "car-a", "egs")
        assertEquals(listOf("msv70"), removed.car("car-a")!!.sgbds())
        val after = removed.profiles.profile("p")!!
        assertEquals(listOf("msv70"), after.ecus)
        assertEquals(listOf("msv70/1"), after.dashboard.phonePages.single().cards.flatMap { it.measurementIds })
        assertEquals(listOf("gps/speed"), after.dashboard.carPages.single().cards.flatMap { it.measurementIds }, "phone sensors belong to no ECU")
        assertTrue(after.graphs.single().series.isEmpty())
        // undo
        val undone = Cars.restoreCar(removed, space, "car-a")
        assertEquals(space, undone)

        // the profile editor: unticked with "Remove" deletes its cards; a new ECU joins a car without list
        val edited = Cars.editProfile(space, "p", null, null, listOf("msv70"), dropCards = listOf("egs"))
        assertEquals(listOf("msv70/1", "gps/speed"), edited.profiles.profile("p")!!.dashboard.measurementIds().toList())
        val noList = CarSpace(Garage(listOf(Vehicle("car-b", "B")), "car-b"), ProfileSet(profiles = listOf(p.copy(carId = "car-b")), activeId = "p"))
        assertEquals(listOf("msv70", "egs", "dde"), Cars.editProfile(noList, "p", null, null, listOf("msv70", "egs", "dde")).car("car-b")!!.sgbds())
    }

    @Test
    fun `a profile needs a car - none on a fresh install, else the selected one or the first`() {
        assertNull(Cars.selectedOrFirst(CarSpace(), 1), "a fresh install has no car: the user adds one first")
        val (space, car) = Cars.create(CarSpace(), "My car", nowMs = 1)
        val unselected = space.copy(garage = space.garage.copy(currentId = null))
        val (picked, first) = Cars.selectedOrFirst(unselected, 2)!!
        assertEquals(car.id, first.id)
        assertEquals(car.id, picked.garage.currentId)
        val (withProfile, p) = Cars.createProfile(picked, car.id, "Lambda", ProfileVehicle(), listOf("msv70"), 2)
        assertEquals(p.id, withProfile.profiles.activeId)
        assertEquals(listOf("msv70"), withProfile.car(car.id)!!.sgbds())
        assertNotNull(withProfile.selected)
    }
}
