/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.deepobd.DeepObdPort
import io.github.jonathangtd.bimmerdata.ediabas.sim.DeepObdAdapterSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The acquisition engine over a (simulated) Deep OBD Bluetooth adapter: fast path, one-off jobs. */
class DeepObdEngineTest {

    private val ids = listOf(0x4807, 0x5804, 0x4300, 0x4200, 0x5A50, 0x5A51, 0x5A81, 0x5A82, 0x5A85, 0x5A86)

    private suspend fun awaitUntil(timeoutMs: Long = 15_000, what: String, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (ex: Exception) {
            throw AssertionError("timeout waiting for $what", ex)
        }
    }

    private suspend fun rate(engine: AcquisitionEngine, first: String, ms: Long): Double {
        delay(500)
        var last = engine.samples.value[first]!!.timestampMs
        var cycles = 0
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) {
            val ts = engine.samples.value[first]!!.timestampMs
            if (ts != last) {
                cycles++
                last = ts
            }
            delay(2)
        }
        return cycles * 1000.0 / (System.currentTimeMillis() - t0)
    }

    @Test
    fun `TestO reads and a one-off job over the adapter, read rate with Bluetooth latency`() = runBlocking {
        val catalog = EdiabasMeasurementCatalog(LiveTestData.ecuDir())
        val measurements = ids.map { catalog.measurement(EdiabasMeasurementCatalog.measurementId("msv70", it))!! }
        val sim = EcuSimulator(liveValues = true)
        val adapter = DeepObdAdapterSimulator(SimulatedKLine.withSimulator(sim, realisticTiming = true))
        val port = DeepObdPort(adapter)
        val engine = AcquisitionEngine(EdInterfaceObd(port), catalog)
        try {
            engine.setMeasurements(measurements)
            engine.start()
            awaitUntil(what = "first samples") { measurements.all { it.id in engine.samples.value } }
            assertEquals(0x0005, port.info!!.adapterType, "adapter identified while connecting")
            val noLatency = rate(engine, measurements[0].id, 2000)
            assertTrue(engine.status.value.message!!.contains("redefined each read"), engine.status.value.message)
            assertTrue(sim.recordDefinitions >= ids.size * 5L, "block defined on every read (JA)")

            // a one-off job (Tool32, faults) slips in between two cycles
            val bmwNr = engine.exclusive("Tool32: MSV70 IDENT") { it.executeJob("msv70", "IDENT")[1]["ID_BMW_NR"]!!.opData }
            assertEquals("0756123", bmwNr)
            // adapter settings are read between cycles too
            val voltage = engine.exclusive("Adapter voltage") { ((it.iface as EdInterfaceObd).port as DeepObdPort).readVoltage() }
            assertEquals(12.4, voltage!!, 1e-9)

            adapter.roundTripMs = 25 // typical SPP round trip phone <-> BC417 module
            val withLatency = rate(engine, measurements[0].id, 3000)
            println("DEEP OBD: %.1f reads/s without Bluetooth latency, %.1f with a 25 ms round trip".format(noLatency, withLatency))
            assertTrue(noLatency in 5.0..8.6, "at most 8 reads/s like the cable: $noLatency")
            assertTrue(withLatency in 2.0..(noLatency + 0.5), "not faster with latency: $withLatency")
        } finally {
            engine.close()
        }
    }
}
