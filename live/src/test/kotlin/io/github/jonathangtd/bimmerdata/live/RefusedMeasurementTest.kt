/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.sim.VehicleSimulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultService
import io.github.jonathangtd.bimmerdata.live.vehicle.SharedJobRunner
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleScanner
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleTables

/**
 * A card whose ECU answers but refuses the job (here the simulated junction box, which only
 * implements identification and fault memory) must not make the connection look dead: before the
 * fix the status went to Error ("no answer") and the engine reconnected every few cycles.
 */
class RefusedMeasurementTest {

    private val refusedJob = "jbbf87/STATUS_DIGITAL_INPUTS/STAT_SHIFTREGISTER_DATA_EIN"

    private fun catalog(): EdiabasMeasurementCatalog {
        val dir = LiveTestData.ecuDir()
        assumeTrue(dir.resolve("jbbf87.prg").exists() || dir.resolve("JBBF87.PRG").exists(), "needs the user's ECU files")
        return EdiabasMeasurementCatalog(dir)
    }

    /** States seen while [ms] elapse. */
    private suspend fun watch(engine: AcquisitionEngine, ms: Long): List<AcquisitionStatus> {
        val seen = ArrayList<AcquisitionStatus>()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) {
            seen += engine.status.value
            delay(20)
        }
        return seen
    }

    @Test
    fun `a job the ECU refuses keeps the link up and never triggers a reconnection`() = runBlocking {
        val catalog = catalog()
        val sim = VehicleSimulator.e90()
        val engine = AcquisitionEngine(EdInterfaceObd(sim.kLine(realisticTiming = false)), catalog)
        try {
            engine.setMeasurements(listOf(catalog.measurement(refusedJob)!!))
            engine.start()
            withTimeout(10_000) { while (engine.status.value.errorCount == 0) delay(10) }
            val seen = watch(engine, 4_000)
            assertTrue(seen.none { it.state == AcquisitionState.Error }, "no link error: ${seen.map { it.state }.distinct()}")
            assertTrue(seen.none { it.state == AcquisitionState.Connecting }, "no reconnection")
            val last = engine.status.value
            assertEquals(AcquisitionState.Running, last.state)
            assertTrue(last.message!!.contains("ERROR_ECU_SERVICE_NOT_SUPPORTED"), last.message)
            assertTrue(refusedJob in engine.unavailable.value, "the card shows it as not available")

            // a one-off operation still runs on the same, never closed, connection
            val nr = engine.exclusive("Tool32: JBBF IDENT") { it.executeJob("jbbf87", "IDENT")[1]["ID_BMW_NR"]!!.opData }
            assertEquals("6943804", (nr as String).trimStart('0'))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an ECU that does not answer at all is still a link error`() = runBlocking {
        val catalog = catalog()
        val sim = VehicleSimulator.e90()
        sim.ecu(0x00)!!.silent = true
        val engine = AcquisitionEngine(EdInterfaceObd(sim.kLine(realisticTiming = false)), catalog)
        try {
            engine.setMeasurements(listOf(catalog.measurement(refusedJob)!!))
            engine.start()
            withTimeout(20_000) { while (engine.status.value.state != AcquisitionState.Error) delay(10) }
        } finally {
            engine.close()
        }
    }

    /** Ids of the user's dashboard when the bug was reported (the GPS ones never reach the engine). */
    private val userIds = listOf(refusedJob, "00swtkws/SWT_PARAMETER_LESEN/SG_ADRESSE")

    private suspend fun rate(engine: AcquisitionEngine, id: String, ms: Long): Double {
        withTimeout(10_000) { while (engine.samples.value[id] == null) delay(10) }
        var last = engine.samples.value[id]!!.timestampMs
        var n = 0
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) {
            val ts = engine.samples.value[id]!!.timestampMs
            if (ts != last) {
                n++
                last = ts
            }
            delay(2)
        }
        return n * 1000.0 / (System.currentTimeMillis() - t0)
    }

    @Test
    fun `the user's dashboard - unavailable cards do not slow the others, and the fault read of every ECU still works`() = runBlocking {
        val catalog = catalog()
        val oil = "msv70/0x4402"
        val baseline = VehicleSimulator.e90().let { sim ->
            val engine = AcquisitionEngine(EdInterfaceObd(sim.kLine(realisticTiming = true)), catalog)
            try {
                engine.setMeasurements(listOf(catalog.measurement(oil)!!))
                engine.start()
                rate(engine, oil, 3_000)
            } finally {
                engine.close()
            }
        }
        val sim = VehicleSimulator.e90()
        val engine = AcquisitionEngine(EdInterfaceObd(sim.kLine(realisticTiming = true)), catalog)
        try {
            engine.setMeasurements((userIds + oil).map { catalog.measurement(it)!! })
            engine.start()
            val withRefused = rate(engine, oil, 3_000)
            println("REFUSED oil reads/s alone %.1f, next to the two unavailable cards %.1f".format(baseline, withRefused))
            assertTrue(withRefused >= baseline * 0.8, "unavailable cards must not slow the others: $withRefused vs $baseline")
            assertEquals(AcquisitionState.Running, engine.status.value.state)
            assertEquals(userIds.toSet(), engine.unavailable.value.keys)
            assertTrue(engine.unavailable.value.getValue("00swtkws/SWT_PARAMETER_LESEN/SG_ADRESSE").contains("IFH-0014"))

            // scan + fault memory of every ECU through the shared cable, as the Faults tab does
            val tables = VehicleTables(LiveTestData.ecuDir())
            val runner = SharedJobRunner(engine)
            val vehicle = VehicleScanner(runner, tables).scan().vehicle!!
            val faults = FaultService(runner, tables).readAll(vehicle)
            val jbbf = faults.first { it.address == 0x00 }
            assertEquals(EcuFaultStatus.Ok, jbbf.status, "JBBF87: ${jbbf.message}")
            assertTrue(faults.none { it.status == EcuFaultStatus.NotResponding }, faults.filter { it.status == EcuFaultStatus.NotResponding }.joinToString())
            assertEquals(AcquisitionState.Running, engine.status.value.state)
        } finally {
            engine.close()
        }
    }
}
