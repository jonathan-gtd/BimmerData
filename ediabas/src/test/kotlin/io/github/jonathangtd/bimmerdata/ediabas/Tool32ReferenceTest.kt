/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.max

/**
 * Golden test against a real car: replays the ECU answers recorded by the original EDIABAS
 * (Tool32 with IfhTrace/ApiTrace) and checks that our interpreter produces exactly the same
 * job results as Tool32 did.
 *
 * Traces contain the user's own car data and are never committed: set BIMMERDATA_TRACE_DIR to a
 * folder holding `IFH.trc` (telegrams) and `api.trc` (jobs and results); skipped otherwise.
 */
class Tool32ReferenceTest {

    private val latin1: Charset = Charset.forName("windows-1252")

    // ---------------------------------------------------------------- IFH.trc: telegrams

    /** Request (hex, without checksum) -> answers in recorded order, from SENDECUTEL blocks. */
    private fun parseIfh(file: File): Map<String, List<ByteArray>> {
        val lines = file.readLines(latin1).filter { it.startsWith("_STD") }.map { it.removePrefix("_STD") }
        val result = LinkedHashMap<String, MutableList<ByteArray>>()
        var inSend = false
        var field: String? = null
        val input = StringBuilder()
        val output = StringBuilder()
        fun flush() {
            if (inSend && input.isNotBlank() && output.isNotBlank()) {
                result.getOrPut(norm(input.toString())) { mutableListOf() }.add(hex(output.toString()))
            }
            input.clear(); output.clear(); field = null
        }
        val hexOnly = Regex("""^\s+([0-9A-F]{2}( [0-9A-F]{2})*)\s*$""")
        for (line in lines) {
            val t = line.trim()
            when {
                t.matches(Regex("[A-Z]+")) -> { flush(); inSend = t == "SENDECUTEL" }
                t.startsWith("INPUT:") -> { field = "in"; input.append(' ').append(t.removePrefix("INPUT:")) }
                t.startsWith("OUTPUT:") -> { field = "out"; output.append(' ').append(t.removePrefix("OUTPUT:")) }
                t.startsWith("I -> D:") || t.startsWith("D -> I:") || t.startsWith("ERROR:") -> field = null
                hexOnly.matches(line) -> when (field) {
                    "in" -> input.append(' ').append(t)
                    "out" -> output.append(' ').append(t)
                }
            }
        }
        flush()
        return result
    }

    // ---------------------------------------------------------------- api.trc: jobs + results

    private class RefJob(val sgbd: String, val job: String, val args: String, val sets: List<Map<String, String>>)

