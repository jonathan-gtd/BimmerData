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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Job groups next to the fast path: one job run for all its results, a minimum period, read-only jobs only. */
class JobScheduleTest {

    @Test
    fun `job period - minimum, proportional to the job duration, capped`() {
        val engine = AcquisitionEngine(FakeInterface { null }, EdiabasMeasurementCatalog(java.io.File(".")), AcquisitionEngine.Config(jobMinIntervalMs = 200, jobIntervalFactor = 2.0, jobMaxIntervalMs = 5000))
        assertEquals(200, engine.jobPeriod(20))
        assertEquals(1200, engine.jobPeriod(400))
        assertEquals(5000, engine.jobPeriod(4000))
        engine.close()
    }

    @Test
    fun `a job group shares the interface with a block, a writing job is never run`() = runBlocking {
        val catalog = EdiabasMeasurementCatalog(LiveTestData.ecuDir())
        val sim = EcuSimulator()
        val sent = java.util.concurrent.atomic.AtomicInteger()
        val fake = FakeInterface { req ->
            sent.incrementAndGet()
            sim.respond(req)
        }
        val engine = AcquisitionEngine(fake, catalog, AcquisitionEngine.Config(jobMinIntervalMs = 300))
        try {
            val table = listOf(0x4807, 0x4300).map { catalog.measurement(EdiabasMeasurementCatalog.measurementId("msv70", it))!! }
            // two results of the same job run: one group
            val job = listOf("STAT_OELTEMPERATUR_WERT", "STAT_KUEHLMITTELTEMPERATUR_WERT").mapIndexed { i, r ->
                Measurement("msv70/job/$i", "msv70", r, "°C", MeasurementSource.JobResult("STATUS_MESSWERTBLOCK_LESEN", "3;0x4402;0x4300", r))
            }
            val writing = Measurement("msv70/bad", "msv70", "bad", "", MeasurementSource.JobResult("STEUERN_EKP", "", "STAT_X"))
            engine.setMeasurements(table + job + writing)
            engine.start()
            withTimeout(10_000) { while (!(table + job).all { it.id in engine.samples.value }) delay(10) }
            assertTrue("msv70/bad" in engine.unsupported.value, "writing job refused: ${engine.unsupported.value}")
            assertFalse("msv70/bad" in engine.samples.value)

            // over 1.5 s: the block is read far more often than the job (period >= 300 ms)
            var jobReads = 0
            var blockReads = 0
            var lastJob = engine.samples.value[job[0].id]!!.timestampMs
            var lastBlock = engine.samples.value[table[0].id]!!.timestampMs
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 1500) {
                val s = engine.samples.value
                val j = s[job[0].id]!!.timestampMs
                val b = s[table[0].id]!!.timestampMs
                if (j != lastJob) { jobReads++; lastJob = j }
                if (b != lastBlock) { blockReads++; lastBlock = b }
                delay(1)
            }
            println("in 1.5 s: $jobReads job runs, $blockReads block reads; status ${engine.status.value.message}")
            assertTrue(jobReads in 3..6, "job period ~300 ms: $jobReads")
            assertTrue(blockReads > jobReads * 3, "the fast path is not slowed down: $blockReads")
            assertEquals(90.0, engine.samples.value[job[0].id]!!.value!!, 1e-5)
            assertEquals(engine.samples.value[job[0].id]!!.timestampMs, engine.samples.value[job[1].id]!!.timestampMs, "one run feeds both results")
        } finally {
            engine.close()
        }
    }
}
