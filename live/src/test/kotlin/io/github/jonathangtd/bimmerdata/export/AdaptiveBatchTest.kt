/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import com.sun.net.httpserver.HttpServer
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.zip.GZIPInputStream

/**
 * A slow server gets smaller batches: partial segments leave the phone only after a 2xx, a crash
 * between the 2xx and the rewrite neither loses nor repeats lines, and the exporter halves its
 * batches on timeout / 502 until they go through.
 */
class AdaptiveBatchTest {
    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).readBytes().toString(Charsets.UTF_8)
    private fun lines(n: Int, from: Int = 0) = List(n) { i -> "bimmerdata,ecu=dme value=${from + i} ${1_000_000L + from + i}" }

    private fun filled(dir: File, n: Int): LineQueue {
        val q = LineQueue(dir, segmentBytes = 1_000_000, freeSpace = { Long.MAX_VALUE })
        assertTrue(q.append(lines(n)))
        q.seal()
        return q
    }

    @Test
    fun `a partial batch sends the first lines and keeps the rest after the 2xx`(@TempDir dir: File) {
        val q = filled(dir, 100)
        val b = q.next(includeOpen = true, maxLines = 30)!!
        assertTrue(b.partial)
        assertEquals(30, b.lines)
        assertEquals(lines(30), gunzip(b.body).lines().filter { it.isNotEmpty() })
        assertEquals(100L, q.state().lines, "nothing leaves before the 2xx")
        q.remove(b)
        assertEquals(70L, q.state().lines)
        val rest = q.next(includeOpen = true)!!
        assertFalse(rest.partial)
        assertEquals(lines(70, from = 30), gunzip(rest.body).lines().filter { it.isNotEmpty() })
        q.remove(rest)
        assertTrue(q.isEmpty())
        assertEquals(0, dir.listFiles { f -> f.isFile && f.name.startsWith("seg-") }!!.size)
    }

    @Test
    fun `a batch that fits is sent whole`(@TempDir dir: File) {
        val q = filled(dir, 40)
        val b = q.next(includeOpen = true, maxLines = 40)!!
        assertFalse(b.partial)
        assertEquals(40, b.lines)
    }

    @Test
    fun `crash between the 2xx and the cleanup - nothing lost, nothing sent twice`(@TempDir dir: File) {
        val q = filled(dir, 100)
        val original = dir.listFiles { f -> f.name.endsWith(".lp.gz") }!!.single()
        val saved = original.readBytes()
        val b = q.next(includeOpen = true, maxLines = 25)!!
        q.remove(b)
        // as if the process died after writing the rest but before deleting the old file
        original.writeBytes(saved)
        assertEquals(2, dir.listFiles { f -> f.name.endsWith(".lp.gz") }!!.size)
        val q2 = LineQueue(dir, segmentBytes = 1_000_000, freeSpace = { Long.MAX_VALUE })
        assertEquals(75L, q2.state().lines)
        assertEquals(lines(75, from = 25), gunzip(q2.next(includeOpen = true)!!.body).lines().filter { it.isNotEmpty() })
        assertEquals(1, dir.listFiles { f -> f.name.endsWith(".lp.gz") }!!.size, "the old file is cleaned up")
    }

    @Test
    fun `timeouts and gateway errors ask for a smaller batch, a bad batch does not`() {
        assertTrue(502 in TelegrafClient.SMALLER_CODES && 504 in TelegrafClient.SMALLER_CODES && 413 in TelegrafClient.SMALLER_CODES)
        assertFalse(400 in TelegrafClient.SMALLER_CODES)
        assertFalse(404 in TelegrafClient.SMALLER_CODES)
    }

    @Test
    fun `batch sizer - halves on failure, grows slowly, never back to a failed size, probes again later`() {
        val s = BatchSizer(min = 50, growAfter = 3, probeAfter = 20)
        assertEquals(Int.MAX_VALUE, s.limit)
        s.ok()
        assertEquals(Int.MAX_VALUE, s.limit, "no limit while nothing failed")
        assertTrue(s.tooBig(1000))
        assertEquals(500, s.limit)
        assertTrue(s.tooBig(500))
        assertEquals(250, s.limit)
        repeat(2) { s.ok() }
        assertEquals(250, s.limit, "grows only after a streak")
        s.ok()
        assertEquals(375, s.limit, "halfway to the size that failed, not back to it")
        repeat(3) { s.ok() }
        assertEquals(437, s.limit)
        // a failure under the floor does not go lower
        val low = BatchSizer(min = 50)
        assertTrue(low.tooBig(80))
        assertEquals(50, low.limit)
        assertFalse(low.tooBig(50), "at the floor: plain retry with backoff")
        // after enough successes the failed size is forgotten
        repeat(20) { s.ok() }
        val before = s.limit
        repeat(3) { s.ok() }
        assertTrue(s.limit >= before, "keeps growing once probing again: $before -> ${s.limit}")
    }

    /** Answers 502 to batches of more than [maxLines] lines (like a listener timing out), 204 otherwise. */
    private class SlowTelegraf(private val maxLines: Int) : AutoCloseable {
        val accepted: MutableList<String> = Collections.synchronizedList(ArrayList())
        val refused = java.util.concurrent.atomic.AtomicInteger()
        val batches = java.util.concurrent.atomic.AtomicInteger()
        val refusedSizes: MutableList<Int> = Collections.synchronizedList(ArrayList())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v2/write") { ex ->
                val raw = ex.requestBody.readBytes()
                val body = if (ex.requestHeaders.getFirst("Content-Encoding") == "gzip") GZIPInputStream(raw.inputStream()).readBytes() else raw
                val ls = body.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
                if (ls.size > maxLines) {
                    refused.incrementAndGet()
                    refusedSizes += ls.size
                    ex.sendResponseHeaders(502, -1)
                } else {
                    batches.incrementAndGet()
                    accepted += ls
                    ex.sendResponseHeaders(204, -1)
                }
                ex.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/api/v2/write"
        override fun close() = server.stop(0)
    }

    @Test
    fun `live points are grouped - one request per pause, not one per reading`(@TempDir dir: File) = SlowTelegraf(maxLines = 1_000_000).use { tg ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = MutableStateFlow(ExportConfig())
        val net = MutableStateFlow(NetworkKind.Unmetered)
        val readings = TestReadings()
        val exporter = TelegrafExporter(
            scope, readings.flow, { null }, LineQueue(dir, freeSpace = { Long.MAX_VALUE }), config, net,
            batchPauseMs = 300, minBackoffMs = 20, maxBackoffMs = 100, clock = { 1_000 },
        )
        exporter.start()
        try {
            runBlocking {
                withTimeout(20_000) {
                    config.value = ExportConfig(enabled = true, url = tg.url, car = "E90", mode = ExportMode.Live)
                    delay(100)
                    // 100 readings over about 1 s, like a car read at 100 values/s
                    for (i in 0 until 100) {
                        readings.value = mapOf("dme/0x4300" to io.github.jonathangtd.bimmerdata.live.Sample(90.0 + i, null, 2_000L + i * 10))
                        delay(10)
                    }
                    while (tg.accepted.size < 100) delay(10)
                }
            }
            assertEquals(100, tg.accepted.size, "every point once")
            assertTrue(tg.batches.get() <= 8, "grouped into a few requests, not one per reading: ${tg.batches.get()}")
        } finally {
            exporter.stop()
            scope.cancel()
        }
    }

    @Test
    fun `the exporter halves its batches until a slow server accepts them, every line once`(@TempDir dir: File) = SlowTelegraf(maxLines = 120).use { tg ->
        filled(dir, 1000).close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = MutableStateFlow(ExportConfig())
        val net = MutableStateFlow(NetworkKind.Unmetered)
        val exporter = TelegrafExporter(
            scope, TestReadings().flow, { null }, LineQueue(dir, segmentBytes = 1_000_000, freeSpace = { Long.MAX_VALUE }), config, net,
            batchPauseMs = 20, minBackoffMs = 20, maxBackoffMs = 100, clock = { 1_000 }, shrinkPauseMs = 10,
        )
        exporter.start()
        try {
            runBlocking {
                withTimeout(20_000) {
                    config.value = ExportConfig(enabled = true, url = tg.url, car = "E90")
                    while (exporter.state.value.sentLines < 1000L) delay(10)
                }
            }
            assertEquals(lines(1000), tg.accepted.toList(), "every line once, in order")
            assertEquals(0L, exporter.state.value.queuedLines)
            // 1000, 500, 250, 125 refused, then batches under what failed: never back up to a failed size
            val sizes = tg.refusedSizes.toList()
            assertTrue(sizes.size <= 6, "few refused batches: $sizes")
            assertEquals(sizes.sortedDescending().distinct(), sizes, "refused sizes only go down: $sizes")
            assertTrue(exporter.batchLimit in 62..124, "the limit stays under what failed: ${exporter.batchLimit}")
        } finally {
            exporter.stop()
            scope.cancel()
        }
    }
}
