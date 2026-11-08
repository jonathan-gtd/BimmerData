/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import io.github.jonathangtd.bimmerdata.ediabas.FakeInterface
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.VehicleSimulator
import io.github.jonathangtd.bimmerdata.live.AcquisitionEngine
import io.github.jonathangtd.bimmerdata.live.AcquisitionPause
import io.github.jonathangtd.bimmerdata.live.CableUnavailableException
import io.github.jonathangtd.bimmerdata.live.CsvSessionRecorder
import io.github.jonathangtd.bimmerdata.live.EdiabasMeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.OfflineSgbd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * The single cable manager: the acquisition engine owns the interface, one-off operations
 * (Tool32, vehicle scan, faults) run between two read cycles. Simulated E90 with the MSV70-like
 * engine ECU on a [FakeInterface]; needs the user's ECU folder (skipped otherwise).
 */
class SharedCableTest {

    private val blockIds = listOf(0x4807, 0x5804, 0x4300, 0x4200, 0x4402)

    private class Rig(val engine: AcquisitionEngine, val fake: FakeInterface, val dme: EcuSimulator, val sim: VehicleSimulator, val measurements: List<Measurement>)

    private fun rig(config: AcquisitionEngine.Config = AcquisitionEngine.Config()): Rig {
        val dir = TestEcuFolder.dir()
        val catalog = EdiabasMeasurementCatalog(dir)
        val dme = EcuSimulator(liveValues = true)
        val sim = VehicleSimulator.e90(dme)
        val fake = FakeInterface()
        fake.multiResponder = sim::respond
        val engine = AcquisitionEngine(fake, catalog, config)
        val ms = blockIds.map { catalog.measurement(EdiabasMeasurementCatalog.measurementId("msv70", it))!! }
        return Rig(engine, fake, dme, sim, ms)
    }

