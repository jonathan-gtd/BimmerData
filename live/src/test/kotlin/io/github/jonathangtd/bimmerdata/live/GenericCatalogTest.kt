/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Job-result measurements on real SGBDs (BIMMERDATA_ECU_DIR) and the read-only job rules. */
class GenericCatalogTest {

    private fun catalog() = EdiabasMeasurementCatalog(LiveTestData.ecuDir())

    private fun EdiabasMeasurementCatalog.byResult(sgbd: String, job: String, result: String): Measurement {
        val list = measurements(sgbd)
        return list.firstOrNull { (it.source as? MeasurementSource.JobResult)?.let { s -> s.job == job && s.resultName == result } == true }
            ?: throw AssertionError("$sgbd $job $result not in ${list.size} measurements: ${list.take(30).map { it.id }}")
    }

    private fun dump(catalog: EdiabasMeasurementCatalog, sgbd: String, n: Int = 25) {
        val list = catalog.measurements(sgbd)
        println("== $sgbd: ${list.size} measurements")
        list.take(n).forEach { println("   ${it.id}  |  ${it.label}  |  ${it.unit}  |  ${it.min}..${it.max}  |  ${it.kind}") }
    }

    @Test
    fun `MSV70 keeps its 465 table measurements first, jobs come after without duplicates`() {
        val c = catalog()
        val list = c.measurements("msv70")
        val table = list.filter { it.source is MeasurementSource.MeasurementTable }
        assertEquals(465, table.size)
        assertEquals(table, list.take(465), "MESSWERTETAB rows first")
        val tableResults = table.map { (it.source as MeasurementSource.MeasurementTable).resultName.uppercase() }.toSet()
        val jobs = list.drop(465)
        assertTrue(jobs.isNotEmpty(), "MSV70 also has argument-free STATUS jobs")
        assertTrue(jobs.none { (it.source as MeasurementSource.JobResult).resultName.uppercase() in tableResults }, "a table result is not repeated as a job result")
        assertEquals(list.size, list.map { it.id }.toSet().size, "ids are unique")
        dump(c, "msv70", 0)
        jobs.take(10).forEach { println("   ${it.id}  |  ${it.label}  |  ${it.unit}") }
    }

    @Test
    fun `gearbox GS19D - temperatures and speeds with units from the _EINH results`() {
        val c = catalog()
        dump(c, "gs19d")
        val temp = c.byResult("gs19d", "STATUS_GETRIEBETEMPERATUR", "STAT_GETRIEBETEMPERATUR_WERT")
        assertEquals("gs19d/STATUS_GETRIEBETEMPERATUR/STAT_GETRIEBETEMPERATUR_WERT", temp.id)
        assertEquals("Getriebeoeltemperatur", temp.label)
        assertEquals("°C", temp.unit)
        assertEquals(-40.0, temp.min)
        assertEquals(215.0, temp.max)
        val rpm = c.byResult("gs19d", "STATUS_MOTORDREHZAHL", "STAT_MOTORDREHZAHL_WERT")
        assertEquals("rpm", rpm.unit)
        assertEquals(8160.0, rpm.max)
        assertEquals("%", c.byResult("gs19d", "STATUS_FAHRPEDALWINKEL", "STAT_FAHRPEDALWINKEL_WERT").unit)
        assertEquals("km/h", c.byResult("gs19d", "STATUS_RADGESCHWINDIGKEITEN", "STAT_RADGESCHWINDIGKEITEN_WERT").unit)
        assertTrue(c.measurements("gs19d").none { (it.source as MeasurementSource.JobResult).resultName.endsWith("_EINH") }, "unit texts are not values")
    }

    @Test
    fun `instrument cluster KOMBI85 - tank, outside temperature, mileage`() {
        val c = catalog()
        dump(c, "kombi85")
        val tank = c.byResult("kombi85", "STATUS_AIF_TANKDATEN_LESEN", "STAT_TANKINHALT_GEF_WERT")
        assertEquals("l", tank.unit)
        val outside = c.byResult("kombi85", "STATUS_AIF_AUSSENTEMP_LESEN", "STAT_AUSSENTEMP_GEF_WERT")
        assertEquals("°C", outside.unit)
        assertEquals("Aussentemperatur (gefiltert)", outside.label)
        assertEquals("km", c.byResult("kombi85", "STATUS_AIF_GWSZ_LESEN", "STAT_GWSZ_WERT").unit)
        val reserve = c.byResult("kombi85", "STATUS_AIF_TANKDATEN_LESEN", "STAT_RESERVE")
        assertEquals(ValueKind.Text, reserve.kind)
    }

