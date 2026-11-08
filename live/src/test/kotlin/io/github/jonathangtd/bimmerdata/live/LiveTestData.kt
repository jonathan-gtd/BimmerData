/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.charset.Charset

/**
 * The user's own EDIABAS ECU folder (BIMMERDATA_ECU_DIR) and Tool32 traces (BIMMERDATA_TRACE_DIR);
 * tests needing them are skipped otherwise. Nothing from there is ever committed.
 */
object LiveTestData {
    fun ecuDir(): File {
        val dir = System.getenv("BIMMERDATA_ECU_DIR")
        assumeTrue(dir != null, "BIMMERDATA_ECU_DIR not set")
        val file = File(dir!!)
        assumeTrue(file.isDirectory, "$dir is not a directory")
        assumeTrue(file.listFiles()?.any { it.name.equals("msv70.prg", true) } == true, "MSV70.PRG not found")
        return file
    }

    fun traceFile(name: String): File {
        val dir = System.getenv("BIMMERDATA_TRACE_DIR")
        assumeTrue(dir != null, "BIMMERDATA_TRACE_DIR not set")
        val file = File(dir!!).listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
        assumeTrue(file != null, "$name not found in $dir")
        return file!!
    }

    fun hex(data: ByteArray?): String = data?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "null"

    fun bytes(hex: String): ByteArray = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    // ---------------------------------------------------------------- api.trc (same parsing as Tool32ReferenceTest)

    /** One job of api.trc; result values as printed by Tool32 ("#hex" for binary results). */
    class ApiJob(val sgbd: String, val job: String, val args: String, val sets: List<Map<String, String>>)

    private val latin1: Charset = Charset.forName("windows-1252")

    fun parseApi(file: File): List<ApiJob> {
        val lines = file.readLines(latin1)
        val jobs = mutableListOf<ApiJob>()
        var i = 0
        val quoted = Regex("""^\s*"(.*)"\s*$""")
        val resultLine = Regex("""^\t(\S+) = (.*)$""")
        val dumpLine = Regex("""^\t\t[0-9A-F]{4}: ((?:[0-9A-F]{2} ){1,8})""")
        while (i < lines.size) {
            if (lines[i].trim() != "Job") { i++; continue }
            val q = mutableListOf<String>()
            i++
            while (i < lines.size && quoted.matches(lines[i])) { q += quoted.find(lines[i])!!.groupValues[1]; i++ }
            while (i < lines.size && !lines[i].startsWith("Results:") && lines[i].trim() != "Job") i++
            if (i >= lines.size || lines[i].trim() == "Job") continue
            i++
            val sets = mutableListOf<MutableMap<String, String>>()
            while (i < lines.size) {
                val l = lines[i]
                if (l.trim().startsWith("#@") || l.startsWith("[") && !l.startsWith("\t")) break
                if (l.trim().matches(Regex("""\[ \d+ ]"""))) { sets += LinkedHashMap(); i++; continue }
                val m = resultLine.find(l)
                if (m != null && sets.isNotEmpty()) {
                    val (name, value) = m.destructured
                    if (value.endsWith("Bytes:")) {
                        val sb = StringBuilder()
                        i++
                        while (i < lines.size && dumpLine.containsMatchIn(lines[i])) {
                            sb.append(dumpLine.find(lines[i])!!.groupValues[1]); i++
                        }
                        sets.last()[name] = "#" + sb.toString().trim()
                        continue
                    }
                    sets.last()[name] = value
                }
                i++
            }
            if (q.size >= 2) jobs += ApiJob(q[0], q[1], q.getOrElse(2) { "" }, sets)
        }
        return jobs
    }

    fun binary(value: String?): ByteArray? = value?.takeIf { it.startsWith("#") }?.let { bytes(it.substring(1)) }
}
