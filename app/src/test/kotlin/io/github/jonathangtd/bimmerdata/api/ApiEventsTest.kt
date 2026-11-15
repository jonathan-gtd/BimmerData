/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.events.EventSnapshot
import io.github.jonathangtd.bimmerdata.events.SnapshotSeries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Socket

/** /api/v1/events on the real server (fake backend). */
class ApiEventsTest {
    private val token = ApiTokens.generate()
    private lateinit var backend: FakeApiBackend
    private lateinit var server: ApiServer
    private var scopes = setOf(Scope.Read, Scope.Config)

    @BeforeEach
    fun start() {
        backend = FakeApiBackend()
        server = ApiServer(backend, { ApiAccess(token, scopes) })
        server.start(listOf(InetAddress.getLoopbackAddress()), 0)
    }

    @AfterEach
    fun stop() {
        server.stop()
        backend.close()
    }

    private class Resp(val status: Int, val text: String) {
        fun json(): JsonObject = Json.parseToJsonElement(text).jsonObject
        val errorCode get() = json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        override fun toString() = "$status $text"
    }

    private fun call(method: String, path: String, body: String? = null): Resp {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 20_000
            val bytes = body?.toByteArray(Charsets.UTF_8)
            val req = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nAuthorization: Bearer $token\r\n")
                if (bytes != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            s.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            bytes?.let { s.getOutputStream().write(it) }
            s.getOutputStream().flush()
            val all = s.getInputStream().readBytes()
            val split = (0 until all.size - 3).first { all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() && all[it + 2] == '\r'.code.toByte() && all[it + 3] == '\n'.code.toByte() }
            val status = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")[0].split(' ')[1].toInt()
            return Resp(status, String(all, split + 4, all.size - split - 4, Charsets.UTF_8))
        }
    }

    @Test
    fun `mark, list, read as JSON and CSV, change the note, delete`() {
        val created = call("POST", "/api/v1/events", """{"note":"jerk at 90"}""")
        assertEquals(201, created.status, created.text)
        val ev = created.json()["event"]!!.jsonObject
        val id = ev["id"]!!.jsonPrimitive.content
        assertEquals("jerk at 90", ev["note"]!!.jsonPrimitive.content)
        assertEquals("Api", ev["source"]!!.jsonPrimitive.content)
        assertEquals(listOf("event: jerk at 90"), backend.markers, "CSV marker row")
        assertEquals(201, call("POST", "/api/v1/events").status, "no body: no note")

        val list = call("GET", "/api/v1/events").json()["events"]!!.jsonArray
        assertEquals(2, list.size)
        assertEquals(id, list[1].jsonObject["id"]!!.jsonPrimitive.content, "newest first")
        assertEquals(1, call("GET", "/api/v1/events?limit=1").json()["events"]!!.jsonArray.size)
        assertEquals(0, call("GET", "/api/v1/events?car=other").json()["events"]!!.jsonArray.size)

        val t = backend.eventList.first { it.id == id }.timeMs
        backend.eventSnapshots[id] = EventSnapshot(t, t - 1000, t + 1000, listOf(SnapshotSeries("msv70/0x4807", "Engine speed", "rpm", listOf(-500, 500), listOf(800.0, 900.0))))
        val one = call("GET", "/api/v1/events/$id").json()
        assertEquals(2, one["snapshot"]!!.jsonObject["series"]!!.jsonArray[0].jsonObject["t"]!!.jsonArray.size)
        val csv = call("GET", "/api/v1/events/$id?format=csv")
        assertEquals(200, csv.status)
        assertTrue(csv.text.removePrefix("\uFEFF").startsWith("time_ms;iso_time;Engine speed [rpm];marker\r\n-500;"), csv.text)
        assertTrue(csv.text.contains(";event: jerk at 90\r\n"), csv.text)
        assertEquals(400, call("GET", "/api/v1/events/$id?format=xml").status)

        val patched = call("PATCH", "/api/v1/events/$id", """{"note":"cruise control off"}""")
        assertEquals(200, patched.status, patched.text)
        assertEquals("cruise control off", patched.json()["event"]!!.jsonObject["note"]!!.jsonPrimitive.content)
        assertEquals(422, call("PATCH", "/api/v1/events/$id", """{"note":"${"x".repeat(300)}"}""").status)
        assertEquals(400, call("PATCH", "/api/v1/events/$id", """{"other":1}""").status)

        assertEquals(204, call("DELETE", "/api/v1/events/$id").status)
        val gone = call("GET", "/api/v1/events/$id")
        assertEquals(404, gone.status)
        assertEquals("event_not_found", gone.errorCode)
        assertEquals(404, call("DELETE", "/api/v1/events/$id").status)
    }

    @Test
    fun `marking needs the config scope, reading the read scope`() {
        server.stop()
        scopes = setOf(Scope.Read)
        server = ApiServer(backend, { ApiAccess(token, scopes) })
        server.start(listOf(InetAddress.getLoopbackAddress()), 0)
        assertEquals(403, call("POST", "/api/v1/events").status)
        assertEquals(200, call("GET", "/api/v1/events").status)
    }

    @Test
    fun `the events are in the OpenAPI document`() {
        val doc = call("GET", "/api/v1/openapi.json")
        assertEquals(200, doc.status)
        val paths = doc.json()["paths"]!!.jsonObject
        assertTrue("/api/v1/events" in paths && "/api/v1/events/{id}" in paths)
        assertTrue(doc.json()["components"]!!.jsonObject["schemas"]!!.jsonObject.containsKey("DriveEvent"))
    }
}