    @Test
    fun `footwell module FRM_87 - analog inputs in volts with their documented range`() {
        val c = catalog()
        dump(c, "frm_87")
        val kl30 = c.byResult("frm_87", "STATUS_ANALOG_INPUTS", "STAT_SPANNUNG_KLEMME_30A_WERT")
        assertEquals("V", kl30.unit)
        assertEquals("Spannung Klemme 30A", kl30.label)
        assertEquals(0.0, kl30.min)
        assertEquals(18.0, kl30.max)
        val jobs = c.measurements("frm_87").map { (it.source as MeasurementSource.JobResult).job }.toSet()
        assertFalse("STEUERN_LAMPEN_DIGITAL" in jobs)
        assertFalse("FS_LESEN" in jobs, "fault memory is not a live value")
    }

    @Test
    fun `CAS, IHKA and DSC expose status values`() {
        val c = catalog()
        for (sgbd in listOf("cas", "ihka87", "ihka87_2", "dsc_87", "ews3", "szl_60")) {
            dump(c, sgbd, 12)
            assertTrue(c.measurements(sgbd).size >= 5, "$sgbd has status values")
        }
        val key = c.byResult("cas", "STATUS_AKTUELL_SCHLUESSEL", "KEY_IS_IN_LOCK")
        assertEquals("Schlüssel gesteckt", key.label)
    }

    @Test
    fun `UDS SGBD - SG_FUNKTIONEN rows read with STATUS_LESEN`() {
        val c = catalog()
        dump(c, "ihka_f15")
        val list = c.measurements("ihka_f15")
        val udsReads = list.filter { (it.source as MeasurementSource.JobResult).job == "STATUS_LESEN" }
        assertTrue(udsReads.size > 20, "SG_FUNKTIONEN reads: ${udsReads.size}")
        val taster = udsReads.first { (it.source as MeasurementSource.JobResult).resultName == "STAT_TASTER_FBM_1_SENS_EIN" }
        assertEquals(MeasurementSource.JobResult("STATUS_LESEN", "ARG;TASTER_FBM_1_SENS_EIN", "STAT_TASTER_FBM_1_SENS_EIN"), taster.source)
        assertEquals("ihka_f15/STATUS_LESEN(ARG;TASTER_FBM_1_SENS_EIN)/STAT_TASTER_FBM_1_SENS_EIN", taster.id)
        assertNotNull(c.measurement(taster.id))
        // a row whose SERVICE is only 0x31 (routine) or 0x2E (write) is never offered
        assertTrue(udsReads.none { (it.source as MeasurementSource.JobResult).args.contains("SHZH_STEUERGERAET_RESET") })
        assertTrue(udsReads.none { (it.source as MeasurementSource.JobResult).args.contains("SHZH_KT_DOSIERPUMPE") })
    }

    /** Words that must never appear in a job the live catalog offers. */
    private val forbiddenWords = setOf(
        "STEUERN", "SCHREIBEN", "LOESCHEN", "FLASH", "START", "STOP", "RESET", "SLEEP", "ENERGIESPARMODE", "WRITE",
        "CLEAR", "AUTHENTISIERUNG", "SYSTEMCHECK", "DIAGNOSE", "MODE",
    )

    private fun forbidden(job: String): Boolean {
        val upper = job.uppercase()
        val words = upper.split('_')
        return words.any { it in forbiddenWords || it.startsWith("CODIER") } ||
            listOf("FS_", "IS_", "HS_", "C_", "_").any { upper.startsWith(it) }
    }

