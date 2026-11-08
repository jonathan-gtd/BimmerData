/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Socket

/** /api/v1/profiles on the real server (fake backend). */
class ApiProfilesTest {
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
    fun `create, list, change, activate, export and delete profiles`() {
        assertEquals(0, call("GET", "/api/v1/profiles").json()["profiles"]!!.jsonArray.size, "a fresh install has none")

        val r = call("POST", "/api/v1/profiles", """{"name":"E90 330i · Lambda","series":"E90","model":"330i","ecus":["MSV70"],"vehicleId":"AB12345"}""")
        assertEquals(201, r.status, r.text)
        val lambdaId = r.json()["id"]!!.jsonPrimitive.content
        assertEquals("msv70", r.json()["ecus"]!!.jsonArray[0].jsonPrimitive.content)

        // second profile, not activated, pages copied from the first
        call("PUT", "/api/v1/profiles/$lambdaId", """{"dashboard":{"phonePages":[{"name":"Engine","columns":2,"rows":3}],"carPages":[]}}""").also { assertEquals(200, it.status, it.text) }
        val knock = call("POST", "/api/v1/profiles", """{"name":"Knock","copyFrom":"$lambdaId","activate":false}""")
        assertEquals(201, knock.status, knock.text)
        val knockId = knock.json()["id"]!!.jsonPrimitive.content
        assertEquals(1, knock.json()["dashboard"]!!.jsonObject["phonePages"]!!.jsonArray.size)

        val list = call("GET", "/api/v1/profiles").json()
        assertEquals(lambdaId, list["activeId"]!!.jsonPrimitive.content)
        val summaries = list["profiles"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("E90 330i · Lambda", "Knock"), summaries.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("E90 330i", summaries[0]["vehicle"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertTrue(summaries[0]["active"]!!.jsonPrimitive.boolean)
        assertEquals(1, summaries[1]["phonePages"]!!.jsonPrimitive.int)

        // invalid changes: nothing written
        assertEquals(422, call("PUT", "/api/v1/profiles/$knockId", """{"ecus":["../etc"]}""").status)
        val badLayout = call("PUT", "/api/v1/profiles/$knockId", """{"dashboard":{"phonePages":[{"name":"x","columns":9,"rows":3}],"carPages":[]}}""")
        assertEquals(422, badLayout.status, badLayout.text)
        assertEquals(404, call("GET", "/api/v1/profiles/nope").status)
        assertEquals("profile_not_found", call("POST", "/api/v1/profiles/nope/activate").errorCode)
        assertEquals(422, call("POST", "/api/v1/profiles", """{"series":"E90"}""").status, "a name is required")

        // activate, then rename and change the car of the inactive one
        assertEquals(200, call("POST", "/api/v1/profiles/$knockId/activate").status)
        assertEquals(knockId, backend.profileSet.activeId)
        val renamed = call("PUT", "/api/v1/profiles/$lambdaId", """{"name":"Lambda","model":"","ecus":["msv70","egs"]}""").json()
        assertEquals("Lambda", renamed["name"]!!.jsonPrimitive.content)
        assertEquals(ProfileVehicle("E90", null, "AB12345"), backend.profileSet.profile(lambdaId)!!.vehicle)
        assertEquals(listOf("msv70", "egs"), backend.profileSet.profile(lambdaId)!!.ecus)

        // shared file: no VIN link
        val export = call("GET", "/api/v1/profiles/$lambdaId/export")
        assertEquals(200, export.status, export.text)
        assertEquals("bimmerdata-profile", export.json()["kind"]!!.jsonPrimitive.content)
        assertFalse(export.text.contains("AB12345"))

        // delete the active one: the other becomes active
        val del = call("DELETE", "/api/v1/profiles/$knockId").json()
        assertEquals(lambdaId, del["activeId"]!!.jsonPrimitive.content)
        call("DELETE", "/api/v1/profiles/$lambdaId")
        assertNull(backend.profileSet.activeId)
        assertTrue(backend.profileSet.profiles.isEmpty())
    }

    @Test
    fun `at most the profile limit`() {
        repeat(Profiles.MAX_PROFILES) { i -> backend.editProfiles { s -> Profiles.create(s, "P$i", nowMs = 1).first to Unit } }
        val r = call("POST", "/api/v1/profiles", """{"name":"One more"}""")
        assertEquals(422, r.status, r.text)
        assertEquals("invalid_profile", r.errorCode)
    }
}
