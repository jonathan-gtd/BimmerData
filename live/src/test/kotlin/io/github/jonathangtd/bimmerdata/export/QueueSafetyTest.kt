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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/** The Telegraf queue never loses a point: deleted only after a 2xx, never automatically. */
class QueueSafetyTest {

    private val oil = Measurement("msv70/0x4402", "msv70", "Oil temperature", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OELTEMPERATUR_WERT"))

    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).readBytes().toString(Charsets.UTF_8)

    // ---------------------------------------------------------------- queue

    @Test
    fun `closed segments are gzip and the round trip gives the lines back`(@TempDir dir: File) {
        val q = LineQueue(dir, segmentBytes = 4096, freeSpace = { Long.MAX_VALUE })
        val lines = List(2000) { i -> "bimmerdata,car=E90,ecu=msv70,id=0x4402,label=Oil\\ temperature,unit=°C value=${80 + i % 20}.5 ${1_762_590_652_000_000_000L + i * 200_000_000L}" }
        lines.chunked(50).forEach { assertTrue(q.append(it)) }
        q.seal()
        val st = q.state()
        assertEquals(2000L, st.lines)
        assertTrue(st.bytes * 4 < st.rawBytes, "compressed ${st.bytes} vs raw ${st.rawBytes}")
        val back = ArrayList<String>()
        while (true) {
            val b = q.next(includeOpen = true) ?: break
            assertTrue(b.gzip)
            back += gunzip(b.body).lines().filter { it.isNotEmpty() }
            q.remove(b)
        }
        assertEquals(lines, back)
    }

    @Test
    fun `reaching the reserve stops queuing and deletes nothing`(@TempDir dir: File) {
        val free = AtomicLong(10_000)
        val q = LineQueue(dir, segmentBytes = 100, freeSpace = { free.get() }, reserveBytes = 5_000)
        repeat(20) { i -> assertTrue(q.append(listOf("m v=$i ${1000 + i}"))) }
        val before = q.state()
        assertEquals(20L, before.lines)
        assertFalse(before.blocked)

        free.set(5_010) // the next lines would eat into the reserve
        assertFalse(q.append(listOf("m v=100 2000", "m v=101 2001")))
        val blocked = q.state()
        assertTrue(blocked.blocked)
        assertEquals(2L, blocked.refusedLines)
        assertEquals(20L, blocked.lines, "nothing already queued is deleted")
        assertEquals(before.rawBytes, blocked.rawBytes)

        free.set(1_000_000) // space freed: queuing goes on
        assertTrue(q.append(listOf("m v=102 2002")))
        assertFalse(q.state().blocked)
        assertEquals(21L, q.state().lines)
        // the oldest point is still the first one sent
        assertTrue(gunzip(q.next(includeOpen = true)!!.body).startsWith("m v=0 1000"))
    }

    @Test
    fun `rejected batches are kept aside and can be retried`(@TempDir dir: File) {
        val q = LineQueue(dir, segmentBytes = 10_000, freeSpace = { Long.MAX_VALUE })
        q.append(listOf("bad line", "m v=1 1"))
        val b = q.next(includeOpen = true)!!
        q.reject(b, "HTTP 400: unable to parse")
        assertTrue(q.isEmpty())
        val st = q.state()
        assertEquals(1, st.rejectedBatches)
        assertEquals(2L, st.rejectedLines)
        val kept = q.rejectedFiles().single()
        assertEquals("bad line\nm v=1 1\n", gunzip(kept.readBytes()))
        assertTrue(File(kept.parentFile, kept.name + ".txt").readText().contains("400"))

        // survives a restart
        q.close()
        val q2 = LineQueue(dir, segmentBytes = 10_000, freeSpace = { Long.MAX_VALUE })
        assertEquals(1, q2.state().rejectedBatches)
        assertTrue(q2.isEmpty(), "rejected batches are not in the upload loop")
        q2.append(listOf("m v=2 2"))
        assertEquals(1, q2.retryRejected())
        assertEquals(0, q2.state().rejectedBatches)
        assertEquals(3L, q2.state().lines)
        // the rejected batch is older: it goes first
        assertTrue(gunzip(q2.next(includeOpen = true)!!.body).startsWith("bad line"))
    }

    // ---------------------------------------------------------------- exporter

