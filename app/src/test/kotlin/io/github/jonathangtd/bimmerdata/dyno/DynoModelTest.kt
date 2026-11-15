/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dyno

import io.github.jonathangtd.bimmerdata.backup.BackupCodec
import io.github.jonathangtd.bimmerdata.backup.BackupContent
import io.github.jonathangtd.bimmerdata.backup.BackupInfo
import io.github.jonathangtd.bimmerdata.backup.BackupSettings
import io.github.jonathangtd.bimmerdata.backup.BackupTranslations
import io.github.jonathangtd.bimmerdata.live.dyno.KW_TO_CH
import io.github.jonathangtd.bimmerdata.live.vehicle.CarPhysics
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import io.github.jonathangtd.bimmerdata.vehicle.GarageJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class DynoModelTest {
    @Test
    fun `a run keeps every reading and gives the curve again after a JSON round trip`() {
        val run = DynoTestData.run("car-1").copy(note = "4th, north")
        val book = DynoRuns.add(DynoBook(inputs = mapOf("car-1" to DynoInputs(rpm = "ecu/0x0001", drivelineLossPct = 12.0))), run)
        val back = DynoJson.decode(DynoJson.encode(book))
        assertEquals(book, back)
        val capture = back.runs.single().capture()
        assertEquals(DynoTestData.capture().rpm, capture.rpm)
        val r = DynoRuns.result(back.runs.single(), null)
        assertTrue(r.ok, "${r.problem}")
        // 120 kW at 6000 rpm, found within 3 %
        assertTrue(abs(r.peakWheelPowerCh!! - 120 * KW_TO_CH) / (120 * KW_TO_CH) < 0.03, "${r.peakWheelPowerCh}")
        assertEquals(25.0, r.kmhPer1000Rpm!!, 0.3)
    }

    @Test
    fun `the car's current mass is used, the run's when the car has none`() {
        val run = DynoTestData.run("car-1", massKg = 1500.0)
        val asMeasured = DynoRuns.result(run, null).peakWheelPowerCh!!
        val heavier = DynoRuns.result(run, CarPhysics(massKg = 1650.0)).peakWheelPowerCh!!
        assertTrue(heavier > asMeasured * 1.05)
        assertEquals(asMeasured, DynoRuns.result(run, CarPhysics()).peakWheelPowerCh!!, 1e-9)
        assertEquals(io.github.jonathangtd.bimmerdata.live.dyno.DynoProblem.NoMass, DynoRuns.result(run.copy(massKg = null), CarPhysics()).problem)
    }

    @Test
    fun `runs per car are limited, the oldest go first, deleted cars take their runs`() {
        var book = DynoBook()
        repeat(DynoRuns.MAX_RUNS_PER_CAR + 3) { i -> book = DynoRuns.add(book, DynoTestData.run("car-1", startMs = 1_000L + i, id = "r$i")) }
        book = DynoRuns.add(book, DynoTestData.run("car-2", id = "other"))
        assertEquals(DynoRuns.MAX_RUNS_PER_CAR, book.runsOf("car-1").size)
        assertNull(book.run("r0"))
        assertNull(book.run("r2"))
        assertTrue(book.run("r3") != null)
        val kept = DynoRuns.keepCars(book.copy(inputs = mapOf("car-1" to DynoInputs(), "car-2" to DynoInputs())), setOf("car-2"))
        assertEquals(listOf("other"), kept.runs.map { it.id })
        assertEquals(setOf("car-2"), kept.inputs.keys)
        assertEquals("abc", DynoRuns.rename(kept, "other", "  abc  ").run("other")!!.note)
        assertTrue(DynoRuns.delete(kept, "other").runs.isEmpty())
    }

    @Test
    fun `defaults - first engine speed, an ECU speed before the GPS, a pedal only by its name, never an overlay`() {
        val d = DynoDefaults.resolve(DynoInputs(), DynoTestData.candidates)
        assertEquals(DynoTestData.rpm.id, d.rpm)
        assertEquals(DynoTestData.speed.id, d.speed)
        assertEquals(DynoTestData.pedal.id, d.pedal)
        assertNull(d.overlay)
        // only the GPS: it is taken; a % value not named like a pedal is not
        val gpsOnly = DynoDefaults.resolve(DynoInputs(), listOf(DynoTestData.rpm, DynoTestData.gps, DynoTestData.load))
        assertEquals("gps/speed", gpsOnly.speed)
        assertNull(gpsOnly.pedal)
        // the user's choice wins
        assertEquals("gps/speed", DynoDefaults.resolve(DynoInputs(speed = "gps/speed"), DynoTestData.candidates).speed)
        assertTrue(DynoDefaults.isOverlay(DynoTestData.torque))
        assertTrue(!DynoDefaults.isOverlay(DynoTestData.oil))
    }

    @Test
    fun `CSV - header, every sample, the curve`() {
        val run = DynoTestData.run("car-1")
        val r = DynoRuns.result(run, null)
        val csv = DynoCsv.encode(run, r, "My car, blue")
        val lines = csv.lines()
        assertEquals("# BimmerData virtual dyno", lines[0])
        assertEquals("# car,\"My car, blue\"", lines[1])
        val samplesHeader = lines.indexOf("time_ms,rpm_read,rpm_smoothed,speed_kmh,accel_ms2,wheel_power_ch,torque_nm,overlay")
        val curveHeader = lines.indexOf("rpm,wheel_power_ch,torque_nm,engine_power_ch_estimated,overlay_torque_nm,overlay_power_ch,samples")
        assertTrue(samplesHeader > 0 && curveHeader > samplesHeader)
        assertEquals(r.processed.size, curveHeader - samplesHeader - 2)
        assertEquals(r.points.size, lines.drop(curveHeader + 1).count { it.isNotBlank() })
        assertTrue(DynoCsv.fileName(run).startsWith("bimmerdata-dyno-") && DynoCsv.fileName(run).endsWith(".csv"))
    }

    @Test
    fun `car values are stored with the car and in the backup with the runs`() {
        val car = Vehicle("car-1", "My car", physics = CarPhysics(massKg = 1620.0, dragAreaM2 = 0.62))
        val garage = Garage(listOf(car, Vehicle("car-2", "Other")), currentId = "car-1")
        val decoded = GarageJson.decode(GarageJson.encode(garage))
        assertEquals(car.physics, decoded.vehicles[0].physics)
        assertEquals(CarPhysics(), decoded.vehicles[1].physics)
        assertTrue(!GarageJson.encode(Garage(listOf(Vehicle("x", "x")))).contains("physics"))

        val book = DynoRuns.add(DynoBook(inputs = mapOf("car-1" to DynoInputs(speed = "gps/speed"))), DynoTestData.run("car-1"))
        val content = BackupContent(garage, ProfileSet(), BackupSettings(), BackupTranslations(), dyno = book)
        val (back, _) = BackupCodec.decode(BackupCodec.encode(content, BackupInfo("t", 1, 1)))
        assertEquals(book, back.dyno)
        assertEquals(1620.0, back.garage.vehicles[0].physics.massKg)
        // an older backup has no dyno: the phone's runs are kept (null)
        val old = BackupCodec.decode(BackupCodec.encode(content.copy(dyno = null), BackupInfo("t", 1, 1))).first
        assertNull(old.dyno)
        // runs of a car that is not in the backup are left out
        val foreign = content.copy(dyno = DynoRuns.add(book, DynoTestData.run("car-9", id = "foreign")))
        assertEquals(1, BackupCodec.decode(BackupCodec.encode(foreign, BackupInfo("t", 1, 1))).first.dyno!!.runs.size)
    }
}
