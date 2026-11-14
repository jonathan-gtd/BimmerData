/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.FakeInterface
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reading stopped by the user while the interface stays connected ([AcquisitionEngine.setPaused]). */
class ReadingPauseTest {

    private val catalog = EdiabasMeasurementCatalog(LiveTestData.ecuDir())

    private fun table(vararg ids: Int) = ids.map { catalog.measurement(EdiabasMeasurementCatalog.measurementId("msv70", it))!! }

    private suspend fun awaitUntil(what: String, cond: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!cond()) delay(10) }
        } catch (ex: Exception) {
            throw AssertionError("timeout waiting for $what", ex)
        }
    }

    @Test
    fun `paused - stays connected, sends nothing, one-off operations still run, resume reads at once`() = runBlocking {
        val fake = FakeInterface(EcuSimulator()::respond)
        val engine = AcquisitionEngine(fake, catalog)
        try {
            val m = table(0x4807, 0x4402)
            engine.setMeasurements(m)
            engine.start()
            awaitUntil("first samples") { m.all { it.id in engine.samples.value } }

            engine.setPaused(true)
            awaitUntil("paused state") { engine.status.value.state == AcquisitionState.Paused }
            assertEquals(0.0, engine.status.value.cyclesPerSecond)
            delay(150) // a read in progress when pausing ends
            val n = fake.exchanges.size
            delay(500)
            assertEquals(n, fake.exchanges.size, "no request while paused")
            assertTrue(fake.connected, "the interface stays connected")

            // a one-off operation (fault memory, Tool32...) still goes through
            val answer = engine.exclusive("test job") { a -> a.executeJob("msv70", "IDENT").size }
            assertTrue(answer > 0)
            val afterOp = fake.exchanges.size
            assertTrue(afterOp > n, "the operation talked to the car")
            delay(300)
            assertEquals(afterOp, fake.exchanges.size, "still nothing read after the operation")
            assertEquals(AcquisitionState.Paused, engine.status.value.state)

            // changing the measurements does not restart the reading either
            engine.setMeasurements(table(0x4807, 0x4402, 0x4300))
            delay(300)
            assertEquals(afterOp, fake.exchanges.size, "a new measurement set is not read while paused")

            val before = engine.samples.value.getValue(m[0].id).timestampMs
            engine.setPaused(false)
            awaitUntil("reading again") { engine.samples.value.getValue(m[0].id).timestampMs > before }
            awaitUntil("running state") { engine.status.value.state == AcquisitionState.Running }
            assertTrue(fake.exchanges.size > afterOp)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `paused before the first connection - connects, then reads nothing until resumed`() = runBlocking {
        val fake = FakeInterface(EcuSimulator()::respond)
        val engine = AcquisitionEngine(fake, catalog)
        try {
            val m = table(0x4807)
            engine.setPaused(true)
            engine.setMeasurements(m)
            engine.start()
            awaitUntil("paused state") { engine.status.value.state == AcquisitionState.Paused }
            assertEquals(AcquisitionEngine.LinkState.Up, engine.link.value)
            delay(300)
            assertTrue(m[0].id !in engine.samples.value, "nothing read")
            engine.setPaused(false)
            awaitUntil("first sample") { m[0].id in engine.samples.value }
        } finally {
            engine.close()
        }
    }
}
