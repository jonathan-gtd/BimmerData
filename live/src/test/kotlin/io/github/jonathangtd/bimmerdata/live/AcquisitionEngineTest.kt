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
    fun `fast path vs job path over the simulated K-line`() = runBlocking {
        val catalog = catalog()
        val sim = EcuSimulator(liveValues = true)
        val (line, iface) = kline(sim)
        val engine = AcquisitionEngine(iface, catalog)
        try {
            val fast = measure(engine, tableMeasurements(catalog), 3000)
            val fastStatus = engine.status.value
            val definitionsFast = sim.recordDefinitions
            engine.stopAndJoin()
            assertFalse(line.isOpen, "interface released after stop")
            assertEquals(AcquisitionState.Stopped, engine.status.value.state)

            val job = measure(engine, jobMeasurements(catalog), 3000)
            engine.stopAndJoin()

            println("FAST PATH (define once, 21 F0 only, native decoding): %.1f cycles/s (engine reports %.1f)".format(fast, fastStatus.cyclesPerSecond))
            println("JOB PATH  (STATUS_MESSWERTBLOCK_LESEN each cycle):     %.1f cycles/s".format(job))
            println("definitions during the fast-path run: ${definitionsFast / blockIds.size}")
            assertTrue(fastStatus.message!!.contains("native"), fastStatus.message)
            assertEquals(blockIds.size.toLong(), definitionsFast, "block defined once")
            assertTrue(fast > job * 1.6, "fast path must be clearly faster")
            assertTrue(fast in 12.0..30.0, "fast path rate plausible for K-line: $fast")
            assertTrue(job in 4.0..14.0, "job path rate plausible: $job")
            assertTrue(abs(fastStatus.cyclesPerSecond - fast) < fast * 0.3, "status cycles/s close to the measured rate")
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
    fun `redefines after an ECU reset and recovers from a silent ECU`() = runBlocking {
        val catalog = catalog()
        val sim = EcuSimulator(liveValues = true)
        val (line, iface) = kline(sim)
        fun inits() = line.requests.count { LiveTestData.hex(it).startsWith("82 12 F1 1A 80") }
        val engine = AcquisitionEngine(iface, catalog, AcquisitionEngine.Config(retryDelayMinMs = 50, retryDelayMaxMs = 400, reconnectAfterFailedCycles = 2))
        try {
            val ms = tableMeasurements(catalog)
            engine.setMeasurements(ms)
            engine.start()
            awaitUntil { sim.recordReads > 5 }
            assertEquals(1L, sim.recordDefinitions / blockIds.size)

            // ECU reset: the definition is lost, the next read gets 7F 21 31 -> immediate redefine
            sim.reset()
            val reads = sim.recordReads
            awaitUntil(what = "redefinition") { sim.recordDefinitions / blockIds.size == 2L && sim.recordReads > reads + 5 }
            assertEquals(AcquisitionState.Running, engine.status.value.state)
            assertEquals(0, engine.status.value.errorCount, "a lost definition is not an error")

            // silent ECU (e.g. ignition off): errors, backoff, reconnect, then back to running
            sim.silent = true
            awaitUntil(what = "error state") { engine.status.value.state == AcquisitionState.Error }
            delay(3000) // a silent read costs the 1.2 s BMW-FAST timeout
            val errors = engine.status.value.errorCount
            println("silent ECU: ${engine.status.value}")
            assertTrue(errors >= 2, "errors counted: $errors")
            sim.reset() // the ECU lost its definition meanwhile
            sim.silent = false
            awaitUntil(what = "running again") {
                engine.status.value.state == AcquisitionState.Running && engine.status.value.cyclesPerSecond > 5
            }
            println("recovered: ${engine.status.value}, definitions ${sim.recordDefinitions / blockIds.size}")
            assertTrue(sim.recordDefinitions / blockIds.size >= 3, "redefined after the silence")
            assertTrue(inits() >= 2, "INITIALISIERUNG ran again after the reconnect: ${inits()}")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an id refused by the ECU is isolated, the others stay on the fast path`() = runBlocking {
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
            assertTrue(engine.status.value.message!!.contains("block of 2 (native)"), engine.status.value.message)
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
            val table = catalog.measurements("msv70").filter {
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

            // the SGBD itself accepts at most 42 ids; the default plan uses one block up to that
            engine.setMeasurements(table)
            engine.stopAndJoin()
            val big = AcquisitionEngine(fake, catalog)
            big.setMeasurements(table)
            big.start()
            awaitUntil { big.status.value.message?.contains("native") == true }
            println("default plan for 25 ids: ${big.status.value.message}")
            assertEquals(1, Regex("block of 25").findAll(big.status.value.message!!).count())
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
