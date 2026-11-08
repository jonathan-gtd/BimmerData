/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator

/**
 * Real MSV70 jobs executed by the interpreter against [FakeInterface] + [EcuSimulator].
 * The request frames printed here are exactly what the SGBD byte code hands to the interface
 * (BMW-FAST header without checksum; the interface appends the checksum).
 */
class JobExecutionTest {

    private class Run(val sets: List<Map<String, ResultData>>, val fake: FakeInterface, val initRequests: List<ByteArray>)

    private fun runJob(job: String, args: String = "", trace: Int = 0): Run {
        TestEcu.file("MSV70.PRG")
        val sim = EcuSimulator()
        val fake = FakeInterface(sim::respond)
        TestEcu.ediabas("msv70", trace = trace, log = trace > 0).use { ediabas ->
            ediabas.edInterfaceClass = fake
            fake.interfaceConnect()

            ediabas.executeJob("INITIALISIERUNG")
            val init = ediabas.resultSets!!
            assertEquals(1L, init[1]["DONE"]!!.opData, "INITIALISIERUNG DONE")
            val initRequests = fake.requests
            fake.exchanges.clear()

            ediabas.argString = args
            ediabas.executeJob(job) // must not throw
            val sets = ediabas.resultSets!!
            println("== $job($args)")
            fake.exchanges.forEach { println("  request : ${TestEcu.hex(it.request)}\n  response: ${TestEcu.hex(it.response)}") }
            sets.forEachIndexed { i, set -> println("  set $i: ${set.values.joinToString()}") }
            assertEquals(job, sets[0]["JOBNAME"]!!.opData)
            assertEquals((sets.size - 1).toLong(), sets[0]["SAETZE"]!!.opData)
            return Run(sets, fake, initRequests)
        }
    }

    private fun Run.jobStatus(): Any? = sets.last()["JOB_STATUS"]?.opData

    @Test
    fun `INITIALISIERUNG sets BMW-FAST communication parameters`() {
        TestEcu.file("MSV70.PRG")
        val fake = FakeInterface(EcuSimulator()::respond)
        TestEcu.ediabas("msv70").use { ediabas ->
            ediabas.edInterfaceClass = fake
            fake.interfaceConnect()
            ediabas.executeJob("INITIALISIERUNG")
            val params = fake.commParameter!!
            println("comm parameters: ${params.joinToString { "0x" + it.toString(16).uppercase() }}, answer length: ${fake.commAnswerLen.joinToString()}")
            println("INITIALISIERUNG requests: ${fake.requests.map { TestEcu.hex(it) }}")
            assertEquals(0x010Fu, params[0], "concept BMW-FAST")
            assertTrue(fake.bmwFastProtocol)
            // the job is executed twice (init job first because requestInit is set, then the job itself)
            assertEquals(2, fake.requests.size)
            fake.requests.forEach { assertEquals("82 12 F1 1A 80", TestEcu.hex(it)) }
        }
    }

    @Test
    fun `INFO needs no ECU traffic`() {
        val run = runJob("INFO")
        assertTrue(run.fake.exchanges.isEmpty(), "INFO sends nothing")
        val info = run.sets[1]
        assertEquals("MSV70 fuer N52 mit EWS3 oder CAS", info["ECU"]!!.opData)
        assertNotNull(info["ORIGIN"])
        assertNotNull(info["REVISION"])
        assertNotNull(info["AUTHOR"])
    }

    @Test
    fun `IDENT`() {
        val run = runJob("IDENT")
        assertEquals(1, run.fake.requests.size)
        assertEquals("82 12 F1 1A 80", TestEcu.hex(run.fake.requests[0]))
        assertEquals("OKAY", run.jobStatus())
        val r = run.sets[1]
        assertEquals("0756123", r["ID_BMW_NR"]!!.opData)
        assertEquals("17.10.0006", r["ID_DATUM"]!!.opData)
        assertArrayEquals(run.fake.requests[0], r["_TEL_AUFTRAG"]!!.opData as ByteArray)
    }

