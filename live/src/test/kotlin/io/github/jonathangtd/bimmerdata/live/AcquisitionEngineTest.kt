/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.BmwFast
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceBase
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.FakeInterface
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AcquisitionEngineTest {

    private val blockIds = listOf(0x4807, 0x5804, 0x4300, 0x4200, 0x5A50, 0x5A51, 0x5A81, 0x5A82, 0x5A85, 0x5A86)

    private fun catalog() = EdiabasMeasurementCatalog(LiveTestData.ecuDir())

    private fun tableMeasurements(catalog: EdiabasMeasurementCatalog, ids: List<Int> = blockIds) =
        ids.map { catalog.measurement(EdiabasMeasurementCatalog.measurementId("msv70", it))!! }

    /** The same values through the generic job path: one STATUS_MESSWERTBLOCK_LESEN run per cycle. */
    private fun jobMeasurements(catalog: EdiabasMeasurementCatalog, ids: List<Int> = blockIds): List<Measurement> {
        val args = "3;" + ids.joinToString(";") { "0x%04X".format(it) }
        return tableMeasurements(catalog, ids).map { m ->
            val src = m.source as MeasurementSource.MeasurementTable
            m.copy(id = m.id + "/job", source = MeasurementSource.JobResult("STATUS_MESSWERTBLOCK_LESEN", args, src.resultName))
        }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, what: String = "condition", cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (ex: Exception) {
            throw AssertionError("timeout waiting for $what", ex)
        }
    }

    private fun kline(sim: EcuSimulator): Pair<SimulatedKLine, EdInterfaceObd> {
        val line = SimulatedKLine.withSimulator(sim, realisticTiming = true)
        return line to EdInterfaceObd(line)
    }

    /** Runs [measurements] for [ms] after a warm-up and returns the measured cycles per second. */
    private suspend fun measure(engine: AcquisitionEngine, measurements: List<Measurement>, ms: Long): Double {
        engine.setMeasurements(measurements)
        engine.start()
        awaitUntil(what = "first samples") { measurements.all { it.id in engine.samples.value } }
        delay(500)
        val start = engine.samples.value.values.maxOf { it.timestampMs }
        var cycles = 0
        val t0 = System.currentTimeMillis()
        var last = start
        while (System.currentTimeMillis() - t0 < ms) {
            val ts = engine.samples.value[measurements[0].id]!!.timestampMs
            if (ts != last) {
                cycles++
                last = ts
            }
            delay(2)
        }
        val measured = cycles * 1000.0 / (System.currentTimeMillis() - t0)
        println("   status: ${engine.status.value}")
        return measured
    }

    @Test
    fun `reads like TestO - the whole job (JA) every read, at most 8 reads per second`() = runBlocking {
        val catalog = catalog()
        val sim = EcuSimulator(liveValues = true)
        val (line, iface) = kline(sim)
        val engine = AcquisitionEngine(iface, catalog)
        try {
            val rate = measure(engine, tableMeasurements(catalog), 3000)
            val status = engine.status.value
            val stats = engine.readStats()
            println("read stats: ${stats.summary()}")
            println("TestO pace: %.1f reads/s (engine reports %.1f)".format(rate, status.cyclesPerSecond))
            // every read: clear F0, define F0, read F0 (like TestO / Deep OBD), never 21 F0 alone
            val sent = line.requestsSnapshot().map { LiveTestData.hex(it) }
            val reads = sent.indices.filter { sent[it].startsWith("82 12 F1 21 F0") }
            assertTrue(reads.size > 10, "reads: ${reads.size}")
            reads.drop(1).forEach { i ->
                assertTrue(sent[i - 1].contains(" 2C F0 02") && sent[i - 2].contains(" 2C F0 04"), "clear + define before read $i: ${sent.subList(i - 2, i + 1)}")
            }
            val block = stats.blocks.single()
            assertEquals("Native", block.mode, stats.summary())
            // one job may be running while the statistics are taken
            assertTrue(block.defines - block.reads in 0..1, "defined on every read: ${block.defines} / ${block.reads}")
            assertTrue(status.message!!.contains("block of 10 (redefined each read)"), status.message)
            assertTrue(rate in 5.0..8.6, "at most 8 reads/s: $rate")
            assertTrue(stats.medianCycleMs >= 120, stats.summary())
            assertEquals(125, stats.readIntervalMs)
            assertEquals(0, stats.slowDowns)
            assertEquals(0, stats.errorCount)
            engine.stopAndJoin()
            assertFalse(line.isOpen, "interface released after stop")
            assertEquals(AcquisitionState.Stopped, engine.status.value.state)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `the 8 reads per second are shared by the blocks of one ECU`() = runBlocking {
        val catalog = catalog()
        val sim = EcuSimulator(liveValues = true)
        val fake = FakeInterface(sim::respond)
        val engine = AcquisitionEngine(fake, catalog)
        try {
            val table = catalog.measurements("msv70").filter { it.source is MeasurementSource.MeasurementTable }.filter {
                catalog.table("msv70")!!.row((it.source as MeasurementSource.MeasurementTable).id)!!.conversionTable == null
            }.take(20)
            engine.setMeasurements(table)
            engine.start()
            awaitUntil { table.all { it.id in engine.samples.value } }
            val msg = engine.status.value.message!!
            println("plan: $msg")
            // 20 values with 16 per block: two balanced blocks of 10 taking turns
            assertEquals(2, Regex("block of 10 \\(redefined each read\\)").findAll(msg).count(), msg)
            fun reads() = fake.exchanges.count { LiveTestData.hex(BmwFast.parse(it.request)!!.data) == "21 F0" }
            delay(300)
            val reads0 = reads()
            val t0 = System.currentTimeMillis()
            delay(2000)
            val perSecond = (reads() - reads0) * 1000.0 / (System.currentTimeMillis() - t0)
            println("both blocks together: %.1f reads/s".format(perSecond))
            assertTrue(perSecond in 6.0..8.6, "8 reads/s for the ECU, not per block: $perSecond")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `live simulated values move and decode to plausible numbers`() = runBlocking {
        val catalog = catalog()
        var now = 0L
        val sim = EcuSimulator(liveValues = true, nanoTime = { now })
        val fake = FakeInterface(sim::respond)
        val engine = AcquisitionEngine(fake, catalog)
        try {
            val ms = tableMeasurements(catalog, blockIds + listOf(0x4402, 0x4600, 0x460A, 0x580E, 0x4508))
            engine.setMeasurements(ms)
            engine.start()
            awaitUntil { ms.all { it.id in engine.samples.value } }
            fun v(id: Int) = engine.samples.value[EdiabasMeasurementCatalog.measurementId("msv70", id)]!!.value!!
            val rpm0 = v(0x4807)
            val coolant0 = v(0x4300)
            assertTrue(rpm0 in 650.0..760.0, "idle rpm $rpm0")
            assertTrue(coolant0 in 15.0..25.0, "cold coolant $coolant0")
            now = 600_000_000_000L + 24_000_000_000L // 10 min + in a rev
            val t = engine.samples.value.values.maxOf { it.timestampMs }
            awaitUntil { engine.samples.value.values.minOf { it.timestampMs } > t }
            delay(50)
            println("after 10 min: " + ms.joinToString { "${it.label}=%.3f".format(engine.samples.value[it.id]!!.value) })
            assertTrue(v(0x4807) > 2000, "rev ${v(0x4807)}")
            assertTrue(v(0x4300) > 80, "warm coolant ${v(0x4300)}")
            assertTrue(v(0x4402) > 70, "warm oil ${v(0x4402)}")
            assertTrue(v(0x5A50) in 0.95..1.05, "lambda ${v(0x5A50)}")
            assertTrue(abs(v(0x5A81)) <= 3.01, "integrator ${v(0x5A81)}")
            assertTrue(v(0x460A) in 13.8..14.4, "battery ${v(0x460A)}")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an ECU reset changes nothing, a silent ECU slows the reads down, then back to 8 per second`() = runBlocking {
        val catalog = catalog()
        val sim = EcuSimulator(liveValues = true)
        val (line, iface) = kline(sim)
        fun inits() = line.requestsSnapshot().count { // the engine keeps sending meanwhile
            LiveTestData.hex(it).startsWith("82 12 F1 1A 80") }
        val engine = AcquisitionEngine(
            iface, catalog,
            AcquisitionEngine.Config(retryDelayMinMs = 50, retryDelayMaxMs = 400, reconnectAfterFailedCycles = 2, recoverAfterMs = 1500),
        )
        try {
            val ms = tableMeasurements(catalog)
            engine.setMeasurements(ms)
            engine.start()
            awaitUntil { sim.recordReads > 5 }

            // ECU reset: the definition is lost, but every read defines it again anyway
            sim.reset()
            val reads = sim.recordReads
            awaitUntil(what = "reads after the reset") { sim.recordReads > reads + 5 }
            assertEquals(AcquisitionState.Running, engine.status.value.state)
            assertEquals(0, engine.status.value.errorCount, "a reset ECU is no error")
            assertEquals(0, engine.readStats().slowDowns)

            // silent ECU (e.g. ignition off, ECU rebooting): errors, slow-down, reconnect
            sim.silent = true
            awaitUntil(what = "error state") { engine.status.value.state == AcquisitionState.Error }
            delay(3000) // a silent read costs the 1.2 s BMW-FAST timeout
            val silent = engine.readStats()
            println("silent ECU: ${engine.status.value}; ${silent.summary()}")
            assertTrue(engine.status.value.errorCount >= 2, "errors counted")
            assertTrue(silent.timeouts >= 1, "reads without answer counted: ${silent.summary()}")
            assertTrue(engine.currentReadIntervalMs > 125, "slowed down: ${engine.currentReadIntervalMs}")
            assertTrue(engine.currentReadIntervalMs <= 1000, "never slower than 1 s: ${engine.currentReadIntervalMs}")
            sim.silent = false
            awaitUntil(what = "running again") { engine.status.value.state == AcquisitionState.Running && engine.status.value.cyclesPerSecond > 0.5 }
            assertTrue(inits() >= 2, "INITIALISIERUNG ran again after the reconnect: ${inits()}")
            // quiet for recoverAfterMs: the interval halves step by step back to 125 ms
            awaitUntil(timeoutMs = 15_000, what = "back to 8 reads/s") { engine.currentReadIntervalMs == 125L }
            println("recovered: ${engine.status.value}; ${engine.readStats().summary()}")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an id refused by the ECU is isolated, the others are still read`() = runBlocking {
        val catalog = catalog()
        val sim = EcuSimulator()
        // this ECU does not know 0x4201: any definition containing it is refused (requestOutOfRange)
        val fake = FakeInterface { req ->
            val tel = BmwFast.parse(req)!!
            val d = tel.data
            val refuses = d.size > 3 && (d[0].toInt() and 0xFF) == 0x2C &&
                (2 until d.size - 4 step 6).any { i -> (d[i + 3].toInt() and 0xFF) == 0x42 && (d[i + 4].toInt() and 0xFF) == 0x01 }
            if (refuses) BmwFast.buildTelegram(tel.source, tel.target, byteArrayOf(0x7F, 0x2C, 0x31)) else sim.respond(req)
        }
        val engine = AcquisitionEngine(fake, catalog)
        try {
            val ms = tableMeasurements(catalog, listOf(0x4402, 0x4201, 0x4300))
            engine.setMeasurements(ms)
            engine.start()
            awaitUntil(what = "refused id isolated") { "msv70/0x4201" in engine.unsupported.value && "msv70/0x4300" in engine.samples.value }
            delay(200)
            println("refused: ${engine.unsupported.value}, status ${engine.status.value}")
            assertEquals(setOf("msv70/0x4201"), engine.unsupported.value)
            assertEquals(90.0, engine.samples.value["msv70/0x4402"]!!.value!!, 1e-5)
            assertEquals(88.5, engine.samples.value["msv70/0x4300"]!!.value!!, 1e-5)
            assertTrue(engine.status.value.message!!.contains("block of 2 (redefined each read)"), engine.status.value.message)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `more values than one block, mixed with a job group`() = runBlocking {
        val catalog = catalog()
        val sim = EcuSimulator()
        val fake = FakeInterface(sim::respond)
        val engine = AcquisitionEngine(fake, catalog, AcquisitionEngine.Config(maxIdsPerBlock = 10))
        try {
            val table = catalog.measurements("msv70").filter { it.source is MeasurementSource.MeasurementTable }.filter {
                val row = catalog.table("msv70")!!.row((it.source as MeasurementSource.MeasurementTable).id)!!
                row.conversionTable == null
            }.take(25)
            val job = Measurement(
                "msv70/job/oel", "msv70", "Oil (job)", "°C",
                MeasurementSource.JobResult("STATUS_MESSWERTBLOCK_LESEN", "3;0x4402", "STAT_OELTEMPERATUR_WERT"),
            )
            engine.setMeasurements(table + job)
            engine.start()
            awaitUntil { (table + job).all { it.id in engine.samples.value } && engine.status.value.message?.contains("block of") == true }
            val msg = engine.status.value.message!!
            println("plan: $msg")
            assertEquals(3, Regex("block of").findAll(msg).count(), "25 ids -> 3 blocks of at most 10")
            assertTrue(msg.contains("redefined each read"))
            assertTrue(msg.contains("STATUS_MESSWERTBLOCK_LESEN(3;0x4402)"))
            assertEquals(90.0, engine.samples.value["msv70/job/oel"]!!.value!!, 1e-5)
            assertTrue(engine.unsupported.value.isEmpty(), "${engine.unsupported.value}")

            // by default at most 16 values per block (TestO): 25 ids = two balanced blocks
            engine.setMeasurements(table)
            engine.stopAndJoin()
            val big = AcquisitionEngine(fake, catalog)
            big.setMeasurements(table)
            big.start()
            awaitUntil { big.status.value.message?.contains("block of 12") == true }
            println("default plan for 25 ids: ${big.status.value.message}")
            assertEquals(1, Regex("block of 13").findAll(big.status.value.message!!).count())
            assertEquals(1, Regex("block of 12").findAll(big.status.value.message!!).count())
            big.close()
        } finally {
            engine.close()
        }
    }

    @Test
    fun `SGBD limit - 42 ids per definition, 43 refused before sending`() {
        val catalog = catalog()
        val ids = catalog.table("msv70")!!.rows.filter { it.conversionTable == null }.take(43)
        val sim = EcuSimulator()
        val fake = FakeInterface(sim::respond)
        io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet("EcuPath=${catalog.ecuDir.path}").use { ed ->
            ed.edInterfaceClass = fake
            fake.interfaceConnect()
            val file = catalog.sgbdFile("msv70")!!.name
            val ok = MesswertBlockReader(ed, fake, file, catalog.table("msv70")!!, ids.take(42))
            val values = ok.defineAndRead()
            assertEquals(42, values.size)
            val define = fake.exchanges.first { (BmwFast.parse(it.request)!!.data.size) > 3 && it.request[3] == 0xFE.toByte() }
            println("42 ids: definition telegram of ${define.request.size} bytes (length byte 0x${"%02X".format(define.request[3])})")
            assertEquals(4 + 2 + 42 * 6, define.request.size)
            val ex = runCatching { MesswertBlockReader(ed, fake, file, catalog.table("msv70")!!, ids).defineAndRead() }.exceptionOrNull()
            assertNotNull(ex)
            assertTrue(ex is DefineRejectedException && ex.jobStatus == "ERROR_ARGUMENT", "$ex")
        }
    }

    @Test
    fun `stop and restart, and nothing is sent without measurements`() = runBlocking {
        val catalog = catalog()
        val sim = EcuSimulator()
        val fake = FakeInterface(sim::respond)
        val engine = AcquisitionEngine(fake, catalog)
        try {
            engine.start()
            awaitUntil { engine.status.value.state == AcquisitionState.Running }
            delay(100)
            assertEquals(0, fake.exchanges.size)
            engine.setMeasurements(tableMeasurements(catalog, listOf(0x4402)))
            awaitUntil { "msv70/0x4402" in engine.samples.value }
            engine.stop()
            awaitUntil { engine.status.value.state == AcquisitionState.Stopped }
            assertFalse(fake.connected)
            val n = fake.exchanges.size
            delay(100)
            assertEquals(n, fake.exchanges.size, "no traffic after stop")
            engine.start()
            awaitUntil { fake.exchanges.size > n + 3 }
            assertTrue(fake.connected)
        } finally {
            engine.close()
        }
    }

    @Suppress("unused")
    private fun EdInterfaceBase.describe() = "$interfaceName connected=$connected"
}
