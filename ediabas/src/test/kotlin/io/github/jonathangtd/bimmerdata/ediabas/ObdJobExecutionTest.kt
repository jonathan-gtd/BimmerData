/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Real MSV70 jobs through the whole stack: interpreter -> [EdInterfaceObd] (BMW-FAST, checksum,
 * echo) -> [SimulatedKLine] (echo, byte timing, ECU delay) -> [EcuSimulator].
 */
class ObdJobExecutionTest {

    private class Stack(val ediabas: EdiabasNet, val iface: EdInterfaceObd, val line: SimulatedKLine)

    private fun stack(realisticTiming: Boolean = true): Stack {
        TestEcu.file("MSV70.PRG")
        val line = SimulatedKLine.withEcu(realisticTiming, respond = EcuSimulator()::respond)
        val iface = EdInterfaceObd(line)
        val ediabas = TestEcu.ediabas("msv70")
        ediabas.edInterfaceClass = iface
        assertTrue(iface.interfaceConnect())
        return Stack(ediabas, iface, line)
    }

    private fun Stack.run(job: String, args: String = ""): List<Map<String, ResultData>> {
        line.requests.clear()
        ediabas.argString = args
        val start = System.nanoTime()
        ediabas.executeJob(job)
        val ms = (System.nanoTime() - start) / 1e6
        val sets = ediabas.resultSets!!
        println("== $job($args) %.1f ms".format(ms))
        line.requests.forEach { println("  sent: ${TestEcu.hex(it)}") }
        assertEquals("OKAY", sets.last()["JOB_STATUS"]!!.opData, "$job JOB_STATUS")
        return sets
    }

    private fun withChecksum(hex: String): String {
        val b = EdInterfaceObdTest.bytes(hex)
        return TestEcu.hex(b + EdInterfaceBase.calcChecksumBmwFast(b, b.size))
    }

    @Test
    fun `IDENT, STATUS_MESSWERTBLOCK_LESEN and FS_LESEN over the simulated K-line`() {
        val s = stack()
        s.ediabas.use {
            val ident = s.run("IDENT")
            assertEquals(115200, s.line.baudRate, "INITIALISIERUNG switched the cable to 115200 baud")
            assertEquals(0, s.line.checksumErrors)
            assertEquals("0756123", ident[1]["ID_BMW_NR"]!!.opData)
            assertEquals("17.10.0006", ident[1]["ID_DATUM"]!!.opData)

            val status = s.run("STATUS_MESSWERTBLOCK_LESEN", "3;0x4402;0x4300")
            assertEquals(
                listOf("83 12 F1 2C F0 04", "8E 12 F1 2C F0 02 01 02 44 02 01 02 03 01 43 00 01", "82 12 F1 21 F0").map(::withChecksum),
                s.line.requests.map { TestEcu.hex(it) },
            )
            val r = status.last()
            assertEquals(90.0, r["STAT_OELTEMPERATUR_WERT"]!!.opData as Double, 1e-4)
            assertEquals(88.5, r["STAT_KUEHLMITTELTEMPERATUR_WERT"]!!.opData as Double, 1e-4)

            val fs = s.run("FS_LESEN")
            assertEquals(listOf(withChecksum("84 12 F1 18 02 FF FF")), s.line.requests.map { TestEcu.hex(it) })
            val faults = fs.drop(1).filter { it.containsKey("F_ORT_NR") }
            assertEquals(2, faults.size)
            assertEquals("29CD Verbrennungsaussetzer, Zylinder 1", faults[0]["F_ORT_TEXT"]!!.opData)
            assertEquals(0, s.line.checksumErrors)
        }
    }

    @Test
    fun `silent ECU fails the job with IFH-0009`() {
        val s = stack()
        s.ediabas.use {
            s.run("IDENT")
            s.line.responder = { emptyList() }
            val ex = runCatching { s.ediabas.executeJob("IDENT") }.exceptionOrNull()
            assertTrue(ex is EdiabasNetException, "got $ex")
            assertEquals(ErrorCodes.EDIABAS_IFH_0009, (ex as EdiabasNetException).errorCode)
        }
    }

    /**
     * Software cost per telegram with a zero-delay line: interpreter + interface + simulator
     * bookkeeping. The regeneration time is set to 0 after INITIALISIERUNG and any remaining
     * protocol wait is subtracted, so only CPU work is left.
     */
    @Test
    fun `software overhead per telegram`() {
        val s = stack(realisticTiming = false)
        s.ediabas.use {
            s.run("IDENT") // runs INITIALISIERUNG first
            s.iface.commParameter = uintArrayOf(0x010Fu, 115200u, 1200u, 0u, 10u, 2u, 5000u) // regen 0

            fun measure(job: String, args: String, iterations: Int): Pair<Double, Double> {
                repeat(50) { // JIT warm-up
                    s.ediabas.argString = args
                    s.ediabas.executeJob(job)
                }
                s.iface.resetStats()
                val start = System.nanoTime()
                repeat(iterations) {
                    s.ediabas.argString = args
                    s.ediabas.executeJob(job)
                }
                val total = System.nanoTime() - start - s.iface.stats.regenWaitNanos
                val telegrams = s.iface.stats.telegrams
                val perJobMs = total / 1e6 / iterations
                val perTelegramMs = total / 1e6 / telegrams
                println("OVERHEAD $job: %d runs, %d telegrams, %.3f ms/job, %.3f ms/telegram".format(iterations, telegrams, perJobMs, perTelegramMs))
                return Pair(perJobMs, perTelegramMs)
            }

            val (_, identPerTel) = measure("IDENT", "", 500)
            val (_, statusPerTel) = measure("STATUS_MESSWERTBLOCK_LESEN", "3;0x4402;0x4300", 500)
            measure("FS_LESEN", "", 500)
            // a K-line telegram exchange at 115200 baud takes > 5 ms on the wire alone
            assertTrue(identPerTel < 5.0 && statusPerTel < 5.0, "software must not be the bottleneck")
        }
    }
}
