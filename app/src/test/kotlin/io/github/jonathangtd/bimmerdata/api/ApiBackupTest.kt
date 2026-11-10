/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpRequest
import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.backup.BackupInfo
import io.github.jonathangtd.bimmerdata.backup.BackupRestorer
import io.github.jonathangtd.bimmerdata.backup.FakeBackupParts
import io.github.jonathangtd.bimmerdata.backup.RestoreSummary
import io.github.jonathangtd.bimmerdata.settings.ApiConfig
import io.github.jonathangtd.bimmerdata.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** GET /api/v1/backup and POST /api/v1/backup/restore through [ApiService.handle]. */
class ApiBackupTest {
    private val token = ApiTokens.generate()
    private val fake = FakeApiBackend()
    private var parts = FakeBackupParts.populated()

    /** The fake backend with a backup over [parts] (includeApi false, like the Android backend). */
    private val backend = object : ApiBackend by fake {
        override fun backup() = BackupRestorer(parts).export(BackupInfo("test", 1, 1_794_300_000_000))
        override fun restoreBackup(text: String, dryRun: Boolean): RestoreSummary = BackupRestorer(parts).restore(text, includeApi = false, dryRun = dryRun)
    }
    private var scopes = setOf(Scope.Read, Scope.Config)
    private val service = ApiService(backend, { ApiAccess(token, scopes) })

    @AfterEach
    fun stop() = fake.close()

    private class Resp(val status: Int, val text: String, val headers: Map<String, String>) {
        fun json(): JsonObject = Json.parseToJsonElement(text).jsonObject
        val code get() = json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        override fun toString() = "$status $text"
    }

    private fun call(method: String, path: String, body: String? = null, query: Map<String, String> = emptyMap()): Resp {
        val r = service.handle(HttpRequest(
            method, path, query.mapValues { listOf(it.value) }, mapOf("authorization" to "Bearer $token"), body?.toByteArray() ?: ByteArray(0), java.net.InetAddress.getLoopbackAddress(),
        ))
        val bytes = (r as HttpResponse.Bytes).body
        return Resp(r.status, String(bytes, Charsets.UTF_8), r.headers)
    }

    @Test
    fun `download then restore onto an empty phone`() {
        val file = call("GET", "/api/v1/backup")
        assertEquals(200, file.status, file.text)
        assertTrue(file.headers["Content-Disposition"]!!.startsWith("attachment; filename=\"bimmerdata-backup-"))
        assertEquals("bimmerdata-backup", file.json()["format"]!!.jsonPrimitive.content)
        assertFalse(FakeBackupParts.API_TOKEN in file.text)

        val original = parts
        val mine = ApiConfig(enabled = true, port = 8765, token = token, scopes = scopes)
        parts = FakeBackupParts(settings = Settings(api = mine))

        assertEquals("confirm_required", call("POST", "/api/v1/backup/restore", file.text).code)
        assertEquals(0, parts.garage.vehicles.size, "nothing without confirmation")

        val dry = call("POST", "/api/v1/backup/restore", file.text, mapOf("dryRun" to "true"))
        assertEquals(200, dry.status, dry.text)
        assertTrue(dry.json()["dryRun"]!!.jsonPrimitive.boolean)
        assertEquals(0, parts.garage.vehicles.size)

        val done = call("POST", "/api/v1/backup/restore", file.text, mapOf("confirm" to "true"))
        assertEquals(200, done.status, done.text)
        assertEquals(2, done.json()["cars"]!!.jsonPrimitive.int)
        assertEquals(2, done.json()["profiles"]!!.jsonPrimitive.int)
        assertFalse(done.json()["apiSettingsRestored"]!!.jsonPrimitive.boolean)
        assertEquals(original.garage, parts.garage)
        assertEquals(original.profiles, parts.profiles)
        assertEquals(mine, parts.settings.api, "Local API settings are phone only")
    }

    @Test
    fun `confirm in the body works too, errors are mapped`() {
        val file = call("GET", "/api/v1/backup").text
        val withConfirm = "{\"confirm\": true," + file.trim().removePrefix("{")
        assertEquals(200, call("POST", "/api/v1/backup/restore", withConfirm).status)

        assertEquals(400, call("POST", "/api/v1/backup/restore", "nope", mapOf("confirm" to "true")).status)
        assertEquals("not_a_backup", call("POST", "/api/v1/backup/restore", """{"kind":"bimmerdata-profile","name":"x"}""", mapOf("confirm" to "true")).code)
        val newer = call("POST", "/api/v1/backup/restore", """{"format":"bimmerdata-backup","version":9,"garage":{},"profiles":{}}""", mapOf("confirm" to "true"))
        assertEquals(422, newer.status)
        assertEquals("newer_version", newer.code)
        assertEquals(400, call("POST", "/api/v1/backup/restore", "", mapOf("confirm" to "true")).status)

        parts.busy = true
        assertEquals(409, call("POST", "/api/v1/backup/restore", file, mapOf("confirm" to "true")).status)
        parts.busy = false
        parts.failOn = "profiles"
        val failed = call("POST", "/api/v1/backup/restore", file, mapOf("confirm" to "true"))
        assertEquals(500, failed.status)
        assertEquals("restore_failed", failed.code)
    }

    @Test
    fun `scope config is needed`() {
        scopes = setOf(Scope.Read, Scope.Layout)
        assertEquals(403, call("GET", "/api/v1/backup").status)
        assertEquals(403, call("POST", "/api/v1/backup/restore", "{}", mapOf("confirm" to "true")).status)
    }

    @Test
    fun `documented in openapi`() {
        val doc = call("GET", "/api/v1/openapi.json")
        assertEquals(200, doc.status)
        assertTrue(doc.text.contains("/api/v1/backup/restore"))
        assertTrue(doc.text.contains("\"RestoreSummary\""))
    }
}
