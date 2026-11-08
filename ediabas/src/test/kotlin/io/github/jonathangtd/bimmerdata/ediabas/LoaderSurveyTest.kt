/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Loads a sample of real PRG and GRP files (every n-th file of the ECU folder, or all of them with
 * BIMMERDATA_SURVEY_ALL=1) through the interpreter's virtual jobs and cross-checks job and table
 * lists against the independent [PrgFile] reader.
 */
class LoaderSurveyTest {

    private fun sample(ext: String, count: Int): List<File> {
        val files = TestEcu.dir().listFiles { f -> f.isFile && f.name.endsWith(ext, ignoreCase = true) }!!.sortedBy { it.name.lowercase() }
        if (System.getenv("BIMMERDATA_SURVEY_ALL") == "1") return files
        val step = maxOf(1, files.size / count)
        return files.filterIndexed { i, _ -> i % step == 0 }
    }

    private class Stats {
        var files = 0
        var jobs = 0
        var tables = 0
        var rows = 0
        var withUses = 0
        var baseJobs = 0
        var skipped = 0
    }

    private fun survey(file: File, stats: Stats, failures: MutableList<String>) {
        val prg = PrgFile.open(file)
        EdiabasNet("EcuPath=${file.parent}").use { ed ->
            ed.noInitForVJobs = true
            ed.sgbdFileName = file.name

            ed.executeJob("_JOBS")
            val jobs = ed.resultSets!!.drop(1).map { it["JOBNAME"]!!.opData as String }
            assertEquals(prg.jobs.map { it.name }, jobs, "${file.name}: jobs")

            ed.argString = "ALL"
            ed.executeJob("_JOBS")
            val allJobs = ed.resultSets!!.drop(1).size
            assertTrue(allJobs >= jobs.size)

            ed.executeJob("_VERSIONINFO")
            val info = ed.resultSets!![1]
            if (info.keys.any { it.startsWith("USES") }) {
                stats.withUses++
                // jobs inherited from the base SGBD(s) are only listed with the EdiabasLib "ALL" argument
                stats.baseJobs += allJobs - jobs.size
            }

            ed.executeJob("_TABLES")
            val tables = ed.resultSets!!.drop(1).map { it["TABLE"]!!.opData as String }
            assertEquals(prg.tables.map { it.name }, tables, "${file.name}: tables")

            for (t in prg.tables) {
                val expected = prg.table(t.name)!!
                ed.argString = t.name
                ed.executeJob("_TABLE")
                val lines = ed.resultSets!!.drop(1).map { set -> (0 until t.columnCount).map { set["COLUMN$it"]!!.opData as String } }
                assertEquals(listOf(expected.header) + expected.rows, lines, "${file.name}: table ${t.name}")
                stats.rows += lines.size
            }

            // descriptions of a few jobs
            for (job in jobs.take(5)) {
                ed.argString = job
                ed.executeJob("_ARGUMENTS")
                ed.argString = job
                ed.executeJob("_RESULTS")
            }
            stats.jobs += jobs.size
            stats.tables += tables.size
            stats.files++
        }
    }

    private fun run(files: List<File>, label: String) {
        val stats = Stats()
        val failures = ArrayList<String>()
        for (file in files) {
            // a few files in a real ECU folder are not object files at all (e.g. 2 byte stubs)
            if (file.length() < 0x100 || !PrgFile.open(file).isObjectFile) {
                stats.skipped++
                println("  skipped (not an EDIABAS object file): ${file.name}")
                continue
            }
            try {
                survey(file, stats, failures)
            } catch (e: Throwable) {
                failures.add("${file.name}: $e")
            }
        }
        println("$label: ${stats.files}/${files.size} files ok, ${stats.jobs} jobs, ${stats.tables} tables, ${stats.rows} table lines, ${stats.withUses} with USES (${stats.baseJobs} inherited jobs), ${stats.skipped} skipped")
        failures.forEach { println("  FAIL $it") }
        assertTrue(failures.isEmpty(), "${failures.size} files failed")
    }

    @Test
    fun `group files`() = run(sample(".grp", 30), "GRP sample")

    @Test
    fun `ecu files`() = run(sample(".prg", 40) + TestEcu.file("MSV70.PRG"), "PRG sample")
}
