/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket

/** Starts the real server on 127.0.0.1 and talks HTTP to it. */
class ApiServerTest {
    private val token = ApiTokens.generate()
    @Volatile private var scopes = setOf(Scope.Read, Scope.Layout, Scope.Config)
    private lateinit var backend: FakeApiBackend
    private lateinit var server: ApiServer

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

    // ---------------------------------------------------------------- tiny HTTP client

    class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
        fun json(): JsonObject = Json.parseToJsonElement(text).jsonObject
        val errorCode get() = json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        override fun toString() = "$status $text"
    }

    private fun call(method: String, path: String, body: String? = null, auth: String? = token, headers: Map<String, String> = emptyMap()): Resp {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 20_000
            val bytes = body?.toByteArray(Charsets.UTF_8)
            val req = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nConnection: close\r\n")
                if (auth != null) append("Authorization: Bearer $auth\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (bytes != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            s.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            bytes?.let { s.getOutputStream().write(it) }
            s.getOutputStream().flush()
            return readResponse(s.getInputStream())
        }
    }

    private fun readResponse(input: InputStream): Resp {
        val all = input.readBytes()
        val split = (0 until all.size - 3).first { all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() && all[it + 2] == '\r'.code.toByte() && all[it + 3] == '\n'.code.toByte() }
        val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = head[0].split(' ')[1].toInt()
        val headers = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Resp(status, headers, all.copyOfRange(split + 4, all.size))
    }

    // ---------------------------------------------------------------- discovery & auth

    @Test
    fun `openapi document is public and describes every route`() {
        val r = call("GET", "/api/v1/openapi.json", auth = null)
        assertEquals(200, r.status, r.text)
        val doc = r.json()
        assertEquals("3.1.0", doc["openapi"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:${server.port}", doc["servers"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content)
        val paths = doc["paths"]!!.jsonObject
        for (route in server.service.routes) {
            val op = paths[route.path]?.jsonObject?.get(route.method.lowercase())?.jsonObject
            assertNotNull(op, "${route.method} ${route.path} missing from openapi.json")
            assertTrue(op!!["description"]!!.jsonPrimitive.content.length > 20)
        }
        val schemas = doc["components"]!!.jsonObject["schemas"]!!.jsonObject
        // every $ref points to a defined schema
        Regex("#/components/schemas/(\\w+)").findAll(r.text).map { it.groupValues[1] }.toSet().forEach {
            assertTrue(it in schemas, "schema $it referenced but not defined")
        }
        assertFalse(r.text.contains(token), "the token must never appear in the spec")
        // Kept as a build artifact for manual inspection / external validators.
        java.io.File("build/openapi.json").writeText(r.text)
        val opIds = paths.values.flatMap { it.jsonObject.values.map { op -> op.jsonObject["operationId"]!!.jsonPrimitive.content } }
        assertEquals(opIds.size, opIds.toSet().size, "operationIds must be unique")
        for ((path, item) in paths) for ((_, op) in item.jsonObject) {
            val declared = op.jsonObject["parameters"]?.jsonArray?.filter { it.jsonObject["in"]!!.jsonPrimitive.content == "path" }
                ?.map { it.jsonObject["name"]!!.jsonPrimitive.content }.orEmpty().toSet()
            assertEquals(Regex("""\{(\w+)}""").findAll(path).map { it.groupValues[1] }.toSet(), declared, "path parameters of $path")
        }
    }

    @Test
    fun `requests without or with a wrong token get 401`() {
        val missing = call("GET", "/api/v1/status", auth = null)
        assertEquals(401, missing.status)
        assertEquals("unauthorized", missing.errorCode)
        assertTrue(missing.headers["www-authenticate"]!!.startsWith("Bearer"))
        assertEquals(401, call("GET", "/api/v1/status", auth = "bd_wrong").status)
        assertEquals(401, call("GET", "/api/v1/status", auth = token.dropLast(1)).status)
    }

    @Test
    fun `operations outside the granted scopes get 403`() {
        scopes = setOf(Scope.Read)
        assertEquals(200, call("GET", "/api/v1/dashboard").status)
        val r = call("POST", "/api/v1/dashboard/phone/pages", """{"name":"X"}""")
        assertEquals(403, r.status)
        assertEquals("insufficient_scope", r.errorCode)
        assertEquals(403, call("POST", "/api/v1/connect", """{"mode":"simulation"}""").status)
        scopes = emptySet()
        assertEquals(403, call("GET", "/api/v1/status").status)
        // the index only needs a valid token and reports the scopes
        val index = call("GET", "/api/v1")
        assertEquals(200, index.status)
        assertEquals(0, index.json()["scopes"]!!.jsonArray.size)
    }

    @Test
    fun `repeated wrong tokens lock the peer out`() {
        repeat(10) { assertEquals(401, call("GET", "/api/v1/status", auth = "bd_nope").status) }
        val locked = call("GET", "/api/v1/status")
        assertEquals(429, locked.status)
        assertNotNull(locked.headers["retry-after"])
    }

    @Test
    fun `unknown paths and methods`() {
        assertEquals(404, call("GET", "/api/v1/nope").status)
        assertEquals(404, call("GET", "/").status)
        val r = call("DELETE", "/api/v1/status")
        assertEquals(405, r.status)
        assertEquals("GET", r.headers["allow"])
    }

    @Test
    fun `keep-alive serves several requests on one connection`() {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 5000
            val out = s.getOutputStream()
            val input = s.getInputStream()
            repeat(3) {
                out.write("GET /api/v1/status HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\n\r\n".toByteArray())
                out.flush()
                val head = readHead(input)
                assertTrue(head.startsWith("HTTP/1.1 200"), head)
                val len = Regex("Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
                val body = ByteArray(len)
                var n = 0
                while (n < len) n += input.read(body, n, len - n)
                assertTrue(String(body).contains("\"connection\""))
            }
        }
    }

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            val a = out.toByteArray()
            if (a.size >= 4 && String(a, a.size - 4, 4) == "\r\n\r\n") break
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    @Test
    fun `malformed and oversized requests are refused`() {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.getOutputStream().write("NONSENSE\r\n\r\n".toByteArray())
            assertEquals(400, readResponse(s.getInputStream()).status)
        }
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.getOutputStream().write("PUT /api/v1/dashboard HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n".toByteArray())
            assertEquals(413, readResponse(s.getInputStream()).status)
        }
        val bad = call("PUT", "/api/v1/dashboard", "{not json")
        assertEquals(400, bad.status)
    }

    // ---------------------------------------------------------------- live data

    @Test
    fun `status, ecus and measurement search`() {
        val st = call("GET", "/api/v1/status").json()
        assertEquals("disconnected", st["connection"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        val ecus = call("GET", "/api/v1/ecus").json()["ecus"]!!.jsonArray
        assertEquals("msv70", ecus[0].jsonObject["sgbd"]!!.jsonPrimitive.content)
        assertEquals("catalog", ecus[0].jsonObject["source"]!!.jsonPrimitive.content)
        val m = call("GET", "/api/v1/ecus/msv70/measurements?q=temp").json()
        assertEquals(2, m["total"]!!.jsonPrimitive.int)
        val first = m["measurements"]!!.jsonArray[0].jsonObject
        assertEquals("msv70/0x4402", first["id"]!!.jsonPrimitive.content)
        assertEquals("table", first["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("0x4402", first["source"]!!.jsonObject["tableId"]!!.jsonPrimitive.content)
        assertEquals(404, call("GET", "/api/v1/ecus/nope/measurements").status)
        val search = call("GET", "/api/v1/measurements?q=lambda").json()
        assertEquals(2, search["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `connect without a mode uses the last mode, 409 before any connection`() {
        val none = call("POST", "/api/v1/connect", "{}")
        assertEquals(409, none.status)
        assertEquals("no_mode", none.errorCode)
        assertEquals(400, call("POST", "/api/v1/connect", """{"mode":"wifi"}""").status)

        call("POST", "/api/v1/connect", """{"mode":"bluetooth"}""")
        assertEquals("bluetooth", call("GET", "/api/v1/settings").json()["lastMode"]!!.jsonPrimitive.content)
        call("POST", "/api/v1/disconnect")
        val again = call("POST", "/api/v1/connect", "{}").json()
        assertEquals("bluetooth", again["connection"]!!.jsonObject["mode"]!!.jsonPrimitive.content, "last mode reused")
    }

    @Test
    fun `read_values needs a connection, then waits for fresh values and samples a series`() {
        val notConnected = call("POST", "/api/v1/values/read", """{"ids":["msv70/0x4402"]}""")
        assertEquals(409, notConnected.status)
        assertEquals("not_connected", notConnected.errorCode)

        val c = call("POST", "/api/v1/connect", """{"mode":"simulation"}""").json()
        assertEquals("connected", c["connection"]!!.jsonObject["state"]!!.jsonPrimitive.content)

        val unknown = call("POST", "/api/v1/values/read", """{"ids":["msv70/0x9999"]}""")
        assertEquals(404, unknown.status)
        assertEquals("unknown_measurement", unknown.errorCode)

        val r = call("POST", "/api/v1/values/read", """{"ids":["msv70/0x4402","msv70/status_motor"]}""")
        assertEquals(200, r.status, r.text)
        val values = r.json()["values"]!!.jsonArray.map { it.jsonObject }
        assertEquals("fresh", values[0]["status"]!!.jsonPrimitive.content)
        assertTrue(values[0]["value"]!!.jsonPrimitive.double > 90.0)
        assertEquals("°C", values[0]["unit"]!!.jsonPrimitive.content)
        assertEquals("Motor läuft", values[1]["text"]!!.jsonPrimitive.content)
        // the lease is released after the read
        assertTrue(waitUntil { backend.requested.isEmpty() })

        val timed = call("POST", "/api/v1/values/read", """{"ids":["msv70/0x4402"],"seconds":1}""").json()
        val series = timed["series"]!!.jsonArray[0].jsonObject
        assertTrue(series["stats"]!!.jsonObject["count"]!!.jsonPrimitive.int >= 5, series.toString())
        assertTrue(series["points"]!!.jsonArray.size >= 5)

        val latest = call("GET", "/api/v1/values?ids=msv70/0x4402").json()["values"]!!.jsonArray[0].jsonObject
        assertNotNull(latest["value"])
        val history = call("GET", "/api/v1/history?ids=msv70/0x4402&seconds=60").json()
        assertTrue(history["series"]!!.jsonArray[0].jsonObject["points"]!!.jsonArray.size >= 5)
    }

    @Test
    fun `watch keeps measurements read until cleared`() {
        call("POST", "/api/v1/connect", """{"mode":"simulation"}""")
        val w = call("POST", "/api/v1/values/watch", """{"ids":["msv70/0x4300"],"seconds":60}""")
        assertEquals(200, w.status, w.text)
        assertEquals(listOf("msv70/0x4300"), backend.requested.map { it.id })
        assertTrue(call("GET", "/api/v1/status").json()["apiReads"]!!.jsonArray.isNotEmpty())
        assertEquals(204, call("DELETE", "/api/v1/values/watch").status)
        assertTrue(backend.requested.isEmpty())
    }

    @Test
    fun `server-sent events stream samples`() {
        call("POST", "/api/v1/connect", """{"mode":"simulation"}""")
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("GET /api/v1/stream?ids=msv70/0x4402&intervalMs=100 HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\n\r\n".toByteArray())
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            assertTrue(reader.readLine().startsWith("HTTP/1.1 200"))
            var sawSamples = false
            var sawStatus = false
            val deadline = System.currentTimeMillis() + 4000
            var event = ""
            while (System.currentTimeMillis() < deadline && !(sawSamples && sawStatus)) {
                val line = reader.readLine() ?: break
                if (line.startsWith("event: ")) event = line.removePrefix("event: ")
                if (line.startsWith("data: ")) {
                    val data = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                    if (event == "samples") {
                        assertNotNull(data["values"]!!.jsonObject["msv70/0x4402"]!!.jsonObject["v"])
                        sawSamples = true
                    }
                    if (event == "status") sawStatus = true
                }
            }
            assertTrue(sawSamples && sawStatus)
            assertEquals(listOf("msv70/0x4402"), backend.requested.map { it.id })
        }
        // closing the stream releases its measurements
        assertTrue(waitUntil { backend.requested.isEmpty() })
    }

    // ---------------------------------------------------------------- dashboard

    @Test
    fun `add, update and delete cards with precise errors`() {
        val add = call("POST", "/api/v1/dashboard/phone/pages/p1/cards", """{"type":"Dial","measurementIds":["msv70/0x4402"],"title":"Oil","rowSpan":2}""")
        assertEquals(201, add.status, add.text)
        val card = add.json()["card"]!!.jsonObject
        val id = card["id"]!!.jsonPrimitive.content
        assertEquals(0, card["col"]!!.jsonPrimitive.int)
        assertEquals(1, backend.dashboard.phonePages[0].cards.size)

        // explicit position on top of the first card
        val overlap = call("POST", "/api/v1/dashboard/phone/pages/0/cards","""{"type":"Trend","measurementIds":["msv70/0x4300"],"col":0,"row":1}""")
        assertEquals(409, overlap.status, overlap.text)
        assertEquals("card_overlap", overlap.errorCode)
        assertTrue(overlap.json()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("free cells"))

        val badCount = call("POST", "/api/v1/dashboard/phone/pages/p1/cards", """{"type":"MultiValue","measurementIds":["msv70/0x4807"]}""")
        assertEquals(422, badCount.status)
        assertEquals("measurement_count", badCount.json()["error"]!!.jsonObject["details"]!!.jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content)

        val unknownField = call("POST", "/api/v1/dashboard/phone/pages/p1/cards", """{"type":"Dial","measurementIds":["msv70/0x4402"],"colour":"red"}""")
        assertEquals(400, unknownField.status)

        val warn = call("POST", "/api/v1/dashboard/phone/pages/p1/cards", """{"type":"BigNumber","measurementIds":["ds2_ews/0x1"],"col":1,"row":0}""")
        assertEquals(201, warn.status, warn.text)
        assertEquals(1, warn.json()["warnings"]!!.jsonArray.size)

        val patch = call("PATCH", "/api/v1/dashboard/phone/pages/p1/cards/$id", """{"warnAbove":130,"title":null}""")
        assertEquals(200, patch.status, patch.text)
        val patched = backend.dashboard.phonePages[0].card(id)!!
        assertEquals(130.0, patched.warnAbove)
        assertEquals(null, patched.title)

        val moveOnto = call("PATCH", "/api/v1/dashboard/phone/pages/p1/cards/$id", """{"col":1}""")
        assertEquals(409, moveOnto.status)

        assertEquals(204, call("DELETE", "/api/v1/dashboard/phone/pages/p1/cards/$id").status)
        val gone = call("DELETE", "/api/v1/dashboard/phone/pages/p1/cards/$id")
        assertEquals(404, gone.status)
        assertEquals("card_not_found", gone.errorCode)
        assertEquals(404, call("GET", "/api/v1/dashboard/phone/pages/zzz").status)
    }

    @Test
    fun `pages with their grid`() {
        val p = call("POST", "/api/v1/dashboard/car/pages", """{"name":"Temps","columns":3,"rows":2}""")
        assertEquals(201, p.status, p.text)
        val page = p.json()
        assertEquals(3, page["columns"]!!.jsonPrimitive.int)
        assertEquals(1, page["index"]!!.jsonPrimitive.int)
        val pid = page["id"]!!.jsonPrimitive.content
        val tooBig = call("POST", "/api/v1/dashboard/car/pages", """{"name":"X","rows":3}""")
        assertEquals(422, tooBig.status)
        repeat(3) { call("POST", "/api/v1/dashboard/car/pages/$pid/cards", """{"type":"Dial","measurementIds":["msv70/0x4402"]}""") }
        // the 3 cards do not fit a 1×1 grid; a 3×1 grid takes them
        assertEquals(409, call("PATCH", "/api/v1/dashboard/car/pages/$pid", """{"columns":1,"rows":1}""").status)
        assertEquals(200, call("PATCH", "/api/v1/dashboard/car/pages/$pid", """{"columns":3,"rows":1}""").status)
        assertEquals(3 to 1, backend.dashboard.carPages[1].let { it.columns to it.rows })
        // ready-made arrangements no longer exist
        assertEquals(404, call("GET", "/api/v1/presets?target=car").status)
        assertEquals(400, call("POST", "/api/v1/dashboard/car/pages", """{"name":"X","presetId":"hero"}""").status, "unknown field")
        assertEquals(200, call("PATCH", "/api/v1/dashboard/car/pages/$pid", """{"name":"Renamed","position":0}""").status)
        assertEquals("Renamed", backend.dashboard.carPages[0].name)
        assertEquals(204, call("DELETE", "/api/v1/dashboard/car/pages/c1").status)
        // Deleting the last page leaves the side empty (valid); resets touch one side only.
        val phoneBefore = backend.dashboard.phonePages
        assertEquals(204, call("DELETE", "/api/v1/dashboard/car/pages/0").status)
        assertTrue(backend.dashboard.carPages.isEmpty())
        assertEquals(phoneBefore, backend.dashboard.phonePages)
        assertEquals(201, call("POST", "/api/v1/dashboard/car/pages", """{"name":"Again"}""").status)
        assertEquals(200, call("POST", "/api/v1/dashboard/car/reset").status)
        assertTrue(backend.dashboard.carPages.isEmpty())
        assertEquals(phoneBefore, backend.dashboard.phonePages, "car reset leaves the phone alone")
        assertEquals(200, call("GET", "/api/v1/dashboard").status)
        assertEquals(200, call("POST", "/api/v1/dashboard/reset").status)
        assertTrue(backend.dashboard.phonePages.isEmpty() && backend.dashboard.carPages.isEmpty())
        assertEquals(6, call("GET", "/api/v1/card-types").json()["types"]!!.jsonArray.size)
    }

    @Test
    fun `set_dashboard validates strictly and supports dry runs`() {
        val invalid = """{"phonePages":[{"id":"a","name":"A","columns":2,"rows":2,"cards":[
            {"id":"x","type":"Dial","measurementIds":["msv70/0x4402"],"colSpan":2},
            {"id":"y","type":"Dial","measurementIds":["msv70/0x4300"],"col":1}]}],
            "carPages":[{"id":"b","name":"B","columns":5,"rows":1}]}"""
        val r = call("PUT", "/api/v1/dashboard", invalid)
        assertEquals(422, r.status, r.text)
        val details = r.json()["error"]!!.jsonObject["details"]!!.jsonArray.map { it.jsonObject }
        assertTrue(details.any { it["code"]!!.jsonPrimitive.content == "card_overlap" && it["path"]!!.jsonPrimitive.content == "phonePages[0].cards[1]" }, details.toString())
        assertTrue(details.any { it["code"]!!.jsonPrimitive.content == "invalid_grid" && it["path"]!!.jsonPrimitive.content == "carPages[0].columns" })
        assertEquals(0, backend.saves)

        val validation = call("POST", "/api/v1/dashboard/validate", invalid).json()
        assertFalse(validation["valid"]!!.jsonPrimitive.boolean)

        val valid = """{"phonePages":[{"id":"a","name":"A","columns":2,"rows":2,"cards":[{"id":"x","type":"Dial","measurementIds":["msv70/0x4402"]}]}],
            "carPages":[{"id":"b","name":"B","columns":4,"rows":1}]}"""
        val dry = call("PUT", "/api/v1/dashboard?dryRun=true", valid)
        assertEquals(200, dry.status, dry.text)
        assertFalse(dry.json()["saved"]!!.jsonPrimitive.boolean)
        assertEquals(0, backend.saves)
        val saved = call("PUT", "/api/v1/dashboard", valid)
        assertTrue(saved.json()["saved"]!!.jsonPrimitive.boolean)
        assertEquals("x", backend.dashboard.phonePages[0].cards[0].id)
        // round trip
        val got = call("GET", "/api/v1/dashboard").json()
        assertEquals("a", got["phonePages"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(200, call("PUT", "/api/v1/dashboard", got.toString()).status)
    }

    @Test
    fun `render_page returns a PNG from the renderer`() {
        backend.dashboard = backend.dashboard.copy(carPages = listOf(backend.dashboard.carPages[0].copy(cards = listOf(
            io.github.jonathangtd.bimmerdata.dashboard.Card(id = "k", type = io.github.jonathangtd.bimmerdata.gauges.GaugeType.Dial, measurementIds = listOf("msv70/0x4402")),
        ))))
        val r = call("GET", "/api/v1/dashboard/car/pages/0/render.png")
        assertEquals(200, r.status)
        assertEquals("image/png", r.headers["content-type"])
        assertArrayEquals(FakeApiBackend.PNG_SIGNATURE, r.body.copyOf(8))
        val req = backend.lastRender!!
        assertEquals(1280, req.width)
        assertEquals(480, req.height)
        assertEquals("Öltemperatur", req.index["msv70/0x4402"]!!.label)

        val draft = call("POST", "/api/v1/dashboard/phone/render?width=540&height=960", """{"name":"Draft","columns":1,"rows":1,"cards":[{"type":"BigNumber","measurementIds":["msv70/0x4300"]}]}""")
        assertEquals(200, draft.status, draft.text)
        assertEquals(540, backend.lastRender!!.width)
        assertEquals(400, call("GET", "/api/v1/dashboard/car/pages/0/render.png?width=10").status)
    }

    // ---------------------------------------------------------------- config

    @Test
    fun `recording, recordings and settings`() {
        assertEquals(409, call("POST", "/api/v1/recording/start").status)
        call("POST", "/api/v1/connect", """{"mode":"usb"}""")
        val rec = call("POST", "/api/v1/recording/start").json()
        assertTrue(rec["recording"]!!.jsonPrimitive.boolean)
        assertEquals(200, call("POST", "/api/v1/recording/markers", """{"label":"3rd gear"}""").status)
        assertEquals(listOf("3rd gear"), backend.markers)
        assertFalse(call("POST", "/api/v1/recording/stop").json()["recording"]!!.jsonPrimitive.boolean)
        val list = call("GET", "/api/v1/recordings").json()["recordings"]!!.jsonArray
        assertEquals("bimmerdata_test.csv", list[0].jsonObject["name"]!!.jsonPrimitive.content)
        val csv = call("GET", "/api/v1/recordings/bimmerdata_test.csv")
        assertEquals(200, csv.status)
        assertTrue(csv.text.startsWith("time;oil"))
        assertEquals(404, call("GET", "/api/v1/recordings/..%2Fetc%2Fpasswd").status)

        val s = call("PATCH", "/api/v1/settings", """{"autoConnectOnCable":true,"bluetoothConnectOnStart":true}""").json()
        assertTrue(s["autoConnectOnCable"]!!.jsonPrimitive.boolean)
        assertTrue(s["bluetoothConnectOnStart"]!!.jsonPrimitive.boolean)
        assertEquals(400, call("PATCH", "/api/v1/settings", """{"defaultMode":"usb"}""").status, "no default mode any more")
        val api = call("PATCH", "/api/v1/settings", """{"api":{"scopes":["read","layout","config","diag"]}}""")
        assertEquals(403, api.status)
        assertEquals("phone_only", api.errorCode)
        assertFalse(call("GET", "/api/v1/settings").text.contains(token))

        val disc = call("POST", "/api/v1/disconnect").json()
        assertEquals("disconnected", disc["connection"]!!.jsonObject["state"]!!.jsonPrimitive.content)
    }

    private fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }
}