    private suspend fun Rig.startReading() {
        engine.setMeasurements(measurements)
        engine.start()
        awaitUntil("first samples") { measurements.all { it.id in engine.samples.value } }
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(5) }
        } catch (ex: Exception) {
            throw AssertionError("timeout waiting for $what", ex)
        }
    }

    private fun sid(req: ByteArray): Int = BmwFastData.sid(req)

    private fun target(req: ByteArray): Int = if (req.size > 1) req[1].toInt() and 0xFF else -1

    @Test
    fun `one-off jobs interleave with the read cycles and the reading goes on`() = runBlocking {
        val r = rig()
        try {
            r.startReading()
            val runner = SharedJobRunner(r.engine, "Test")
            val before = r.engine.exclusive("snapshot") { r.fake.requests.size }
            repeat(5) {
                val out = runner.run("komb87", "IDENT")
                assertTrue(out.ok, out.error)
                assertNotNull(out.sets.firstOrNull()?.get("ID_BMW_NR"), "KOMBI identification")
            }
            val after = r.engine.exclusive("snapshot") { r.fake.requests.drop(before) }
            val identIdx = after.indices.filter { target(after[it]) == 0x60 && sid(after[it]) == 0x1A }
            assertEquals(5, identIdx.size, "five KOMBI identifications")
            // between two one-off jobs the engine ran at least one read of the DME block (21 F0)
            identIdx.zipWithNext().forEach { (a, b) ->
                assertTrue((a + 1 until b).any { target(after[it]) == 0x12 && sid(after[it]) == 0x21 }, "a read cycle between jobs $a and $b")
            }
            // a job of another ECU does not force a redefinition of the DME block
            assertEquals(blockIds.size.toLong(), r.dme.recordDefinitions, "block still defined once")
            assertEquals(7L, r.engine.exclusiveRuns)
            val t = r.engine.samples.value[r.measurements[0].id]!!.timestampMs
            awaitUntil("reading resumes") { r.engine.samples.value[r.measurements[0].id]!!.timestampMs > t }
        } finally {
            r.engine.close()
        }
    }

    @Test
    fun `a job on the fast-path SGBD makes the engine define its block again`() = runBlocking {
        val r = rig()
        try {
            r.startReading()
            val defines = r.dme.recordDefinitions
            // Tool32-style: the user's own STATUS_MESSWERTBLOCK_LESEN with another id list redefines F0
            val sets = r.engine.exclusive("Tool32: MSV70 STATUS_MESSWERTBLOCK_LESEN") { a ->
                a.executeJob("msv70", "STATUS_MESSWERTBLOCK_LESEN", "3;0x4600")
            }
            assertEquals("OKAY", sets.getOrNull(1)?.get("JOB_STATUS")?.opData)
            assertEquals(defines + 1, r.dme.recordDefinitions, "the user's job defined F0 with one value")
            awaitUntil("block defined again") { r.dme.recordDefinitions >= defines + 1 + blockIds.size }
            val t = System.currentTimeMillis()
            awaitUntil("fresh samples after the redefinition") { r.measurements.all { (r.engine.samples.value[it.id]?.timestampMs ?: 0) >= t } }
            val rpm = r.engine.samples.value[r.measurements[0].id]!!.value!!
            val oil = r.engine.samples.value[r.measurements.last().id]!!.value!!
            assertTrue(rpm in 500.0..7500.0, "rpm decoded with the right layout: $rpm")
            assertTrue(oil in -40.0..160.0, "oil temperature decoded with the right layout: $oil")
            assertTrue(r.engine.status.value.message!!.contains("native"), r.engine.status.value.message)
        } finally {
            r.engine.close()
        }
    }

    @Test
    fun `cancelling a long operation aborts it and frees the cable`() = runBlocking {
        val r = rig()
        try {
            r.startReading()
            val started = CompletableDeferred<Unit>()
            var runs = 0
            val long = async(Dispatchers.Default) {
                r.engine.exclusive("Tool32: repeat") { a ->
                    started.complete(Unit)
                    while (true) {
                        a.executeJob("komb87", "IDENT") // throws once cancelled
                        runs++
                    }
                }
            }
            withTimeout(5000) { started.await() }
            delay(200)
            long.cancel()
            val t0 = System.currentTimeMillis()
            val out = withTimeout(5000) { SharedJobRunner(r.engine).run("cas", "IDENT") }
            assertTrue(out.ok, out.error)
            assertTrue(System.currentTimeMillis() - t0 < 3000, "next operation runs right away")
            assertTrue(runs > 0)
            val t = r.engine.samples.value[r.measurements[0].id]!!.timestampMs
            awaitUntil("reading resumes after the cancelled operation") { r.engine.samples.value[r.measurements[0].id]!!.timestampMs > t }
        } finally {
            r.engine.close()
        }
    }

    @Test
    fun `a long operation writes a pause line in the CSV recording`() = runBlocking {
        val r = rig(AcquisitionEngine.Config(pauseThresholdMs = 300))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val out = ByteArrayOutputStream()
        try {
            r.startReading()
            val recorder = CsvSessionRecorder.forEngine(r.engine, { out }, scope)
            val pause = async { r.engine.pauses.first() }
            recorder.start()
            awaitUntil("rows before the pause") { recorder.state.value.rows >= 3 }
            r.engine.exclusive("Faults: E89X FS_LESEN_FUNKTIONAL") { Thread.sleep(600) }
            // a short operation is no pause
            r.engine.exclusive("short") { }
            val p: AcquisitionPause = withTimeout(5000) { pause.await() }
            assertTrue(p.durationMs in 550..3000, "pause ${p.durationMs} ms")
            val rows = recorder.state.value.rows
            awaitUntil("rows after the pause") { recorder.state.value.rows >= rows + 3 }
            recorder.stop()
            delay(300)
            val lines = out.toString(Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }
            val pauseLines = lines.filter { it.substringAfterLast(';').startsWith("pause ") }
            assertEquals(1, pauseLines.size, lines.joinToString("\n"))
            val cells = pauseLines[0].split(';')
            assertTrue(cells.last().matches(Regex("pause \\d+ ms: Faults: E89X FS_LESEN_FUNKTIONAL")), cells.last())
            assertTrue(cells.subList(2, cells.size - 1).all { it.isEmpty() }, "no value on the pause line: ${pauseLines[0]}")
            val i = lines.indexOf(pauseLines[0])
            assertTrue(i in 2 until lines.size - 1, "pause line between value rows")
            val time = { line: String -> line.substringBefore(';').toLong() }
            assertTrue(time(lines[i - 1]) <= time(lines[i]) && time(lines[i]) <= time(lines[i + 1]), "times stay in order")
            assertTrue(time(lines[i + 1]) - time(lines[i]) >= 550, "the gap follows the pause line")
        } finally {
            scope.cancel()
            r.engine.close()
        }
    }

    @Test
    fun `disconnecting during an operation fails it and the queued ones, then refuses new ones`(): Unit = runBlocking {
        val r = rig()
        try {
            r.startReading()
            val started = CompletableDeferred<Unit>()
            val long = async(Dispatchers.Default) {
                runCatching {
                    r.engine.exclusive("long") { a ->
                        started.complete(Unit)
                        while (true) a.executeJob("komb87", "IDENT")
                    }
                }
            }
            withTimeout(5000) { started.await() }
            val queued = async(Dispatchers.Default) { runCatching { r.engine.exclusive("queued") { 42 } } }
            delay(100)
            r.engine.stopAndJoin()
            val first = withTimeout(5000) { long.await() }
            val second = withTimeout(5000) { queued.await() }
            assertTrue(first.isFailure, "running operation aborted")
            assertTrue(second.exceptionOrNull() is CableUnavailableException, "queued operation failed: $second")
            assertThrows(CableUnavailableException::class.java) { runBlocking { r.engine.exclusive("after") { 1 } } }
            Unit
        } finally {
            r.engine.close()
        }
    }

    @Test
    fun `operations run back to back when nothing is read`() = runBlocking {
        val r = rig()
        try {
            r.engine.start()
            val runner = SharedJobRunner(r.engine)
            val results = (1..3).map { launchRun(runner) }
            results.forEach { assertTrue(withTimeout(10_000) { it.await() }.ok) }
            assertTrue(r.engine.exclusive("snapshot") { r.fake.requests.toList() }.none { sid(it) == 0x21 || sid(it) == 0x2C }, "no live traffic without measurements")
        } finally {
            r.engine.close()
        }
    }

    private fun CoroutineScope.launchRun(runner: SharedJobRunner) = async(Dispatchers.Default) { runner.run("cas", "IDENT") }

    @Test
    fun `vehicle scan and fault read through the shared cable while live values are read`() = runBlocking {
        val r = rig()
        try {
            r.startReading()
            val tables = VehicleTables(TestEcuFolder.dir())
            val runner = SharedJobRunner(r.engine, "Faults")
            var liveUpdates = 0
            var last = 0L
            val watcher = launch(Dispatchers.Default) {
                r.engine.samples.collect { s ->
                    val ts = s[r.measurements[0].id]?.timestampMs ?: return@collect
                    if (ts != last) liveUpdates++
                    last = ts
                }
            }
            val vehicle = VehicleScanner(runner, tables).scan(options = VehicleScanner.Options(probeBuses = false)).vehicle!!
            assertEquals(r.sim.ecus.map { it.address }, vehicle.ecus.map { it.address })
            val faults = FaultService(runner, tables).readAll(vehicle)
            assertTrue(faults.first { it.address == 0x12 }.faults.any { it.code == 0x2F08 }, "DME fault 2F08")
            watcher.cancel()
            assertTrue(liveUpdates >= 10, "live values kept updating during the scan: $liveUpdates")
        } finally {
            r.engine.close()
        }
    }

    @Test
    fun `job list of an SGBD without any connection`() {
        val dir = TestEcuFolder.dir()
        OfflineSgbd(dir).use { s ->
            val jobs = s.jobs("msv70")
            assertTrue("IDENT" in jobs && "STATUS_MESSWERTBLOCK_LESEN" in jobs && "FS_LESEN" in jobs, jobs.take(10).toString())
            assertTrue(s.jobs("komb87.prg").contains("IDENT"))
        }
    }
}
