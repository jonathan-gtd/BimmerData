/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpRequest
import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress

/** Swagger UI routes and the Telegraf export routes, straight through [ApiService.handle]. */
class ApiDocsTelegrafTest {
    private val token = ApiTokens.generate()
    private val assets = File("src/main/assets/swagger-ui")

    private class TelegrafBackend(val inner: FakeApiBackend = FakeApiBackend()) : ApiBackend by inner {
        var view = TelegrafView(
            false, "", "token", "", true, "", "afterTrip",
            TelegrafStateView("off", 12, 1200, 0, 0, false, 5_000_000_000, 524_288_000, 0),
        )
        var sent = 0

        override fun telegraf() = view
        override fun updateTelegraf(patch: TelegrafPatch): TelegrafView {
            view = view.copy(
                enabled = patch.enabled ?: view.enabled, url = patch.url ?: view.url, car = patch.car ?: view.car,
                mode = patch.mode ?: view.mode,
            )
            return view
        }
        override fun testTelegraf() = TelegrafTest(true, "ok")
        override fun sendTelegrafNow() { sent++ }
    }

    private val backend = TelegrafBackend()
    private val service = ApiService(backend, { ApiAccess(token, setOf(Scope.Read, Scope.Config)) }, docsAssets = { name -> File(assets, name).takeIf { it.isFile }?.readBytes() })

    private fun call(method: String, path: String, body: String = "", auth: Boolean = true): HttpResponse.Bytes {
        val headers = if (auth) mapOf("authorization" to "Bearer $token") else emptyMap()
        val r = service.handle(HttpRequest(method, path, emptyMap(), headers, body.toByteArray(), InetAddress.getLoopbackAddress()))
        return r as HttpResponse.Bytes
    }

    private fun json(r: HttpResponse.Bytes) = ApiJson.parseToJsonElement(r.body.toString(Charsets.UTF_8)).jsonObject

    @Test
    fun `swagger ui page and files are public`() {
        val page = call("GET", "/api/v1/docs", auth = false)
        assertEquals(200, page.status)
        assertTrue(page.contentType.startsWith("text/html"))
        val html = page.body.toString(Charsets.UTF_8)
        assertTrue(html.contains("url: '/api/v1/openapi.json'"), html)
        assertTrue(html.contains("/api/v1/docs/swagger-ui-bundle.js"))
        val js = call("GET", "/api/v1/docs/swagger-ui-bundle.js", auth = false)
        assertEquals(200, js.status)
        assertTrue(js.contentType.startsWith("application/javascript"))
        assertTrue(js.body.size > 100_000)
        assertEquals(404, call("GET", "/api/v1/docs/..%2FLICENSE", auth = false).status)
        assertEquals(404, call("GET", "/api/v1/docs/index.html", auth = false).status)
    }

    @Test
    fun `in-app page with the inline spec`() {
        // written for a visual check in a browser (bd-scripts/set_docs_shot.sh)
        val spec = ApiJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), OpenApi.document(service.routes, null, "test"))
        val html = ApiDocs.html(assets.absoluteFile.toURI().toString().trimEnd('/') + "/", specJson = spec, tryItOut = false, dark = true)
        File("build/apidocs").mkdirs()
        File("build/apidocs/index.html").writeText(html)
        assertTrue(html.contains("/api/v1/export/telegraf"))
    }

    @Test
    fun `inline spec cannot close the script element`() {
        val html = ApiDocs.html("x/", specJson = """{"a":"</script><b>"}""", tryItOut = false)
        assertFalse(html.contains("</script><b>"))
        assertTrue(html.contains("supportedSubmitMethods: []"))
    }

    @Test
    fun `telegraf export is read and changed with the config scope, never its secrets`() {
        val get = call("GET", "/api/v1/export/telegraf")
        assertEquals(200, get.status)
        val doc = json(get)
        assertEquals("afterTrip", doc["mode"]!!.jsonPrimitive.content)
        assertFalse(get.body.toString(Charsets.UTF_8).contains("password"))

        val patched = call("PATCH", "/api/v1/export/telegraf", """{"enabled":true,"url":"http://10.0.0.2:8186/api/v2/write","mode":"live"}""")
        assertEquals(200, patched.status, patched.body.toString(Charsets.UTF_8))
        assertEquals("live", backend.view.mode)

        assertEquals(403, call("PATCH", "/api/v1/export/telegraf", """{"token":"x"}""").status)
        // every reading is exported: the old rate field is refused with a clear message
        val rate = call("PATCH", "/api/v1/export/telegraf", """{"pointsPerSecond":2}""")
        assertEquals(400, rate.status)
        assertTrue(rate.body.toString(Charsets.UTF_8).contains("every reading"))
        assertEquals(400, call("PATCH", "/api/v1/export/telegraf", """{"url":"ftp://x"}""").status)
        assertEquals(400, call("PATCH", "/api/v1/export/telegraf", """{"bucket":"x"}""").status)
        assertEquals(200, call("POST", "/api/v1/export/telegraf/test").status)
        assertEquals(200, call("POST", "/api/v1/export/telegraf/send").status)
        assertEquals(1, backend.sent)
        assertEquals(401, call("GET", "/api/v1/export/telegraf", auth = false).status)
    }

    @Test
    fun `telegraf routes need the config scope`() {
        val readOnly = ApiService(backend, { ApiAccess(token, setOf(Scope.Read)) })
        val r = readOnly.handle(HttpRequest("GET", "/api/v1/export/telegraf", emptyMap(), mapOf("authorization" to "Bearer $token"), ByteArray(0), InetAddress.getLoopbackAddress()))
        assertEquals(403, r.status)
    }

    @Test
    fun `backends without the export answer 501`() {
        val plain = ApiService(FakeApiBackend(), { ApiAccess(token, setOf(Scope.Config)) })
        val r = plain.handle(HttpRequest("GET", "/api/v1/export/telegraf", emptyMap(), mapOf("authorization" to "Bearer $token"), ByteArray(0), InetAddress.getLoopbackAddress()))
        assertEquals(501, r.status)
    }
}
