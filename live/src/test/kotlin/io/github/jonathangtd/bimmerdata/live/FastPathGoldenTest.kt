/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.BmwFast
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.FakeInterface
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Golden test of the native fast-path decoder against the real car: the STATUS_MESSWERTBLOCK_LESEN
 * runs recorded by Tool32 (api.trc) hold the definition telegram, the ECU's answer to `21 F0` and
 * Tool32's decoded values. Our decoder must give the same values (7 significant digits, like
 * Tool32ReferenceTest).
 */
class FastPathGoldenTest {

    private class Run(val args: String, val define: ByteArray, val readRequest: ByteArray, val answer: ByteArray, val values: Map<String, Double>)

    private fun runs(): List<Run> {
        val jobs = LiveTestData.parseApi(LiveTestData.traceFile("api.trc"))
            .filter { it.job.equals("status_messwertblock_lesen", true) && it.sgbd.equals("msv70", true) }
        assumeTrue(jobs.isNotEmpty(), "no STATUS_MESSWERTBLOCK_LESEN in api.trc")
        return jobs.map { j ->
            val set = j.sets[1]
            Run(
                j.args,
                LiveTestData.binary(set["_TEL_AUFTRAG_2"])!!,
                LiveTestData.binary(set["_TEL_AUFTRAG_3"])!!,
                LiveTestData.binary(set["_TEL_ANTWORT_3"])!!,
                set.filterKeys { it.endsWith("_WERT") }.mapValues { it.value.toDouble() },
            )
        }
    }

    private fun same(ref: Double, ours: Double) = abs(ours - ref) <= 1e-6 * max(1.0, abs(ref))

    @Test
    fun `native decoding of the recorded 21 F0 answers equals Tool32`() {
        val tab = MesswerteTab.load("msv70", EdiabasMeasurementCatalog(LiveTestData.ecuDir()).sgbdFile("msv70")!!)!!
        val runs = runs()
        val tenValueRuns = runs.filter { it.args.split(';').size == 11 }
        println("api.trc: ${runs.size} STATUS_MESSWERTBLOCK_LESEN runs, ${tenValueRuns.size} with 10 values")
        var compared = 0
        val mismatches = ArrayList<String>()
        for (run in runs) {
            val layout = MesswertDecoder.layout(tab, run.define)!!
            assertEquals(run.args.split(';').drop(1).map { Integer.decode(it) }, layout.slots.map { it.row.id }, "layout follows the arguments")
            assertEquals("82 12 F1 21 F0", LiveTestData.hex(run.readRequest))
            val decoded = MesswertDecoder.decode(tab, layout, run.answer, 0) as MesswertDecoder.Decoded.Values
            println("== ${run.args}  answer ${LiveTestData.hex(run.answer)}")
            for ((slot, sample) in decoded.values) {
                val ref = run.values[slot.row.resultName]
                if (ref == null) {
                    mismatches += "${slot.row.resultName}: not in Tool32 results"
                    continue
                }
                compared++
                val ok = same(ref, sample.value!!)
                println("  ${if (ok) "ok" else "XX"} ${slot.row.resultName} = $ref   ours ${sample.value}")
                if (!ok) mismatches += "${run.args} ${slot.row.resultName}: Tool32 $ref, ours ${sample.value}"
            }
        }
        println("compared $compared values, ${mismatches.size} mismatches")
        // the trace holds 11 answers to 21 F0: one 2-value block (oil, coolant) and ten 10-value blocks
        assertTrue(runs.size >= 11, "expected the 11 recorded 21 F0 answers")
        assertTrue(tenValueRuns.size >= 10, "expected the 10 recorded 10-value runs")
        assertTrue(compared >= 2 + 10 * 10)
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `block reader defines once then decodes the recorded answers natively`() {
        val ecuDir = LiveTestData.ecuDir()
        val catalog = EdiabasMeasurementCatalog(ecuDir)
        val runs = runs().filter { it.args.split(';').size == 11 }
        assumeTrue(runs.size >= 2)
        val answers = ArrayDeque(runs.map { it.answer })
        val sim = EcuSimulator()
        // recorded answers for the reads, simulator for the rest (clear / define acknowledgements)
        val fake = FakeInterface { req ->
            val tel = BmwFast.parse(req)!!
            if (LiveTestData.hex(tel.data) == "21 F0") answers.removeFirst() else sim.respond(req)
        }
        EdiabasNet("EcuPath=${ecuDir.path}").use { ed ->
            ed.edInterfaceClass = fake
            fake.interfaceConnect()
            val tab = catalog.table("msv70")!!
            val rows = runs[0].args.split(';').drop(1).map { tab.row(Integer.decode(it))!! }
            val reader = MesswertBlockReader(ed, fake, catalog.sgbdFile("msv70")!!.name, tab, rows)
            var mismatches = 0
            runs.forEachIndexed { i, run ->
                val before = fake.exchanges.size
                val values = if (i == 0) reader.defineAndRead() else reader.read()
                if (i == 0) assertEquals(MesswertBlockReader.Mode.Native, reader.mode, reader.interpretedReason)
                if (i > 0) {
                    assertEquals(1, fake.exchanges.size - before, "one telegram per read")
                    assertEquals("82 12 F1 21 F0", LiveTestData.hex(fake.exchanges.last().request))
                }
                for ((row, sample) in values) {
                    if (!same(run.values[row.resultName]!!, sample.value!!)) mismatches++
                }
            }
            println("block reader: ${runs.size} reads, ${reader.defines} definition, $mismatches mismatches")
            assertEquals(1L, reader.defines)
            assertEquals(0, mismatches)
        }
    }
}
