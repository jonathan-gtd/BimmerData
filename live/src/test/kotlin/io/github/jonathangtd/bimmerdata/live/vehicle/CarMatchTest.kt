/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which car a VIN read at connection or a scan result belongs to. */
class CarMatchTest {
    private val scanned = Vehicle("AB12345", "Daily", vin = "WBAPH11000AB12345", vinShort = "AB12345", series = "E8x/E9x", scannedAtMs = 1)
    private val other = Vehicle("ZZ99999", "Other car", vinShort = "ZZ99999", scannedAtMs = 2)
    private val byHand = Vehicle("car-hand", "Project car", series = "E46", model = "M3", addedSgbds = listOf("ms430"))
    private val cars = listOf(scanned, other, byHand)

    @Test
    fun `a VIN is matched by id, short VIN or the end of the full VIN, case ignored`() {
        assertEquals(scanned, CarMatch.findByVin(cars, "ab12345"))
        assertEquals("car-1", CarMatch.findByVin(listOf(scanned.copy(id = "car-1", vinShort = null)), "AB12345")?.id, "end of the full VIN")
        assertEquals(other, CarMatch.findByVin(cars, "ZZ99999"))
        assertNull(CarMatch.findByVin(cars, "NEW0001"))
    }

    @Test
    fun `decision at connection - every case`() {
        // known VIN, already selected
        assertEquals(VinDecision.Stay, CarMatch.decide(cars, "AB12345", "AB12345"))
        // known VIN, another car selected (with or without VIN): switch
        assertEquals(VinDecision.SwitchTo("AB12345"), CarMatch.decide(cars, "ZZ99999", "ab12345"))
        assertEquals(VinDecision.SwitchTo("ZZ99999"), CarMatch.decide(cars, "car-hand", "ZZ99999"), "a known VIN wins over the car without VIN")
        // unknown VIN: the selected car without VIN takes it (first connection)
        assertEquals(VinDecision.AttachToSelected("car-hand"), CarMatch.decide(cars, "car-hand", "NEW0001"))
        // unknown VIN, the selected car has its own VIN: new car
        assertEquals(VinDecision.NewCar, CarMatch.decide(cars, "AB12345", "NEW0001"))
        // no car at all, or nothing selected
        assertEquals(VinDecision.NewCar, CarMatch.decide(emptyList(), null, "NEW0001"))
        assertEquals(VinDecision.NewCar, CarMatch.decide(cars, null, "NEW0001"))
        // a car scanned without VIN (sequential scan) is still a car without VIN
        val noVin = Vehicle("car-seq", "Old car", scannedAtMs = 3)
        assertEquals(VinDecision.AttachToSelected("car-seq"), CarMatch.decide(listOf(noVin), "car-seq", "NEW0001"))
    }

    @Test
    fun `a scan merges into the car with its VIN, keeping id, name and hand-added SGBDs`() {
        val ecu = VehicleEcu(0x12, "DME/DDE", sgbd = "msv70", sgbdAvailable = true)
        val found = Vehicle("AB12345", "E8x/E9x AB12345", vinShort = "AB12345", series = "E8x/E9x", ecus = listOf(ecu), scannedAtMs = 10)
        val renamed = scanned.copy(id = "car-old", label = "Daily", addedSgbds = listOf("egs"))
        val (list, car) = CarMatch.mergeScan(listOf(renamed, other), "ZZ99999", found)
        assertEquals(2, list.size, "no duplicate")
        assertEquals("car-old", car.id, "the existing id is kept")
        assertEquals("Daily", car.label)
        assertEquals(listOf(ecu), car.ecus, "scanned ECUs replaced")
        assertEquals(listOf("msv70", "egs"), car.sgbds(), "hand-added SGBDs kept after the scanned ones")
        assertEquals("WBAPH11000AB12345", car.vin, "a VIN read before is kept")
    }

    @Test
    fun `a scan of a car added by hand fills it, an unknown VIN otherwise is a new car`() {
        val ecu = VehicleEcu(0x12, "DME/DDE", sgbd = "ms430")
        val found = Vehicle("NEW0001", "E4x NEW0001", vinShort = "NEW0001", series = "E4x", ecus = listOf(ecu), scannedAtMs = 10)
        // the selected car has no VIN: first scan of the car added by hand
        val (list, car) = CarMatch.mergeScan(cars, "car-hand", found)
        assertEquals(3, list.size)
        assertEquals("car-hand", car.id)
        assertEquals("Project car", car.label)
        assertEquals("NEW0001", car.vinShort)
        assertEquals("E46", car.series, "never scanned before: the series typed by hand stays")
        assertEquals("M3", car.model)
        assertEquals(listOf("ms430"), car.sgbds())
        // the selected car has its own VIN: the scan is another car
        val (list2, car2) = CarMatch.mergeScan(cars, "AB12345", found)
        assertEquals(4, list2.size)
        assertEquals(found, car2)
        // no VIN read: the car the scanner continued (same id) is updated
        val noVin = found.copy(id = "ZZ99999", vinShort = null, label = "x")
        val (list3, car3) = CarMatch.mergeScan(cars, "ZZ99999", noVin)
        assertEquals(3, list3.size)
        assertEquals("Other car", car3.label)
        assertEquals("ZZ99999", car3.vinShort)
    }

    @Test
    fun `an ECU the detection no longer finds stays only while profiles use it`() {
        val dme = VehicleEcu(0x12, "DME/DDE", sgbd = "msv70")
        val egs = VehicleEcu(0x18, "EGS", sgbd = "gs19")
        val kombi = VehicleEcu(0x60, "KOMBI", sgbd = "kombi90")
        val car = scanned.copy(ecus = listOf(dme, egs, kombi), addedSgbds = listOf("ms430"))
        val found = Vehicle("AB12345", "x", vinShort = "AB12345", ecus = listOf(dme), scannedAtMs = 20)
        val m = CarMatch.mergeScan(listOf(car), "AB12345", found) { id -> if (id == "AB12345") setOf("GS19") else emptySet() }
        assertEquals(listOf("gs19"), m.kept, "used by a profile: kept and asked about")
        assertEquals(listOf("msv70", "ms430", "gs19"), m.car.sgbds(), "kombi90 (unused) leaves, gs19 stays as added by hand")
        assertEquals(false, m.isNew)
        // a new ECU found simply joins the list
        val more = CarMatch.mergeScan(listOf(car), "AB12345", found.copy(ecus = listOf(dme, egs, kombi, VehicleEcu(0x40, "CAS", sgbd = "cas"))))
        assertEquals(emptyList<String>(), more.kept)
        assertEquals(listOf("msv70", "gs19", "kombi90", "cas", "ms430"), more.car.sgbds())
    }
}
