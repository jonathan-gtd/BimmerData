/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpRequest
import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/** /api/v1/lock straight through [ApiService.handle], with a clock under control. */
class ApiLockTest {
    private val token = ApiTokens.generate()
    private var now = 1_000_000L
    private val lock = EditLock { now }
    private val backend = FakeApiBackend()
    private var scopes = setOf(Scope.Read, Scope.Layout)
    private val service = ApiService(backend, { ApiAccess(token, scopes) }, clock = { now }, editLock = lock)

    @AfterEach
    fun close() = backend.close()

    private fun call(method: String, body: String = ""): Pair<Int, JsonObject> {
        val r = service.handle(HttpRequest(method, "/api/v1/lock", emptyMap(), mapOf("authorization" to "Bearer $token"), body.toByteArray(), InetAddress.getLoopbackAddress())) as HttpResponse.Bytes
        return r.status to ApiJson.parseToJsonElement(String(r.body, Charsets.UTF_8)).jsonObject
    }

    @Test
    fun `lock, renew, read and release`() {
        assertEquals(JsonNull, call("GET").second["lock"])
        val (code, taken) = call("PUT", """{"reason":"Adding a profile","seconds":120}""")
        assertEquals(200, code)
        val l = taken["lock"]!!.jsonObject
        assertEquals("Adding a profile", l["reason"]!!.jsonPrimitive.content)
        assertEquals(now, l["sinceMs"]!!.jsonPrimitive.long)
        assertEquals(now + 120_000, l["untilMs"]!!.jsonPrimitive.long)
        assertEquals(120, l["secondsLeft"]!!.jsonPrimitive.long)
        assertEquals("Adding a profile", lock.current()?.reason)

        val since = now
        now += 60_000
        val renewed = call("PUT", """{"reason":"Mirroring Android Auto"}""").second["lock"]!!.jsonObject
        assertEquals(since, renewed["sinceMs"]!!.jsonPrimitive.long, "renewing keeps the start")
        assertEquals(now + EditLock.DEFAULT_SECONDS * 1000L, renewed["untilMs"]!!.jsonPrimitive.long)

        val (dc, released) = call("DELETE")
        assertEquals(200, dc)
        assertTrue(released["released"]!!.jsonPrimitive.boolean)
        assertNull(lock.current())
        assertFalse(call("DELETE").second["released"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `the lock ends by itself`() {
        call("PUT", """{"reason":"x","seconds":30}""")
        now += 29_999
        assertEquals("x", lock.current()?.reason)
        now += 1
        assertNull(lock.current())
        assertEquals(JsonNull, call("GET").second["lock"])
    }

    @Test
    fun `bad requests and scopes`() {
        assertEquals(400, call("PUT", "{}").first)
        assertEquals(400, call("PUT", """{"reason":"   "}""").first)
        assertEquals(400, call("PUT", """{"reason":"${"a".repeat(EditLock.MAX_REASON + 1)}"}""").first)
        assertEquals(400, call("PUT", """{"reason":"x","seconds":5}""").first)
        assertEquals(400, call("PUT", """{"reason":"x","seconds":3601}""").first)
        assertEquals(400, call("PUT", """{"reason":"x","other":1}""").first)
        assertNull(lock.current())
        scopes = setOf(Scope.Read)
        assertEquals(403, call("PUT", """{"reason":"x"}""").first)
        assertEquals(403, call("DELETE").first)
        assertEquals(200, call("GET").first)
    }
}