    private fun parseApi(file: File): List<RefJob> {
        val lines = file.readLines(latin1)
        val jobs = mutableListOf<RefJob>()
        var i = 0
        val quoted = Regex("""^\s*"(.*)"\s*$""")
        val resultLine = Regex("""^\t(\S+) = (.*)$""")
        val dumpLine = Regex("""^\t\t[0-9A-F]{4}: ((?:[0-9A-F]{2} ){1,8})""")
        while (i < lines.size) {
            if (lines[i].trim() != "Job") { i++; continue }
            val q = mutableListOf<String>()
            i++
            while (i < lines.size && quoted.matches(lines[i])) { q += quoted.find(lines[i])!!.groupValues[1]; i++ }
            // results follow after the job time line
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
                        sets.last()[name] = "#" + norm(sb.toString())
                        continue
                    }
                    sets.last()[name] = value
                }
                i++
            }
            if (q.size >= 2) jobs += RefJob(q[0], q[1], q.getOrElse(2) { "" }, sets)
        }
        return jobs
    }

    // ---------------------------------------------------------------- comparison

    private fun norm(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").uppercase()
    private fun hex(s: String) = norm(s).split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /** Returns null when equal, else a description of the difference. */
    private fun compare(ref: String, ours: ResultData?): String? {
        if (ours == null) return "missing"
        val v = ours.opData
        return when {
            ref.startsWith("#") -> {
                val got = if (v is ByteArray) TestEcu.hex(v) else v.toString()
                if (got == ref.substring(1)) null else "bytes $got"
            }
            ref.startsWith("\"") -> {
                val s = ref.removeSurrounding("\"")
                if (v.toString() == s) null else "\"$v\""
            }
            Regex("""^[+-]?\d+$""").matches(ref) -> {
                val r = ref.removePrefix("+").toLong()
                val got = (v as? Number)?.toLong()
                if (got == r) null else "$v"
            }
            else -> {
                val r = ref.toDouble()
                val got = (v as? Number)?.toDouble() ?: return "$v (not a number)"
                // Tool32 prints 7 significant digits
                if (abs(got - r) <= 1e-6 * max(1.0, abs(r))) null else "$got"
            }
        }
    }

    @Test
    fun `interpreter reproduces Tool32 results from recorded car answers`() {
        val dir = System.getenv("BIMMERDATA_TRACE_DIR")
        assumeTrue(dir != null, "BIMMERDATA_TRACE_DIR not set")
        val ifhFile = File(dir!!).listFiles()?.firstOrNull { it.name.equals("ifh.trc", true) }
        val apiFile = File(dir).listFiles()?.firstOrNull { it.name.equals("api.trc", true) }
        assumeTrue(ifhFile != null && apiFile != null, "IFH.trc / api.trc not found in $dir")

        val answers = parseIfh(ifhFile!!)
        println("IFH.trc: ${answers.values.sumOf { it.size }} answers for ${answers.size} distinct requests")
        val jobs = parseApi(apiFile!!).filter { !it.job.startsWith("_") }
        println("api.trc: ${jobs.size} real jobs: ${jobs.joinToString { it.job }}")
        assumeTrue(jobs.isNotEmpty(), "no real job in api.trc")

        val queues = answers.mapValues { ArrayDeque(it.value) }
        val misses = mutableListOf<String>()
        val responder: (ByteArray) -> ByteArray? = { req ->
            val key = TestEcu.hex(req)
            val q = queues[key]
            when {
                q == null -> { misses += key; null }
                q.size > 1 -> q.removeFirst()
                else -> q.first() // last recorded answer is reused (e.g. extra init telegrams)
            }
        }

        val mismatches = mutableListOf<String>()
        var compared = 0
        TestEcu.dir()
        val bySgbd = jobs.groupBy { it.sgbd.lowercase() }
        for ((sgbd, sgbdJobs) in bySgbd) {
            val fake = FakeInterface(responder)
            TestEcu.ediabas(sgbd).use { ediabas ->
                ediabas.edInterfaceClass = fake
                fake.interfaceConnect()
                for (ref in jobs.filter { it.sgbd.lowercase() == sgbd }) {
                    ediabas.argString = ref.args
                    ediabas.executeJob(ref.job.uppercase())
                    val sets = ediabas.resultSets!!
                    println("== $sgbd ${ref.job}(${ref.args})")
                    for (setIdx in 1 until ref.sets.size) {
                        val ours = sets.getOrNull(setIdx) ?: run {
                            mismatches += "${ref.job} set $setIdx: missing set"; null
                        } ?: continue
                        for ((name, value) in ref.sets[setIdx]) {
                            compared++
                            val diff = compare(value, ours[name])
                            val mark = if (diff == null) "ok " else "XX "
                            println("  $mark[$setIdx] $name = $value" + (diff?.let { "   ours: $it" } ?: ""))
                            if (diff != null) mismatches += "${ref.job}(${ref.args}) [$setIdx] $name: Tool32 $value, ours $diff"
                        }
                    }
                }
            }
            check(sgbdJobs.isNotEmpty())
        }
        println("compared $compared results, ${mismatches.size} mismatches, ${misses.size} unrecorded requests")
        misses.distinct().forEach { println("  unrecorded request: $it") }
        mismatches.forEach { println("  MISMATCH $it") }
        assertTrue(compared > 0, "nothing compared")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} results differ from Tool32:\n" + mismatches.joinToString("\n"))
    }
}
