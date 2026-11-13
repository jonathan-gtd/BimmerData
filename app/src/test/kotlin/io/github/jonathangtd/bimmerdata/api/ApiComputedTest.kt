/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.profiles.Profiles
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

/** /api/v1/profiles/{profile}/computed on the real server (fake backend). */
class ApiComputedTest {
    private val token = ApiTokens.generate()
    private lateinit var backend: FakeApiBackend
    private lateinit var server: ApiServer

    @BeforeEach
    fun start() {
        backend = FakeApiBackend()
        server = ApiServer(backend, { ApiAccess(token, setOf(Scope.Read, Scope.Layout)) })
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
    fun `create, list, use, replace and delete a computed value`() {
        assertEquals(201, call("POST", "/api/v1/cars", """{"label":"My car","select":true}""").status)
        val created = call("POST", "/api/v1/profiles", """{"name":"Sport","ecus":["msv70"]}""")
        assertEquals(201, created.status, created.text)
        val pid = created.json()["id"]!!.jsonPrimitive.content
        val base = "/api/v1/profiles/$pid/computed"

        val empty = call("GET", base).json()
        assertEquals(0, empty["computed"]!!.jsonArray.size)
        assertEquals("Power", empty["kinds"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)

        val body = """{"kind":"Power","label":"Power","inputs":{"torque":"msv70/0x4402","rpm":"msv70/0x4807"}}"""
        val made = call("POST", base, body)
        assertEquals(201, made.status, made.text)
        val c = made.json()["computed"]!!.jsonObject
        assertEquals("calc/power", c["id"]!!.jsonPrimitive.content)
        assertEquals("ch", c["unit"]!!.jsonPrimitive.content)
        assertEquals(0, made.json()["warnings"]!!.jsonArray.size)
        // a second one gets the next key; unknown inputs are warned about
        val second = call("POST", base, """{"kind":"power","inputs":{"torque":"other/0x1","rpm":"msv70/0x4807"}}""")
        assertEquals(201, second.status, second.text)
        assertEquals("power_2", second.json()["computed"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals(1, second.json()["warnings"]!!.jsonArray.size)

        assertEquals("invalid_computed", call("POST", base, """{"kind":"Boost","inputs":{}}""").errorCode)
        assertEquals("invalid_computed", call("POST", base, """{"kind":"Power","inputs":{"torque":"msv70/0x4402"}}""").errorCode)
        assertEquals(409, call("POST", base, """{"key":"power","kind":"Power","inputs":{"torque":"msv70/0x4402","rpm":"msv70/0x4807"}}""").status)
        assertEquals(400, call("POST", base, """{"kind":"Power","inputs":{"torque":"msv70/0x4402","rpm":"msv70/0x4807"},"oops":1}""").status)
        assertEquals(2, call("GET", base).json()["computed"]!!.jsonArray.size)

        // on a card like any measurement (the fake keeps its dashboard apart: write it into the profile)
        val layout = Dashboard(phonePages = listOf(Page(id = "p", name = "Sport", columns = 2, rows = 2, cards = listOf(
            Card(id = "c1", type = GaugeType.BigNumber, measurementIds = listOf("calc/power")),
            Card(id = "c2", type = GaugeType.BigNumber, measurementIds = listOf("msv70/0x4300"), col = 1),
        ))))
        backend.profileSet = Profiles.writeDashboard(backend.profileSet, pid, layout)

        val replaced = call("PUT", "$base/power", """{"kind":"Power","label":"Puissance","inputs":{"torque":"msv70/0x4402","rpm":"msv70/0x4808"}}""")
        assertEquals(200, replaced.status, replaced.text)
        assertEquals("Puissance", replaced.json()["computed"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(400, call("PUT", "$base/power", """{"key":"other","kind":"Power","inputs":{"torque":"msv70/0x4402","rpm":"msv70/0x4808"}}""").status)
        assertEquals(404, call("PUT", "$base/nope", body).status)

        // the profile JSON and its export carry it
        val profile = call("GET", "/api/v1/profiles/$pid").json()
        assertEquals(2, profile["computed"]!!.jsonArray.size)
        assertTrue(call("GET", "/api/v1/profiles/$pid/export").text.contains("\"computed\""))

        // in use: never removed silently
        val inUse = call("DELETE", "$base/power")
        assertEquals(409, inUse.status)
        assertEquals("computed_in_use", inUse.errorCode)
        assertEquals(1, inUse.json()["error"]!!.jsonObject["details"]!!.jsonObject["cards"]!!.jsonPrimitive.int)
        assertEquals(204, call("DELETE", "$base/power?removeCards=true").status)
        assertEquals(listOf("c2"), backend.profileSet.profile(pid)!!.dashboard.phonePages[0].cards.map { it.id })
        assertEquals(204, call("DELETE", "$base/power_2").status, "not shown: deleted at once")
        assertEquals(404, call("DELETE", "$base/power_2").status)
        assertEquals(0, call("GET", base).json()["computed"]!!.jsonArray.size)
        assertEquals(404, call("GET", "/api/v1/profiles/nope/computed").status)
    }
}
