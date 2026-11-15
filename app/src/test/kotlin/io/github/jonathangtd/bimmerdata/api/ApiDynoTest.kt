/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.dyno.DynoRuns
import io.github.jonathangtd.bimmerdata.dyno.DynoTestData
import io.github.jonathangtd.bimmerdata.dyno.FakeDyno
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
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

/** /api/v1/dyno and the car values (physics) on the real server, with a fake dyno. */
class ApiDynoTest {
    private val token = ApiTokens.generate()
    private lateinit var backend: FakeApiBackend
    private lateinit var server: ApiServer
    private lateinit var dyno: FakeDyno

    @BeforeEach
    fun start() {
        backend = FakeApiBackend()
        dyno = FakeDyno { backend.cars().selected?.physics?.massKg }
        backend.dynoControl = dyno
        server = ApiServer(backend, { ApiAccess(token, setOf(Scope.Read, Scope.Layout, Scope.Config)) })
        server.start(listOf(InetAddress.getLoopbackAddress()), 0)
    }

    @AfterEach
    fun stop() {
        server.stop()
        backend.close()
    }

    private class Resp(val status: Int, val text: String, val type: String) {
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
            val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val type = head.firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
            return Resp(head[0].split(' ')[1].toInt(), String(all, split + 4, all.size - split - 4, Charsets.UTF_8), type)
        }
    }

    private fun newCar(): String {
        val r = call("POST", "/api/v1/cars", """{"label":"My car","select":true}""")
        assertEquals(201, r.status, r.text)
        return r.json()["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `car values - set, merged, checked, removed`() {
        val id = newCar()
        assertEquals(0, call("GET", "/api/v1/cars/$id").json()["physics"]!!.jsonObject.size)
        val set = call("PUT", "/api/v1/cars/$id", """{"physics":{"massKg":1620,"dragAreaM2":0.62}}""")
        assertEquals(200, set.status, set.text)
        assertEquals(1620.0, set.json()["physics"]!!.jsonObject["massKg"]!!.jsonPrimitive.double)
        // a field given replaces it, the others stay; null removes one
        val merged = call("PUT", "/api/v1/cars/$id", """{"physics":{"rollingResistance":0.011,"dragAreaM2":null}}""").json()["physics"]!!.jsonObject
        assertEquals(setOf("massKg", "rollingResistance"), merged.keys)
        assertEquals(1620.0, backend.cars().car(id)!!.physics.massKg)
        // out of range, wrong type, unknown field
        assertEquals("invalid_car", call("PUT", "/api/v1/cars/$id", """{"physics":{"massKg":20}}""").errorCode)
        assertEquals("invalid_car", call("PUT", "/api/v1/cars/$id", """{"physics":{"massKg":"heavy"}}""").errorCode)
        assertEquals("invalid_car", call("PUT", "/api/v1/cars/$id", """{"physics":{"weight":1500}}""").errorCode)
        assertEquals(1620.0, backend.cars().car(id)!!.physics.massKg)
        // all removed
        assertEquals(0, call("PUT", "/api/v1/cars/$id", """{"physics":null}""").json()["physics"]!!.jsonObject.size)
    }

    @Test
    fun `status, inputs, arm needs the mass`() {
        val id = newCar()
        val st = call("GET", "/api/v1/dyno").json()
        assertEquals("idle", st["state"]!!.jsonObject["phase"]!!.jsonPrimitive.content)
        assertEquals(false, st["car"]!!.jsonObject["ready"]!!.jsonPrimitive.boolean)
        assertEquals("car_mass_missing", st["car"]!!.jsonObject["problems"]!!.jsonArray[0].jsonPrimitive.content)
        val used = st["inputs"]!!.jsonObject["used"]!!.jsonObject
        assertEquals(DynoTestData.rpm.id, used["rpm"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("rpm", used["rpm"]!!.jsonObject["unit"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, used["overlay"])

        val refused = call("POST", "/api/v1/dyno/arm")
        assertEquals(409, refused.status)
        assertEquals("car_mass_missing", refused.errorCode)
        call("PUT", "/api/v1/cars/$id", """{"physics":{"massKg":1500}}""")
        val armed = call("POST", "/api/v1/dyno/arm")
        assertEquals(200, armed.status, armed.text)
        assertEquals("armed", armed.json()["state"]!!.jsonObject["phase"]!!.jsonPrimitive.content)
        assertEquals("running", call("POST", "/api/v1/dyno/start").json()["state"]!!.jsonObject["phase"]!!.jsonPrimitive.content)
        assertEquals("armed", call("POST", "/api/v1/dyno/stop").json()["state"]!!.jsonObject["phase"]!!.jsonPrimitive.content)
        assertEquals("idle", call("POST", "/api/v1/dyno/disarm").json()["state"]!!.jsonObject["phase"]!!.jsonPrimitive.content)
        assertEquals(true, call("GET", "/api/v1/dyno").json()["car"]!!.jsonObject["ready"]!!.jsonPrimitive.boolean)

        // inputs: wrong unit refused, the others saved; null goes back to the default
        assertEquals("invalid_dyno_input", call("PUT", "/api/v1/dyno/settings", """{"rpm":"${DynoTestData.speed.id}"}""").errorCode)
        assertEquals("invalid_dyno_input", call("PUT", "/api/v1/dyno/settings", """{"speed":"nonsense"}""").errorCode)
        assertEquals("invalid_dyno_input", call("PUT", "/api/v1/dyno/settings", """{"drivelineLossPct":80}""").errorCode)
        val saved = call("PUT", "/api/v1/dyno/settings", """{"speed":"gps/speed","overlay":"${DynoTestData.torque.id}","drivelineLossPct":12}""")
        assertEquals(200, saved.status, saved.text)
        assertEquals("gps/speed", saved.json()["used"]!!.jsonObject["speed"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(12.0, dyno.inputs(id).drivelineLossPct)
        assertEquals(DynoTestData.torque.id, dyno.inputs(id).overlay)
        val reset = call("PUT", "/api/v1/dyno/settings", """{"speed":null}""").json()
        assertEquals(DynoTestData.speed.id, reset["used"]!!.jsonObject["speed"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(DynoTestData.torque.id, dyno.inputs(id).overlay)
        assertTrue(reset["candidates"]!!.jsonArray.any { c -> c.jsonObject["id"]!!.jsonPrimitive.content == "gps/speed" && c.jsonObject["roles"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf("speed") })
    }

    @Test
    fun `runs - list with peaks, curve, raw readings, CSV, name, delete`() {
        val id = newCar()
        call("PUT", "/api/v1/cars/$id", """{"physics":{"massKg":1500}}""")
        dyno.book = DynoRuns.add(DynoRuns.add(dyno.book, DynoTestData.run(id, startMs = 1_000, id = "a")), DynoTestData.run(id, startMs = 2_000, id = "b"))
        dyno.book = DynoRuns.add(dyno.book, DynoTestData.run("car-other", id = "elsewhere"))

        val list = call("GET", "/api/v1/dyno/runs").json()["runs"]!!.jsonArray
        assertEquals(listOf("b", "a"), list.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val peak = list[0].jsonObject["result"]!!.jsonObject["peakWheelPowerCh"]!!.jsonPrimitive.double
        assertTrue(peak in 155.0..172.0, "$peak")
        assertTrue("points" !in list[0].jsonObject["result"]!!.jsonObject)
        assertEquals(3, call("GET", "/api/v1/dyno/runs?car=all").json()["runs"]!!.jsonArray.size)

        val one = call("GET", "/api/v1/dyno/runs/a").json()
        assertTrue(one["result"]!!.jsonObject["points"]!!.jsonArray.size > 5)
        assertTrue("raw" !in one)
        val raw = call("GET", "/api/v1/dyno/runs/a?raw=true").json()
        assertTrue(raw["raw"]!!.jsonObject["rpm"]!!.jsonObject["t"]!!.jsonArray.size > 50)
        assertTrue(raw["result"]!!.jsonObject["processed"]!!.jsonArray.size > 50)

        val csv = call("GET", "/api/v1/dyno/runs/a/csv")
        assertEquals(200, csv.status)
        assertTrue(csv.type.startsWith("text/csv"), csv.type)
        assertTrue(csv.text.startsWith("# BimmerData virtual dyno"))

        assertEquals("4th", call("PUT", "/api/v1/dyno/runs/a", """{"note":"4th"}""").json()["note"]!!.jsonPrimitive.content)
        assertEquals(200, call("DELETE", "/api/v1/dyno/runs/a").status)
        assertEquals("run_not_found", call("GET", "/api/v1/dyno/runs/a").errorCode)
        assertEquals("run_not_found", call("DELETE", "/api/v1/dyno/runs/a").errorCode)
    }

    @Test
    fun `no dyno on this backend - 501, documented in the OpenAPI file`() {
        newCar()
        backend.dynoControl = null
        assertEquals(501, call("GET", "/api/v1/dyno").status)
        val doc = call("GET", "/api/v1/openapi.json")
        assertEquals(200, doc.status)
        val paths = doc.json()["paths"]!!.jsonObject
        for (p in listOf("/api/v1/dyno", "/api/v1/dyno/arm", "/api/v1/dyno/runs/{run}", "/api/v1/dyno/runs/{run}/csv")) assertTrue(p in paths, p)
        assertTrue("CarPhysics" in doc.json()["components"]!!.jsonObject["schemas"]!!.jsonObject)
    }
}
