/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.http

import io.github.jonathangtd.bimmerdata.api.tls.TlsIdentityStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The embedded server must keep accepting whatever the clients do: silent TLS clients, half
 * requests, never-ending event streams, a failing or stuck accept step.
 */
@Timeout(60)
class HttpServerResilienceTest {

    @TempDir lateinit var dir: File

    private val servers = mutableListOf<HttpServer>()
    private val sockets = mutableListOf<Socket>()
    private val streamsOpen = AtomicInteger()
    private val stopStreams = CountDownLatch(1)

    @AfterEach
    fun tearDown() {
        stopStreams.countDown()
        sockets.forEach { runCatching { it.close() } }
        servers.forEach { it.stop() }
    }

    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun handler(req: HttpRequest): HttpResponse = when (req.path) {
        "/stream" -> HttpResponse.Stream(200, "text/event-stream") { out ->
            streamsOpen.incrementAndGet()
            try {
                out.write("data: hello\n\n".toByteArray()); out.flush()
                stopStreams.await(50, TimeUnit.SECONDS)
            } finally {
                streamsOpen.decrementAndGet()
            }
        }
        else -> HttpResponse.text(200, "pong")
    }

    private fun start(
        tls: Boolean,
        limits: HttpLimits = HttpLimits(handshakeTimeoutMs = 1_000, readTimeoutMs = 1_000),
        acceptPeer: (InetAddress) -> Boolean = { true },
    ): HttpServer {
        val s = HttpServer(::handler, limits, acceptPeer)
        val ctx = if (tls) TlsIdentityStore(File(dir, "tls.bin"), { it }, { it }).ensure(listOf(loopback as java.net.Inet4Address)).sslContext() else null
        s.start(listOf(loopback), 0, ctx)
        servers += s
        return s
    }

    private val trustAll: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), null)
    }

    /** GET [path]; returns (status, body), with a short timeout so a stuck server fails the test. */
    private fun get(s: HttpServer, path: String = "/ping", timeoutMs: Int = 4_000): Pair<Int, String> {
        val scheme = if (s.secure) "https" else "http"
        val c = URL("$scheme://127.0.0.1:${s.port}$path").openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = trustAll.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        return try {
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun rawClient(s: HttpServer, send: ByteArray? = null): Socket =
        Socket("127.0.0.1", s.port).also { sock ->
            sockets += sock
            if (send != null) { sock.getOutputStream().write(send); sock.getOutputStream().flush() }
        }

    @Test
    fun `silent and half-sent TLS clients do not stop the next client`() {
        val s = start(tls = true)
        repeat(3) { rawClient(s) } // TCP connected, never says a word
        repeat(3) { rawClient(s, byteArrayOf(0x16, 0x03, 0x01, 0x00)) } // first bytes of a ClientHello, then nothing
        val t0 = System.nanoTime()
        assertEquals(200 to "pong", get(s))
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L, "served within 3 s")
    }

    @Test
    fun `half requests on plain HTTP do not stop the next client`() {
        val s = start(tls = false)
        repeat(4) { rawClient(s, "GET /ping HTTP/1.1\r\nHost: x".toByteArray()) }
        repeat(4) { rawClient(s) }
        assertEquals(200 to "pong", get(s))
    }

    @Test
    fun `never-ending streams keep their own slots, normal requests still go through`() {
        val s = start(tls = true, limits = HttpLimits(maxConnections = 4, maxStreams = 3, handshakeTimeoutMs = 1_000, readTimeoutMs = 1_000))
        val opened = (1..3).map { Thread { runCatching { get(s, "/stream", timeoutMs = 30_000) } }.apply { isDaemon = true; start() } }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (streamsOpen.get() < 3 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(3, streamsOpen.get())
        // more streams than allowed: refused, not queued forever
        assertEquals(503, get(s, "/stream").first)
        // the 3 streams gave their connection slots back: 4 normal requests in a row still work
        repeat(4) { assertEquals(200 to "pong", get(s)) }
        assertTrue(opened.all { it.isAlive })
    }

    @Test
    fun `an exception in the accept step does not kill the accept loop`() {
        val first = AtomicBoolean(true)
        val s = start(tls = true, acceptPeer = { if (first.getAndSet(false)) throw IllegalStateException("boom") else true })
        runCatching { get(s, timeoutMs = 2_000) } // this one is dropped
        assertEquals(200 to "pong", get(s))
    }

    @Test
    fun `the watchdog binds again a listening socket whose accept loop is stuck`() {
        val stuck = CountDownLatch(1)
        val firstCall = AtomicBoolean(true)
        val s = start(
            tls = true,
            limits = HttpLimits(handshakeTimeoutMs = 1_000, readTimeoutMs = 1_000, watchdogPeriodMs = 300),
            acceptPeer = { if (firstCall.getAndSet(false)) { stuck.await(50, TimeUnit.SECONDS); true } else true },
        )
        rawClient(s) // the accept thread now hangs in acceptPeer: nothing else is accepted
        val deadline = System.nanoTime() + 15_000_000_000L
        while (s.rebinds.get() == 0 && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue(s.rebinds.get() >= 1, "watchdog rebound the socket")
        assertEquals(200 to "pong", get(s))
        stuck.countDown()
    }
}
