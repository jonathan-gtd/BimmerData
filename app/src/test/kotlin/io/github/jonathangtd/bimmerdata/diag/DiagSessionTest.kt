/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.diag

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import io.github.jonathangtd.bimmerdata.live.AcquisitionEngine
import io.github.jonathangtd.bimmerdata.live.EdiabasMeasurementCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The mini Tool32 on the shared cable (acquisition engine without measurements), simulated MSV70, user's MSV70.PRG. */
class DiagSessionTest {

    private fun ecuDir(): File {
        val dir = System.getenv("BIMMERDATA_ECU_DIR")?.let(::File)
        assumeTrue(dir?.listFiles()?.any { it.name.equals("msv70.prg", ignoreCase = true) } == true, "BIMMERDATA_ECU_DIR with MSV70.PRG needed")
        return dir!!
    }

    private fun <T> withSession(line: SimulatedKLine = SimulatedKLine.withEcu(respond = EcuSimulator()::respond), block: suspend (DiagSession) -> T): T {
        val engine = AcquisitionEngine(EdInterfaceObd(line), EdiabasMeasurementCatalog(ecuDir()))
        engine.start()
        return try {
            runBlocking { block(DiagSession(engine, "test")) }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `run, frames and timing`() {
        withSession { s ->
            assertEquals(true, s.readIgnition())
            val run = s.runJob("msv70.prg", "STATUS_MESSWERTBLOCK_LESEN", "3;0x4402;0x4300")
            assertNull(run.error)
            assertEquals("OKAY", run.sets.last()["JOB_STATUS"]!!.opData)
            assertEquals(true, run.ignition)
            // INITIALISIERUNG (IDENT) + 3 telegrams of the job
            assertEquals(4, run.telegrams)
            assertEquals(4, run.frames.count { it.direction == EdInterfaceObd.FrameDirection.Tx })
            println("STATUS_MESSWERTBLOCK_LESEN incl. init: %.1f ms".format(run.elapsedNanos / 1e6))
            val again = s.runJob("msv70.prg", "STATUS_MESSWERTBLOCK_LESEN", "3;0x4402;0x4300")
            assertEquals(3, again.telegrams)
            println("STATUS_MESSWERTBLOCK_LESEN: %.1f ms".format(again.elapsedNanos / 1e6))
        }
    }

    @Test
    fun `cancel stops a job waiting for a silent ECU`() {
        val line = SimulatedKLine.withEcu(respond = EcuSimulator()::respond)
        withSession(line) { s ->
            s.runJob("msv70.prg", "IDENT", "")
            line.responder = { emptyList() }
            val start = System.nanoTime()
            val result = kotlinx.coroutines.coroutineScope {
                val run = async { s.runJob("msv70.prg", "IDENT", "") }
                delay(150)
                s.cancel()
                run.await()
            }
            val ms = (System.nanoTime() - start) / 1e6
            assertEquals("Cancelled", result.error)
            assertTrue(result.cancelled)
            assertTrue(ms < 800, "cancelled after $ms ms")
            // the cable is free again
            line.responder = SimulatedKLine.withEcu(respond = EcuSimulator()::respond).responder
            assertNull(s.runJob("msv70.prg", "IDENT", "").error)
        }
    }

    @Test
    fun `programming session and writes stop at the SafetyGuard`() {
        val line = SimulatedKLine.withEcu(respond = EcuSimulator()::respond)
        withSession(line) { s ->
            s.runJob("msv70.prg", "IDENT", "")
            for ((job, args) in listOf("DIAGNOSE_MODE" to "ECUPM", "PRUEFSTEMPEL_SCHREIBEN" to "1;2;3", "AUTHENTISIERUNG_START" to "")) {
                line.requests.clear()
                val run = s.runJob("msv70.prg", job, args)
                val blocked = run.frames.filter { it.direction == EdInterfaceObd.FrameDirection.Error }.map { it.text }
                println("$job($args) -> error=${run.error}, status=${run.sets.lastOrNull()?.get("JOB_STATUS")?.opData}, blocked=$blocked, sent=${line.requests.size}")
                if (job != "AUTHENTISIERUNG_START") assertTrue(run.error?.contains("SafetyGuard") == true, run.error)
                assertTrue(line.requests.none { req -> (req[3].toInt() and 0xFF) in setOf(0x27, 0x2E, 0x3B, 0x3D) || ((req[3].toInt() and 0xFF) == 0x10 && (req[4].toInt() and 0xFF) == 0x85) })
            }
        }
    }
}