    private class FakeTelegraf(@Volatile var status: Int = 204) : AutoCloseable {
        val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
        val encodings: MutableList<String?> = Collections.synchronizedList(ArrayList())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v2/write") { ex ->
                val raw = ex.requestBody.readBytes()
                val enc = ex.requestHeaders.getFirst("Content-Encoding")
                encodings += enc
                bodies += if (enc == "gzip") GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8) else raw.toString(Charsets.UTF_8)
                ex.sendResponseHeaders(status, -1)
                ex.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/api/v2/write"
        override fun close() = server.stop(0)
    }

    private fun exporterTest(dir: File, block: suspend (TelegrafExporter, TestReadings, MutableStateFlow<ExportConfig>, MutableStateFlow<NetworkKind>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val samples = TestReadings()
        val config = MutableStateFlow(ExportConfig())
        val net = MutableStateFlow(NetworkKind.None)
        val exporter = TelegrafExporter(
            scope, samples.flow, { id -> if (id == oil.id) oil else null }, LineQueue(dir, freeSpace = { Long.MAX_VALUE }), config, net,
            batchPauseMs = 50, minBackoffMs = 50, maxBackoffMs = 200, clock = { 1_000 }, maxBatchWaitMs = 100,
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
    fun `404 is retried and nothing is deleted`(@TempDir dir: File) = FakeTelegraf(status = 404).use { tg ->
        exporterTest(dir) { exporter, samples, config, net ->
            net.value = NetworkKind.Unmetered
            config.value = ExportConfig(enabled = true, url = tg.url, car = "E90")
            delay(50)
            samples.value = mapOf(oil.id to Sample(90.0, null, 5_000))
            until("tried three times") { tg.bodies.size >= 3 }
            until("retrying") { exporter.state.value.phase == ExportPhase.Retrying }
            assertEquals(1L, exporter.state.value.queuedLines, "a 404 never deletes the batch")
            assertEquals(0, exporter.state.value.rejectedBatches)
            assertTrue(tg.encodings.all { it == "gzip" }, "batches are sent gzip-compressed")
            tg.status = 204
            until("sent once the server answers 2xx") { exporter.state.value.sentLines == 1L }
            assertEquals(0L, exporter.state.value.queuedLines)
            assertNull(exporter.state.value.lastError)
        }
    }

    @Test
    fun `400 moves the batch to rejected and keeps it`(@TempDir dir: File) = FakeTelegraf(status = 400).use { tg ->
        exporterTest(dir) { exporter, samples, config, net ->
            net.value = NetworkKind.Unmetered
            config.value = ExportConfig(enabled = true, url = tg.url, car = "E90")
            delay(50)
            samples.value = mapOf(oil.id to Sample(90.0, null, 5_000))
            until("rejected") { exporter.state.value.rejectedBatches == 1 }
            assertEquals(0L, exporter.state.value.queuedLines)
            assertEquals(1L, exporter.state.value.rejectedLines)
            assertEquals(0L, exporter.state.value.sentLines)
            assertTrue(exporter.rejectedFiles().single().exists())
            // the server is fixed: "Retry" sends it
            tg.status = 204
            assertEquals(1, exporter.retryRejected())
            until("sent after retry") { exporter.state.value.sentLines == 1L }
            assertEquals(0, exporter.state.value.rejectedBatches)
            assertTrue(tg.bodies.last().contains("ecu=msv70"))
        }
    }

    @Test
    fun `5xx and network errors keep every point`(@TempDir dir: File) {
        // nothing listens on this port: connection refused
        exporterTest(dir) { exporter, samples, config, net ->
            net.value = NetworkKind.Metered
            config.value = ExportConfig(enabled = true, url = "http://127.0.0.1:9/api/v2/write", car = "E90", mode = ExportMode.Live)
            delay(50)
            for (t in 0 until 5) {
                samples.value = mapOf(oil.id to Sample(80.0 + t, null, 2_000L + t * 200))
                delay(20)
            }
            until("5 queued") { exporter.state.value.queuedLines == 5L }
            until("retrying") { exporter.state.value.phase == ExportPhase.Retrying }
            delay(300)
            assertEquals(5L, exporter.state.value.queuedLines)
            assertEquals(0, exporter.state.value.rejectedBatches)
        }
    }

    // ---------------------------------------------------------------- estimate

    @Test
    fun `autonomy before the reserve`() {
        // 10 measurements × 2 points/s × 110 bytes = 2200 B/s raw, gzip 10 % → 220 B/s on disk
        val raw = QueueEstimate.rawBytesPerSecond(0.0, 2.0, 10)
        assertEquals(2200.0, raw, 0.001)
        val secs = QueueEstimate.autonomySeconds(roomBytes = 792_000, rawBytesPerSecond = raw, gzipRatio = 0.1)
        assertEquals(3600L, secs)
        assertNull(QueueEstimate.autonomySeconds(1_000, 0.0, 0.1))
        assertEquals(0L, QueueEstimate.autonomySeconds(-5, 100.0, 0.1))
        assertEquals(QueueEstimate.TYPICAL_GZIP_RATIO, QueueEstimate.gzipRatio(10, 100), 0.0)
        assertEquals(0.2, QueueEstimate.gzipRatio(20_000, 100_000), 0.0001)
        assertEquals(-1, QueueEstimate.growthLevel(50 * LineQueue.MB))
        assertEquals(1, QueueEstimate.growthLevel(600 * LineQueue.MB))
        assertEquals(500_000_000L, 500 * LineQueue.MB)
    }

    @Test
    fun `measured rate over a sliding minute`() {
        val r = QueueEstimate.Rate(windowMs = 60_000)
        r.add(0, 6_000)
        r.add(30_000, 6_000)
        assertEquals(12_000 * 1000.0 / 60_000, r.bytesPerSecond(60_000), 0.001)
        assertEquals(6_000 * 1000.0 / 50_000, r.bytesPerSecond(80_000), 0.001)
        assertEquals(0.0, r.bytesPerSecond(200_000), 0.0)
    }
}