    @Test
    fun `STATUS_MESSWERTBLOCK_LESEN oil and coolant temperature`() {
        val run = runJob("STATUS_MESSWERTBLOCK_LESEN", "3;0x4402;0x4300")
        val frames = run.fake.requests.map { TestEcu.hex(it) }
        assertEquals(
            listOf(
                "83 12 F1 2C F0 04",
                "8E 12 F1 2C F0 02 01 02 44 02 01 02 03 01 43 00 01",
                "82 12 F1 21 F0",
            ),
            frames,
        )
        assertEquals("OKAY", run.jobStatus())
        val r = run.sets.last()
        assertEquals(90.0, r["STAT_OELTEMPERATUR_WERT"]!!.opData as Double, 1e-4)
        assertEquals("°C", r["STAT_OELTEMPERATUR_EINH"]!!.opData)
        assertEquals(88.5, r["STAT_KUEHLMITTELTEMPERATUR_WERT"]!!.opData as Double, 1e-4)
    }

    @Test
    fun `FS_LESEN two faults`() {
        val run = runJob("FS_LESEN")
        assertEquals(listOf("84 12 F1 18 02 FF FF"), run.fake.requests.map { TestEcu.hex(it) })
        assertEquals("OKAY", run.jobStatus())
        val faults = run.sets.drop(1).filter { it.containsKey("F_ORT_NR") }
        assertEquals(2, faults.size)
        assertEquals(0x29CDL, faults[0]["F_ORT_NR"]!!.opData)
        assertEquals("29CD Verbrennungsaussetzer, Zylinder 1", faults[0]["F_ORT_TEXT"]!!.opData)
        assertEquals("Fehler momentan vorhanden, OBD-entprellt", faults[1]["F_VORHANDEN_TEXT"]!!.opData)
    }

    @Test
    fun `silent ECU gives an IFH error, not an interpreter crash`() {
        TestEcu.file("MSV70.PRG")
        val sim = EcuSimulator()
        val fake = FakeInterface(sim::respond)
        TestEcu.ediabas("msv70").use { ediabas ->
            ediabas.edInterfaceClass = fake
            fake.interfaceConnect()
            ediabas.executeJob("INITIALISIERUNG")
            fake.responder = { null }
            val ex = runCatching { ediabas.executeJob("IDENT") }.exceptionOrNull()
            assertTrue(ex is EdiabasNetException, "got $ex")
            assertEquals(ErrorCodes.EDIABAS_IFH_0009, (ex as EdiabasNetException).errorCode)
            assertEquals(ErrorCodes.EDIABAS_IFH_0009, ediabas.errorCodeLast)
        }
    }

    @Test
    fun `group file D_MOTOR resolves to MSV70`() {
        TestEcu.file("D_MOTOR.GRP")
        val fake = FakeInterface(EcuSimulator()::respond)
        EdiabasNet("EcuPath=${TestEcu.dir().path}").use { ediabas ->
            ediabas.edInterfaceClass = fake
            fake.interfaceConnect()
            // IDENTIFIKATION of the group file reads 1A 80 and seeks "12 .34. 0000" in T_GRTB/ZuordnungsTabelle
            ediabas.resolveSgbdFile("d_motor")
            println("D_MOTOR identification requests: ${fake.requests.map { TestEcu.hex(it) }}")
            assertEquals("msv70.prg", ediabas.sgbdFileName)
            assertEquals("d_motor", ediabas.groupName)
            assertTrue(fake.requests.all { TestEcu.hex(it) == "82 12 F1 1A 80" })

            val count = fake.requests.size
            ediabas.resolveSgbdFile("D_MOTOR") // same name: nothing to do
            assertEquals(count, fake.requests.size)

            ediabas.executeJob("IDENT")
            val sets = ediabas.resultSets!!
            assertEquals("d_motor", sets[0]["GRUPPE"]!!.opData)
            assertEquals("MSV70", sets[0]["VARIANTE"]!!.opData)
            assertEquals("OKAY", sets.last()["JOB_STATUS"]!!.opData)
        }
    }
}
