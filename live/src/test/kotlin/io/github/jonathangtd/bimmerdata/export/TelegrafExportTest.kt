/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import com.sun.net.httpserver.HttpServer
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.zip.GZIPInputStream

class TelegrafExportTest {

    private val oil = Measurement("msv70/0x4402", "msv70", "Oil temperature", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OELTEMPERATUR_WERT"))

    // ---------------------------------------------------------------- line protocol

    @Test
    fun `encodes a numeric sample with tags and nanosecond time`() {
        val line = LineEncoder("E90 330i").encode(oil, Sample(87.5, null, 1_731_000_000_123))
        assertEquals("bimmerdata,car=E90\\ 330i,ecu=msv70,id=0x4402,label=Oil\\ temperature,unit=°C value=87.5 1731000000123000000", line)
    }

    @Test
    fun `escapes special characters and skips blank tags`() {
        val m = Measurement("kombi/STATUS_X/STAT_A", "kombi", "a,b=c", "", MeasurementSource.JobResult("STATUS_X", "", "STAT_A"))
        val line = LineEncoder("car").encode(m, Sample(null, "say \"hi\"\nnow \\", 1))
        assertEquals("bimmerdata,car=car,ecu=kombi,id=STATUS_X/STAT_A,label=a\\,b\\=c text=\"say \\\"hi\\\" now \\\\\" 1000000", line)
    }

    @Test
    fun `floats are plain decimals and NaN is dropped`() {
        assertEquals("0.00001", LineProtocol.float(1e-5))
        assertEquals("12300000000", LineProtocol.float(1.23e10))
        assertEquals("-48", LineProtocol.float(-48.0))
        assertEquals("0", LineProtocol.float(-0.0))
        assertNull(LineProtocol.float(Double.NaN))
        assertNull(LineEncoder("c").encode(oil, Sample(Double.POSITIVE_INFINITY, null, 1)))
    }

    @Test
    fun `sampler limits points per second and skips old values`() {
        val s = ExportSampler(pointsPerSecond = 2, notBeforeMs = 1000)
        assertTrue(s.select(mapOf("a" to Sample(1.0, null, 500))).isEmpty()) // older than the start
        assertEquals(1, s.select(mapOf("a" to Sample(1.0, null, 1000))).size)
        assertTrue(s.select(mapOf("a" to Sample(1.0, null, 1000))).isEmpty()) // same sample again
        assertTrue(s.select(mapOf("a" to Sample(1.0, null, 1400))).isEmpty()) // < 500 ms later
        assertEquals(1, s.select(mapOf("a" to Sample(1.0, null, 1500))).size)
    }

    // ---------------------------------------------------------------- queue

    @Test
    fun `queue survives a crash and trims a cut line`(@TempDir dir: File) {
        val q = LineQueue(dir, segmentBytes = 50, freeSpace = { Long.MAX_VALUE })
        q.append(listOf("m v=1 1", "m v=2 2"))
        q.append(List(10) { "m v=$it 1$it" })
        q.append(listOf("m v=3 3"))
        // crash: no close(), the open raw segment is cut in the middle of a line
        val raw = dir.listFiles()!!.single { it.name.endsWith(".lp") }
        raw.appendText("m v=9")
        val q2 = LineQueue(dir, segmentBytes = 50, freeSpace = { Long.MAX_VALUE })
        assertEquals(13, q2.state().lines)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".lp") }, "recovered raw segment is compressed")
        val bodies = ArrayList<String>()
        while (true) {
            val b = q2.next(includeOpen = true) ?: break
            assertTrue(b.gzip)
            bodies += GZIPInputStream(b.body.inputStream()).readBytes().toString(Charsets.UTF_8)
            q2.remove(b)
        }
        assertEquals(13, bodies.joinToString("").lines().count { it.isNotEmpty() })
        assertTrue(q2.isEmpty())
    }

    // ---------------------------------------------------------------- exporter

    private class FakeTelegraf(var status: Int = 204) : AutoCloseable {
        val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
        val auth: MutableList<String?> = Collections.synchronizedList(ArrayList())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v2/write") { ex ->
                val raw = ex.requestBody.readBytes()
                val body = if (ex.requestHeaders.getFirst("Content-Encoding") == "gzip") GZIPInputStream(raw.inputStream()).readBytes() else raw
                bodies += body.toString(Charsets.UTF_8)
                auth += ex.requestHeaders.getFirst("Authorization")
                ex.sendResponseHeaders(status, -1)
                ex.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/api/v2/write"
        val lines get() = synchronized(bodies) { bodies.flatMap { it.lines() }.filter { it.isNotEmpty() } }
        override fun close() = server.stop(0)
    }

    private fun exporterTest(dir: File, block: suspend (TelegrafExporter, MutableStateFlow<Map<String, Sample>>, MutableStateFlow<ExportConfig>, MutableStateFlow<NetworkKind>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val samples = MutableStateFlow<Map<String, Sample>>(emptyMap())
        val config = MutableStateFlow(ExportConfig())
        val net = MutableStateFlow(NetworkKind.None)
        val exporter = TelegrafExporter(
            scope, samples, { id -> if (id == oil.id) oil else null }, LineQueue(dir), config, net,
            batchPauseMs = 50, minBackoffMs = 50, maxBackoffMs = 200, clock = { 1_000 },
        )
        exporter.start()
        try {
            runBlocking { withTimeout(10_000) { block(exporter, samples, config, net) } }
        } finally {
            exporter.stop()
            scope.cancel()
        }
    }

    private suspend fun until(what: String, cond: () -> Boolean) {
        while (!cond()) delay(10)
        assertTrue(cond(), what)
    }

    @Test
    fun `after the trip mode waits for an unmetered network and keeps original times`(@TempDir dir: File) = FakeTelegraf().use { tg ->
        exporterTest(dir) { exporter, samples, config, net ->
            config.value = ExportConfig(enabled = true, url = tg.url, auth = TelegrafAuth.Token("secret"), car = "E90", pointsPerSecond = 5)
            delay(50)
            for (t in 0 until 10) {
                samples.value = mapOf(oil.id to Sample(80.0 + t, null, 2_000L + t * 200))
                delay(20)
            }
            until("10 points queued") { exporter.state.value.queuedLines == 10L }
            net.value = NetworkKind.Metered
            delay(150)
            assertEquals(ExportPhase.WaitingForUnmetered, exporter.state.value.phase)
            assertTrue(tg.bodies.isEmpty(), "nothing is sent on mobile data")
            net.value = NetworkKind.Unmetered
            until("all sent") { exporter.state.value.sentLines == 10L }
            assertEquals(10, tg.lines.size)
            assertTrue(tg.lines.first().endsWith(" 2000000000"), tg.lines.first())
            assertEquals("Token secret", tg.auth.first())
            assertEquals(0L, exporter.state.value.queuedLines)
        }
    }

    @Test
    fun `server errors are retried without losing points`(@TempDir dir: File) = FakeTelegraf(status = 503).use { tg ->
        exporterTest(dir) { exporter, samples, config, net ->
            net.value = NetworkKind.Metered
            config.value = ExportConfig(enabled = true, url = tg.url, car = "E90", mode = ExportMode.Live)
            delay(50)
            samples.value = mapOf(oil.id to Sample(90.0, null, 5_000))
            until("tried twice") { tg.bodies.size >= 2 }
            assertEquals(ExportPhase.Retrying, exporter.state.value.phase)
            assertEquals(1L, exporter.state.value.queuedLines)
            tg.status = 204
            until("sent") { exporter.state.value.sentLines == 1L }
            assertNull(exporter.state.value.lastError)
        }
    }

    @Test
    fun `wrong token stops until the settings change`(@TempDir dir: File) = FakeTelegraf(status = 401).use { tg ->
        exporterTest(dir) { exporter, samples, config, net ->
            net.value = NetworkKind.Unmetered
            config.value = ExportConfig(enabled = true, url = tg.url, auth = TelegrafAuth.Token("bad"), car = "E90")
            delay(50)
            samples.value = mapOf(oil.id to Sample(90.0, null, 5_000))
            until("auth failed") { exporter.state.value.phase == ExportPhase.AuthFailed }
            val tries = tg.bodies.size
            delay(300)
            assertEquals(tries, tg.bodies.size, "no retry loop with a wrong token")
            tg.status = 204
            config.value = config.value.copy(auth = TelegrafAuth.Token("good"))
            until("sent after the fix") { exporter.state.value.sentLines == 1L }
            assertEquals("Token good", tg.auth.last())
        }
    }

    @Test
    fun `test sends an empty write and reports the status`() = FakeTelegraf(status = 401).use { tg ->
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            val e = TelegrafExporter(scope, MutableStateFlow(emptyMap()), { null }, LineQueue(File(System.getProperty("java.io.tmpdir"), "bd-test-${System.nanoTime()}")),
                MutableStateFlow(ExportConfig()), MutableStateFlow(NetworkKind.None))
            assertTrue(e.test(ExportConfig(enabled = true, url = tg.url)) is WriteResult.AuthFailed)
            tg.status = 204
            assertEquals(WriteResult.Ok, e.test(ExportConfig(enabled = true, url = tg.url)))
            assertTrue(e.test(ExportConfig(enabled = true, url = "ftp://x")) is WriteResult.Rejected)
            scope.cancel()
        }
    }
}
