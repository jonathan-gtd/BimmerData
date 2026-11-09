/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import com.sun.net.httpserver.HttpServer
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.zip.GZIPInputStream

/**
 * One sender: the background job only sends the backlog, live points are grouped (visible as
 * Gathering), "Send now" skips the grouping, and requests reuse one HTTP connection.
 */
class SingleSenderTest {
    private class Server : AutoCloseable {
        val lines: MutableList<String> = Collections.synchronizedList(ArrayList())
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val clientPorts: MutableSet<Int> = Collections.synchronizedSet(HashSet())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v2/write") { ex ->
                val raw = ex.requestBody.readBytes()
                val body = if (ex.requestHeaders.getFirst("Content-Encoding") == "gzip") GZIPInputStream(raw.inputStream()).readBytes() else raw
                lines += body.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
                clientPorts += ex.remoteAddress.port
                requests.incrementAndGet()
                ex.sendResponseHeaders(204, -1)
                ex.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/api/v2/write"
        override fun close() = server.stop(0)
    }

    private fun run(dir: File, live: Boolean, maxWaitMs: Long, block: suspend (TelegrafExporter, TestReadings, LineQueue) -> Unit) = Server().use { srv ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = MutableStateFlow(ExportConfig(enabled = true, url = srv.url, car = "E90", mode = ExportMode.Live))
        val net = MutableStateFlow(NetworkKind.Unmetered)
        val readings = TestReadings()
        val queue = LineQueue(dir, freeSpace = { Long.MAX_VALUE })
        val exporter = TelegrafExporter(
            scope, readings.flow, { null }, queue, config, net,
            batchPauseMs = 100, minBackoffMs = 20, maxBackoffMs = 100, clock = { 1_000 }, maxBatchWaitMs = maxWaitMs,
        )
        if (live) exporter.start()
        try {
            runBlocking { withTimeout(15_000) { block(exporter, readings, queue) } }
        } finally {
            exporter.stop()
            scope.cancel()
        }
        srv
    }

    @Test
    fun `the background job sends the backlog only, never the live points`(@TempDir dir: File) {
        val srv = Server()
        srv.use {
            val q = LineQueue(dir, freeSpace = { Long.MAX_VALUE })
            q.append(List(100) { "m v=$it ${1000 + it}" })
            q.seal() // backlog: a closed segment
            q.append(List(5) { "live v=$it ${5000 + it}" }) // live points, still open
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val exporter = TelegrafExporter(
                scope, TestReadings().flow, { null }, q,
                MutableStateFlow(ExportConfig(enabled = true, url = srv.url, car = "E90")), MutableStateFlow(NetworkKind.Unmetered),
            )
            val done = runBlocking { withTimeout(10_000) { exporter.drain() } }
            scope.cancel()
            assertTrue(done)
            assertEquals(100, srv.lines.size, "only the backlog")
            assertTrue(srv.lines.none { it.startsWith("live") })
            assertEquals(5L, q.lines(), "the live points stay for the sender")
        }
    }

    @Test
    fun `live points are shown as gathering, and send now skips the wait`(@TempDir dir: File) {
        lateinit var srvRef: Server
        val srv = run(dir, live = true, maxWaitMs = 60_000) { exporter, readings, _ ->
            delay(100)
            for (i in 0 until 5) readings.value = mapOf("dme/0x4300" to Sample(90.0 + i, null, 2_000L + i * 10))
            while (exporter.state.value.phase != ExportPhase.Gathering) delay(10)
            delay(300)
            assertEquals(ExportPhase.Gathering, exporter.state.value.phase, "5 points: still grouping")
            assertEquals(5L, exporter.state.value.queuedLines)
            exporter.kick() // "Send now"
            while (exporter.state.value.sentLines < 5) delay(10)
        }
        srvRef = srv
        assertEquals(1, srvRef.requests.get(), "one request for the 5 points")
    }

    @Test
    fun `requests reuse one connection`(@TempDir dir: File) {
        val srv = run(dir, live = true, maxWaitMs = 200) { exporter, readings, _ ->
            delay(100)
            for (round in 0 until 4) {
                readings.value = mapOf("dme/0x4300" to Sample(90.0 + round, null, 2_000L + round * 1000))
                while (exporter.state.value.sentLines < round + 1) delay(10)
            }
        }
        assertEquals(4, srv.requests.get())
        assertEquals(1, srv.clientPorts.size, "keep-alive: the same client connection, ports ${srv.clientPorts}")
    }
}
