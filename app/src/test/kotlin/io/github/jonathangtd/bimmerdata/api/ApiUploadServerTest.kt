/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.files.EcuFileStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket

/** Streamed uploads on the real server: `Expect: 100-continue`, early refusals, bodies above the 1 MB limit, keep-alive. */
class ApiUploadServerTest {
    @TempDir lateinit var tmp: File

    private val token = ApiTokens.generate()
    private lateinit var backend: FakeApiBackend
    private lateinit var server: ApiServer
    private lateinit var store: EcuFileStore

    @BeforeEach
    fun start() {
        backend = FakeApiBackend()
        store = EcuFileStore(File(tmp, "files").apply { mkdirs() }, File(tmp, "import"), freeSpace = { 10_000_000_000 }, reserveBytes = 0)
        backend.ecuStore = store
        server = ApiServer(backend, { ApiAccess(token, setOf(Scope.Read, Scope.Config)) })
        server.start(listOf(InetAddress.getLoopbackAddress()), 0)
    }

    @AfterEach
    fun stop() {
        server.stop()
        backend.close()
    }

    /** Reads one response head (status line + headers) without reading the body. */
    private fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            check(b >= 0) { "connection closed: ${buf.toString(Charsets.ISO_8859_1)}" }
            buf.write(b)
            val s = buf.toString(Charsets.ISO_8859_1)
            if (s.endsWith("\r\n\r\n")) {
                val lines = s.trim().split("\r\n")
                return lines[0].split(' ')[1].toInt() to lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            }
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        val n = headers["content-length"]!!.toInt()
        val b = ByteArray(n)
        var r = 0
        while (r < n) r += input.read(b, r, n - r).also { check(it > 0) }
        return String(b, Charsets.UTF_8)
    }

    private fun head(method: String, path: String, length: Int, auth: String = token, expect: Boolean = true, close: Boolean = false) = buildString {
        append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $auth\r\nContent-Type: application/octet-stream\r\nContent-Length: $length\r\n")
        if (expect) append("Expect: 100-continue\r\n")
        if (close) append("Connection: close\r\n")
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `a 3 MB file is streamed after 100 Continue and the connection stays usable`() {
        val data = ByteArray(3_000_000) { (it % 253).toByte() }
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 20_000
            val out = s.getOutputStream()
            val input = s.getInputStream()
            out.write(head("PUT", "/api/v1/ecu-files/BIG.PRG", data.size))
            out.flush()
            val (cont, _) = readHead(input)
            assertEquals(100, cont, "the server asks for the body only once the request is accepted")
            out.write(data)
            out.flush()
            val (status, headers) = readHead(input)
            val body = readBody(input, headers)
            assertEquals(201, status, body)
            assertEquals("keep-alive", headers["connection"])
            // same connection, next request
            out.write("GET /api/v1/ecu-files HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val (st2, h2) = readHead(input)
            assertEquals(200, st2)
            assertTrue(readBody(input, h2).contains("\"fileCount\":1"))
        }
        assertArrayEquals(data, File(store.ecuDir, "big.prg").readBytes())
    }

    @Test
    fun `a wrong token is refused before the body is sent`() {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write(head("PUT", "/api/v1/ecu-files/a.prg", 5_000_000, auth = "bd_wrong"))
            s.getOutputStream().flush()
            val (status, headers) = readHead(s.getInputStream())
            assertEquals(401, status, "final answer instead of 100 Continue")
            assertEquals("close", headers["connection"])
        }
        assertTrue(store.files().isEmpty())
    }

    @Test
    fun `a bad name without Expect is refused and the connection closed`() {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 10_000
            val body = ByteArray(200_000)
            s.getOutputStream().write(head("PUT", "/api/v1/ecu-files/evil.sh", body.size, expect = false))
            s.getOutputStream().write(body)
            s.getOutputStream().flush()
            val (status, headers) = readHead(s.getInputStream())
            assertEquals(400, status)
            assertTrue(readBody(s.getInputStream(), headers).contains("invalid_name"))
        }
        assertTrue(store.files().isEmpty())
    }

    @Test
    fun `other routes keep the 1 MB limit and upload routes their own`() {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write(head("PUT", "/api/v1/dashboard", 2_000_000))
            s.getOutputStream().flush()
            assertEquals(413, readHead(s.getInputStream()).first)
        }
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write(head("PUT", "/api/v1/ecu-files/a.prg", (EcuFileStore.MAX_FILE_BYTES + 1).toInt()))
            s.getOutputStream().flush()
            assertEquals(413, readHead(s.getInputStream()).first)
        }
        assertFalse(File(store.ecuDir, "a.prg").exists())
    }

    @Test
    fun `a client that stops in the middle leaves no file`() {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            out.write(head("PUT", "/api/v1/ecu-files/half.prg", 1_000_000, expect = false))
            out.write(ByteArray(300_000))
            out.flush()
            s.shutdownOutput()
            val (status, headers) = readHead(s.getInputStream())
            assertEquals(400, status)
            assertTrue(readBody(s.getInputStream(), headers).contains("truncated_body"))
        }
        assertTrue(store.files().isEmpty())
    }
}
