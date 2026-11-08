/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Virtual jobs (_JOBS, _TABLES, ...) on the real MSV70.PRG. */
class VirtualJobsTest {

    private fun run(jobName: String, args: String = ""): List<Map<String, ResultData>> {
        TestEcu.file("MSV70.PRG")
        TestEcu.ediabas("msv70").use { ediabas ->
            ediabas.noInitForVJobs = true
            ediabas.argString = args
            ediabas.executeJob(jobName)
            return ediabas.resultSets!!
        }
    }

    @Test
    fun `jobs list`() {
        val sets = run("_JOBS")
        val sys = sets[0]
        assertEquals("_JOBS", sys["JOBNAME"]!!.opData)
        assertEquals("MSV70", sys["VARIANTE"]!!.opData)
        assertEquals(331L, sys["SAETZE"]!!.opData)
        val names = sets.drop(1).map { it["JOBNAME"]!!.opData as String }
        assertEquals(331, names.size)
        listOf("INITIALISIERUNG", "IDENT", "FS_LESEN", "STATUS_MESSWERTBLOCK_LESEN").forEach { assertTrue(it in names, it) }
        println("first jobs: ${names.take(12)}")
    }

    @Test
    fun `tables list and table content`() {
        val tables = run("_TABLES").drop(1).map { it["TABLE"]!!.opData as String }
        assertEquals(82, tables.size)
        assertTrue("MESSWERTETAB" in tables)

        val rows = run("_TABLE", "MESSWERTETAB").drop(1)
        assertEquals(466, rows.size) // header line + 465 rows
        assertEquals("ARG", rows[0]["COLUMN0"]!!.opData)
        assertEquals("RESULTNAME", rows[0]["COLUMN2"]!!.opData)
        val oil = rows.first { it["COLUMN1"]!!.opData == "0x4402" }
        assertEquals("STAT_OELTEMPERATUR_WERT", oil["COLUMN2"]!!.opData)
        assertEquals("Öltemperatur", oil["COLUMN3"]!!.opData)
    }

    @Test
    fun `arguments and results of jobs`() {
        for (job in listOf("STATUS_MESSWERTBLOCK_LESEN", "FS_LESEN")) {
            val args = run("_ARGUMENTS", job).drop(1)
            val results = run("_RESULTS", job).drop(1)
            println("$job arguments: " + args.joinToString { "${it["ARG"]?.opData}(${it["ARGTYPE"]?.opData})" })
            println("$job results: " + results.joinToString { "${it["RESULT"]?.opData}(${it["RESULTTYPE"]?.opData})" })
            assertTrue(results.isNotEmpty(), "$job has results")
            assertTrue(results.all { it["RESULT"] != null && it["RESULTTYPE"] != null })
            assertTrue(results.any { it["RESULT"]!!.opData == "JOB_STATUS" }, "$job documents JOB_STATUS")
            if (job == "STATUS_MESSWERTBLOCK_LESEN") {
                assertTrue(args.isNotEmpty(), "$job has arguments")
                assertTrue(args.all { it["ARG"] != null && it["ARGTYPE"] != null })
            }
        }
        val comments = run("_JOBCOMMENTS", "FS_LESEN").drop(1)
        println("FS_LESEN comments: ${comments.firstOrNull()?.values}")
        assertEquals(1, comments.size)
    }

    @Test
    fun `version info`() {
        val info = run("_VERSIONINFO").drop(1).single()
        println("version info: ${info.values}")
        assertTrue((info["BIP_VERSION"]!!.opData as String).matches(Regex("\\d+\\.\\d+\\.\\d+")))
        assertTrue(info.containsKey("AUTHOR"))
        assertTrue(info.containsKey("REVISION"))
    }

    @Test
    fun `unknown job raises SYS-0008`() {
        TestEcu.file("MSV70.PRG")
        TestEcu.ediabas("msv70").use { ediabas ->
            val ex = runCatching { ediabas.executeJob("NO_SUCH_JOB") }.exceptionOrNull()
            assertTrue(ex is EdiabasNetException, "got $ex")
            assertEquals(ErrorCodes.EDIABAS_SYS_0008, (ex as EdiabasNetException).errorCode)
            assertEquals("SYS-0008: JOB NOT FOUND", EdiabasNet.getErrorDescription(ex.errorCode))
        }
    }
}