    @Test
    fun `every PRG - no writing or acting job is ever offered, counts and timings`() {
        val dir = LiveTestData.ecuDir()
        val c = EdiabasMeasurementCatalog(dir)
        val prgs = dir.listFiles()!!.filter { it.name.endsWith(".prg", true) }.map { it.name.lowercase().removeSuffix(".prg") }.sorted()
        val t0 = System.nanoTime()
        var total = 0
        var withAny = 0
        var withJobs = 0
        var table = 0
        var uds = 0
        var maxMs = 0.0
        var slowest = ""
        val jobsSeen = HashSet<String>()
        for (sgbd in prgs) {
            val t = System.nanoTime()
            val list = c.measurements(sgbd)
            val ms = (System.nanoTime() - t) / 1e6
            if (ms > maxMs) { maxMs = ms; slowest = sgbd }
            if (list.isNotEmpty()) withAny++
            total += list.size
            for (m in list) {
                when (val s = m.source) {
                    is MeasurementSource.MeasurementTable -> table++
                    is MeasurementSource.JobResult -> {
                        if (s.job == "STATUS_LESEN" && s.args.startsWith("ARG;")) uds++
                        jobsSeen += s.job
                        assertTrue(ReadOnlyJobs.isReadOnlyName(s.job), "$sgbd ${s.job}")
                        assertFalse(forbidden(s.job), "$sgbd offers ${s.job}")
                        assertTrue(s.args.isEmpty() || s.job == "STATUS_LESEN", "$sgbd ${s.job} with args ${s.args}")
                        assertTrue(m.id.startsWith("$sgbd/"), m.id)
                    }
                }
            }
            if (list.any { it.source is MeasurementSource.JobResult }) withJobs++
        }
        val allMs = (System.nanoTime() - t0) / 1e6
        println("ALL PRGs: ${prgs.size} SGBDs, $withAny with measurements ($withJobs with job results), $total measurements " +
            "($table MESSWERTETAB, $uds SG_FUNKTIONEN, ${total - table - uds} job results), ${jobsSeen.size} distinct jobs")
        println("measurements() of every SGBD: %.0f ms total, slowest %s %.0f ms".format(allMs, slowest, maxMs))
        println("jobs that are not STATUS_*: " + jobsSeen.filterNot { it.uppercase().startsWith("STATUS") }.sorted().joinToString())
        assertTrue(withAny > 1000, "most SGBDs are usable now: $withAny")
    }

    @Test
    fun `folder scan - descriptions, cache, timings`(@TempDir tmp: File) {
        val dir = LiveTestData.ecuDir()
        val cache = File(tmp, "catalog.tsv")
        val first = EdiabasMeasurementCatalog(dir, cache)
        val infos = first.sgbdInfos()
        val second = EdiabasMeasurementCatalog(dir, cache)
        val cached = second.sgbdInfos()
        println("scan: ${infos.size} SGBDs with measurements; first scan ${first.lastScanMs} ms (${first.lastScanParsed} files read), " +
            "from the cache ${second.lastScanMs} ms (${second.lastScanParsed} files read), ${Runtime.getRuntime().availableProcessors()} CPUs")
        assertEquals(infos, cached)
        assertEquals(0, second.lastScanParsed)
        assertTrue(cache.readLines().first() == EdiabasMeasurementCatalog.CacheHeader)
        val msv70 = infos.first { it.name == "msv70" }
        println("msv70: $msv70")
        assertTrue(msv70.description.contains("MSV70", ignoreCase = true), msv70.description)
        assertTrue(msv70.measurementCount >= 465)
        assertTrue(infos.first { it.name == "kombi85" }.description.contains("KOMBI", ignoreCase = true))
        listOf("frm_87", "cas", "gs19d", "ihka87", "dsc_87").forEach { n -> println("$n: ${infos.firstOrNull { it.name == n }}") }
        assertTrue(infos.none { it.name.endsWith(".grp") })

        // a changed file is read again, the others come from the cache
        val copyDir = File(tmp, "ecu").apply { mkdirs() }
        dir.listFiles()!!.filter { it.name.lowercase() in setOf("msv70.prg", "gs19d.prg", "kombi85.prg") }.forEach { it.copyTo(File(copyDir, it.name)) }
        val small = File(tmp, "small.tsv")
        assertEquals(3, EdiabasMeasurementCatalog(copyDir, small).sgbdInfos().size)
        File(copyDir, copyDir.list()!!.first { it.lowercase() == "gs19d.prg" }).setLastModified(System.currentTimeMillis() - 86_400_000)
        val again = EdiabasMeasurementCatalog(copyDir, small)
        assertEquals(3, again.sgbdInfos().size)
        assertEquals(1, again.lastScanParsed)
    }

    @Test
    fun `old cache format is ignored`(@TempDir tmp: File) {
        val dir = File(tmp, "ecu").apply { mkdirs() }
        val cache = File(tmp, "c.tsv").apply { writeText("msv70.prg\t1\t2\t1") }
        assertTrue(EdiabasMeasurementCatalog(dir, cache).sgbdInfos().isEmpty())
        assertEquals(EdiabasMeasurementCatalog.CacheHeader, cache.readLines().first(), "rewritten in the current format")
    }
}
