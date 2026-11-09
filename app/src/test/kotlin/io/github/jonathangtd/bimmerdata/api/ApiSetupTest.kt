/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpRequest
import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.files.EcuFileStore
import io.github.jonathangtd.bimmerdata.graph.GraphAxes
import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress

/** The setup routes (ECU files, permissions, Bluetooth, settings pages, Telegraf, profile import, graphs) through [ApiService.handle]. */
class ApiSetupTest {
    @TempDir lateinit var tmp: File

    private val token = ApiTokens.generate()
    private var scopes = setOf(Scope.Read, Scope.Layout, Scope.Config)

    private class Guard : CarWriteGuard {
        val asked = ArrayList<CarWrite>()
        var answer = CarWriteDecision.Approved
        override fun authorize(request: CarWrite, timeoutMs: Long): CarWriteDecision {
            asked += request
            return answer
        }
    }

    /** FakeApiBackend plus a Telegraf export. */
    private class Backend(val inner: FakeApiBackend = FakeApiBackend()) : ApiBackend by inner {
        var view = TelegrafView(true, "http://10.0.0.2:8186/api/v2/write", "token", "", false, "", "afterTrip",
            TelegrafStateView("idle", 12, 1200, 0, 40, false, 5_000_000_000, 100_000_000, 0, rejectedBatches = 2))
        var secret: String? = null
        var cleared = 0
        var rejected: ByteArray? = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0)

        override fun telegraf() = view
        override fun setTelegrafAuth(patch: TelegrafAuthPatch): TelegrafView {
            patch.secret?.let { secret = it }
            view = view.copy(auth = patch.auth, username = patch.username ?: view.username, secretSet = !secret.isNullOrEmpty())
            return view
        }
        override fun clearTelegrafQueue() { cleared++; view = view.copy(state = view.state.copy(queuedPoints = 0)) }
        override fun retryTelegrafRejected(): Int = view.state.rejectedBatches.also { view = view.copy(state = view.state.copy(rejectedBatches = 0, rejectedPoints = 0)) }
        override fun deleteTelegrafRejected() { rejected = null; view = view.copy(state = view.state.copy(rejectedBatches = 0, rejectedPoints = 0)) }
        override fun openTelegrafRejected(): InputStream? = rejected?.let(::ByteArrayInputStream)
    }

    private val backend = Backend()
    private val fake get() = backend.inner
    private val guard = Guard()
    private val service = ApiService(backend, { ApiAccess(token, scopes) }, guard = guard)

    @AfterEach
    fun close() = fake.close()

    private fun call(method: String, path: String, body: String = "", query: Map<String, String> = emptyMap(), raw: ByteArray? = null): HttpResponse.Bytes {
        val bytes = raw ?: body.toByteArray()
        val r = service.handle(HttpRequest(method, path, query.mapValues { listOf(it.value) }, mapOf("authorization" to "Bearer $token"), bytes, InetAddress.getLoopbackAddress()))
        return r as HttpResponse.Bytes
    }

    private val HttpResponse.Bytes.text get() = body.toString(Charsets.UTF_8)
    private fun HttpResponse.Bytes.json(): JsonObject = ApiJson.parseToJsonElement(text).jsonObject
    private val HttpResponse.Bytes.code get() = json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject

    private fun ecuStore(): EcuFileStore = EcuFileStore(File(tmp, "files").apply { mkdirs() }, File(tmp, "import"), freeSpace = { 10_000_000_000 }, reserveBytes = 1000)
        .also { fake.ecuStore = it }

    // ---------------------------------------------------------------- permissions

    @Test
    fun `permissions report says what is missing and what blocks`() {
        val r = call("GET", "/api/v1/permissions")
        assertEquals(200, r.status, r.text)
        val j = r.json()
        assertEquals(3, j["permissions"]!!.jsonArray.size)
        assertEquals(listOf("bluetoothConnect", "locationOn"), j["missing"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("bluetoothConnect"), j["blocking"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(j["permissions"]!!.jsonArray[0].jsonObject.str("howToGrant").isNotBlank())
    }

    // ---------------------------------------------------------------- ECU files

    @Test
    fun `ecu files - upload, list, delete and import from the device folder`() {
        assertEquals(501, call("GET", "/api/v1/ecu-files").status, "no store: not available")
        val store = ecuStore()
        val empty = call("GET", "/api/v1/ecu-files").json()
        assertEquals(0, empty["fileCount"]!!.jsonPrimitive.int)
        assertEquals("idle", empty.obj("deviceImport").str("phase"))

        val put = call("PUT", "/api/v1/ecu-files/MSV70.PRG", raw = "engine".toByteArray())
        assertEquals(201, put.status, put.text)
        assertEquals("msv70.prg", put.json().obj("file").str("name"))
        assertEquals(200, call("PUT", "/api/v1/ecu-files/msv70.prg", raw = "engine 2".toByteArray()).status, "replacing answers 200")
        assertEquals("invalid_name", call("PUT", "/api/v1/ecu-files/x.exe", raw = "x".toByteArray()).code)
        assertEquals(400, call("PUT", "/api/v1/ecu-files/x.prg", raw = ByteArray(0)).status, "empty body")

        val list = call("GET", "/api/v1/ecu-files", query = mapOf("files" to "true")).json()
        assertEquals(listOf("msv70.prg"), list["files"]!!.jsonArray.map { it.jsonObject.str("name") })

        // the Ecu folder pushed to the phone, one level down
        File(tmp, "import/Ecu").mkdirs()
        File(tmp, "import/Ecu/EGS.PRG").writeText("gearbox")
        File(tmp, "import/Ecu/D_0032.GRP").writeText("group")
        File(tmp, "import/Ecu/info.txt").writeText("notes")
        assertEquals(2, call("GET", "/api/v1/ecu-files").json()["importDirFiles"]!!.jsonPrimitive.int)
        val started = call("POST", "/api/v1/ecu-files/import-from-device", """{"mode":"merge"}""")
        assertEquals(202, started.status, started.text)
        assertTrue(started.json()["started"]!!.jsonPrimitive.boolean)
        // runs on its own thread
        val deadline = System.currentTimeMillis() + 5_000
        while (store.deviceImport.phase == io.github.jonathangtd.bimmerdata.files.ImportPhase.Running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val after = call("GET", "/api/v1/ecu-files").json()
        assertEquals("done", after.obj("deviceImport").str("phase"))
        assertEquals(1, after.obj("deviceImport")["skippedCount"]!!.jsonPrimitive.int)
        assertEquals(3, after["fileCount"]!!.jsonPrimitive.int)
        assertEquals(400, call("POST", "/api/v1/ecu-files/import-from-device", """{"mode":"zip"}""").status)
        assertFalse(call("DELETE", "/api/v1/ecu-files/import-from-device").json()["cancelled"]!!.jsonPrimitive.boolean)

        assertEquals(204, call("DELETE", "/api/v1/ecu-files/egs.prg").status)
        assertEquals("ecu_file_not_found", call("DELETE", "/api/v1/ecu-files/egs.prg").code)
        assertEquals(2, call("DELETE", "/api/v1/ecu-files").json()["deleted"]!!.jsonPrimitive.int)
        assertTrue(store.files().isEmpty())

        File(tmp, "import").deleteRecursively()
        assertEquals("no_ecu_files", call("POST", "/api/v1/ecu-files/import-from-device").code)
    }

    @Test
    fun `changing ecu files needs the config scope`() {
        ecuStore()
        scopes = setOf(Scope.Read)
        assertEquals(200, call("GET", "/api/v1/ecu-files").status)
        assertEquals(403, call("PUT", "/api/v1/ecu-files/a.prg", raw = "x".toByteArray()).status)
        assertEquals(403, call("POST", "/api/v1/ecu-files/import-from-device").status)
        assertEquals(403, call("DELETE", "/api/v1/ecu-files").status)
    }

    @Test
    fun `only the upload route streams its body`() {
        assertEquals(EcuFileStore.MAX_FILE_BYTES, service.streamedBodyLimit("PUT", "/api/v1/ecu-files/a.prg"))
        assertEquals(null, service.streamedBodyLimit("POST", "/api/v1/ecu-files/import-from-device"))
        assertEquals(null, service.streamedBodyLimit("PUT", "/api/v1/dashboard"))
        assertEquals(null, service.streamedBodyLimit("DELETE", "/api/v1/ecu-files/a.prg"))
    }

    // ---------------------------------------------------------------- Bluetooth

    @Test
    fun `bluetooth - devices, choose, forget, permission missing`() {
        val devices = call("GET", "/api/v1/bluetooth/devices").json()
        assertEquals(2, devices["devices"]!!.jsonArray.size)
        val chosen = call("PUT", "/api/v1/bluetooth/adapter", """{"address":"00:11:22:33:44:55","connectOnStart":true}""")
        assertEquals(200, chosen.status, chosen.text)
        assertEquals("Deep OBD BMW", chosen.json().obj("adapter").str("name"))
        assertTrue(chosen.json()["connectOnStart"]!!.jsonPrimitive.boolean)
        assertTrue(call("GET", "/api/v1/bluetooth/devices").json()["devices"]!!.jsonArray[0].jsonObject["selected"]!!.jsonPrimitive.boolean)
        assertEquals("device_not_paired", call("PUT", "/api/v1/bluetooth/adapter", """{"address":"01:02:03:04:05:06"}""").code)
        assertEquals("low_energy_only", call("PUT", "/api/v1/bluetooth/adapter", """{"address":"aa:bb:cc:dd:ee:ff"}""").code)
        assertEquals(400, call("PUT", "/api/v1/bluetooth/adapter", """{"address":"nope"}""").status)
        assertEquals(400, call("PUT", "/api/v1/bluetooth/adapter", "{}").status)
        assertFalse(call("DELETE", "/api/v1/bluetooth/adapter").json().containsKey("adapter"))

        fake.bt = fake.bt.copy(permission = "missing")
        assertEquals("bluetooth_permission_missing", call("GET", "/api/v1/bluetooth/devices").code)
        assertEquals("bluetooth_permission_missing", call("PUT", "/api/v1/bluetooth/adapter", """{"address":"00:11:22:33:44:55"}""").code)
        assertEquals("missing", call("GET", "/api/v1/bluetooth").json().str("permission"))
    }

    @Test
    fun `adapter can mode is written only after the phone confirmed it`() {
        assertEquals("not_connected_bluetooth", call("POST", "/api/v1/bluetooth/adapter/read").code)
        assertEquals("not_connected_bluetooth", call("PUT", "/api/v1/bluetooth/adapter/can-mode", """{"mode":"kLineOnly"}""").code)
        assertTrue(guard.asked.isEmpty())

        fake.bt = fake.bt.copy(connected = true)
        assertEquals(12.4, call("POST", "/api/v1/bluetooth/adapter/read").json()["voltage"]!!.jsonPrimitive.content.toDouble())
        assertEquals(400, call("PUT", "/api/v1/bluetooth/adapter/can-mode", """{"mode":"can100"}""").status)

        guard.answer = CarWriteDecision.Rejected
        assertEquals("refused_on_phone", call("PUT", "/api/v1/bluetooth/adapter/can-mode", """{"mode":"kLineOnly"}""").code)
        guard.answer = CarWriteDecision.Timeout
        assertEquals(408, call("PUT", "/api/v1/bluetooth/adapter/can-mode", """{"mode":"kLineOnly"}""").status)
        assertTrue(fake.canModeWrites.isEmpty(), "nothing written without approval")

        guard.answer = CarWriteDecision.Approved
        val r = call("PUT", "/api/v1/bluetooth/adapter/can-mode", """{"mode":"kLineOnly"}""")
        assertEquals(200, r.status, r.text)
        assertEquals("kLineOnly", r.json().str("canMode"))
        assertEquals(listOf(0x00), fake.canModeWrites)
        assertEquals(CarWrite.AdapterCanMode(0x00, "kLineOnly"), guard.asked.last())
    }

    @Test
    fun `the default guard refuses adapter writes`() {
        val s = ApiService(fake, { ApiAccess(token, scopes) })
        fake.bt = fake.bt.copy(connected = true)
        val r = s.handle(HttpRequest("PUT", "/api/v1/bluetooth/adapter/can-mode", emptyMap(), mapOf("authorization" to "Bearer $token"), """{"mode":"auto"}""".toByteArray(), InetAddress.getLoopbackAddress()))
        assertEquals(503, r.status)
        assertTrue(fake.canModeWrites.isEmpty())
    }

    // ---------------------------------------------------------------- settings pages

    @Test
    fun `gps, recording and translation settings`() {
        val gps = call("PATCH", "/api/v1/settings/gps", """{"enabled":true,"intervalMs":2000}""")
        assertEquals(200, gps.status, gps.text)
        assertEquals("noPermission", gps.json().str("state"))
        assertEquals(400, call("PATCH", "/api/v1/settings/gps", """{"intervalMs":1500}""").status)
        assertEquals(400, call("PATCH", "/api/v1/settings/gps", """{"accuracy":1}""").status)

        val rec = call("PATCH", "/api/v1/settings/recording", """{"rowIntervalMs":200,"filePrefix":"my-car_1","useDefaultFolder":true}""").json()
        assertEquals(200, rec["rowIntervalMs"]!!.jsonPrimitive.long)
        assertEquals("my-car_1", rec.str("filePrefix"))
        assertFalse(rec["customFolder"]!!.jsonPrimitive.boolean)
        assertEquals("phone_only", call("PATCH", "/api/v1/settings/recording", """{"folder":"Download"}""").code)
        assertEquals(400, call("PATCH", "/api/v1/settings/recording", """{"filePrefix":"../x"}""").status)
        assertEquals(400, call("PATCH", "/api/v1/settings/recording", """{"rowIntervalMs":300}""").status)

        val tr = call("PATCH", "/api/v1/settings/translations", """{"language":"FR","token":"secret-1","selection":"chosen","chosen":["MSV70.prg","egs"]}""")
        assertEquals(200, tr.status, tr.text)
        assertEquals("fr", tr.json().str("language"))
        assertTrue(tr.json()["tokenSet"]!!.jsonPrimitive.boolean)
        assertFalse(tr.text.contains("secret-1"), "the token is never returned")
        assertEquals(listOf("msv70", "egs"), tr.json()["chosen"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(call("PATCH", "/api/v1/settings/translations", """{"language":"de"}""").json()["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(409, call("PATCH", "/api/v1/settings/translations", """{"source":"folder"}""").status, "no folder picked on the phone")
        assertEquals(403, call("PATCH", "/api/v1/settings/translations", """{"folderUri":"content://x"}""").status)
        assertEquals(400, call("PATCH", "/api/v1/settings/translations", """{"baseUrl":"ftp://x"}""").status)
        assertEquals(400, call("PATCH", "/api/v1/settings/translations", """{"token":"a\nb"}""").status)
        val now = call("POST", "/api/v1/settings/translations/update")
        assertEquals(202, now.status)
        assertEquals(1, fake.translationUpdates)

        scopes = setOf(Scope.Read)
        assertEquals(200, call("GET", "/api/v1/settings/gps").status)
        assertEquals(200, call("GET", "/api/v1/settings/recording").status)
        assertEquals(200, call("GET", "/api/v1/settings/translations").status)
        assertEquals(403, call("PATCH", "/api/v1/settings/gps", """{"enabled":false}""").status)
    }

    // ---------------------------------------------------------------- Telegraf

    @Test
    fun `telegraf secrets are write-only and the queue can be managed`() {
        assertEquals("use_auth_route", call("PATCH", "/api/v1/export/telegraf", """{"token":"x"}""").code, "secrets never go through the PATCH")
        val r = call("PUT", "/api/v1/export/telegraf/auth", """{"auth":"token","token":"tk-123"}""")
        assertEquals(200, r.status, r.text)
        assertTrue(r.json()["secretSet"]!!.jsonPrimitive.boolean)
        assertFalse(r.text.contains("tk-123"))
        assertEquals("tk-123", backend.secret)
        val basic = call("PUT", "/api/v1/export/telegraf/auth", """{"auth":"basic","username":"car","password":"pw"}""").json()
        assertEquals("basic", basic.str("auth"))
        assertEquals("car", basic.str("username"))
        assertEquals(400, call("PUT", "/api/v1/export/telegraf/auth", """{"auth":"basic","token":"x"}""").status)
        assertEquals(400, call("PUT", "/api/v1/export/telegraf/auth", """{"auth":"none","password":"x"}""").status)
        assertEquals(400, call("PUT", "/api/v1/export/telegraf/auth", """{"auth":"basic","username":"a:b"}""").status)
        assertEquals(400, call("PUT", "/api/v1/export/telegraf/auth", """{"token":"x"}""").status, "auth is required")
        assertEquals("none", call("PUT", "/api/v1/export/telegraf/auth", """{"auth":"none"}""").json().str("auth"))

        val gz = call("GET", "/api/v1/export/telegraf/rejected")
        assertEquals(200, gz.status)
        assertEquals("application/gzip", gz.contentType)
        assertEquals(2, call("POST", "/api/v1/export/telegraf/rejected/retry").json()["requeued"]!!.jsonPrimitive.int)
        assertEquals(200, call("DELETE", "/api/v1/export/telegraf/rejected").status)
        assertEquals("no_rejected", call("GET", "/api/v1/export/telegraf/rejected").code)
        assertEquals(0, call("DELETE", "/api/v1/export/telegraf/queue").json().obj("state")["queuedPoints"]!!.jsonPrimitive.int)
        assertEquals(1, backend.cleared)
    }

    // ---------------------------------------------------------------- profiles and graphs

    @Test
    fun `profile file import keeps the graphs and makes no car link`() {
        val export = """{"kind":"bimmerdata-profile","formatVersion":1,"name":"My car","series":"<series>","ecus":["MSV70"],
            "dashboard":{"phonePages":[{"name":"Engine","columns":2,"rows":3,"cards":[{"type":"Dial","measurementIds":["msv70/0x4402"]}]}],"carPages":[]},
            "graphs":[{"id":"g1","name":"Temps","series":[{"measurementId":"msv70/0x4402","color":3},{"measurementId":"zzz/0x1"}],"windowSeconds":300,"axes":"Bands"}]}"""
        val r = call("POST", "/api/v1/profiles/import", export, query = mapOf("activate" to "false"))
        assertEquals(201, r.status, r.text)
        val p = r.json().obj("profile")
        assertEquals("My car", p.str("name"))
        assertEquals(1, p["graphs"]!!.jsonArray.size)
        assertEquals(1, r.json()["warnings"]!!.jsonArray.size, "zzz/0x1 is unknown")
        assertEquals(null, fake.profileSet.activeId, "activate=false")
        assertEquals(ProfileVehicle("<series>", null, null), fake.profileSet.profiles.single().vehicle)
        // the same file again: a second profile with another name
        assertEquals("My car (2)", call("POST", "/api/v1/profiles/import", export).json().obj("profile").str("name"))
        assertEquals(fake.profileSet.profiles[1].id, fake.profileSet.activeId)
        assertEquals("invalid_profile_file", call("POST", "/api/v1/profiles/import", """{"kind":"other"}""").code)
        assertEquals("invalid_profile_file", call("POST", "/api/v1/profiles/import", "not json").code)
    }

    @Test
    fun `graphs of a profile - create, read, replace, delete, never touching the pages`() {
        val created = call("POST", "/api/v1/profiles", """{"name":"Car"}""").json()
        val id = created.str("id")
        call("PUT", "/api/v1/profiles/$id", """{"dashboard":{"phonePages":[{"name":"Engine","columns":2,"rows":3}],"carPages":[]}}""")
        val pagesBefore = fake.profileSet.profile(id)!!.dashboard

        val g = call("POST", "/api/v1/profiles/$id/graphs", """{"name":"Temperatures","measurementIds":["msv70/0x4402","msv70/0x4300"],"windowSeconds":300}""")
        assertEquals(201, g.status, g.text)
        val graph = g.json().obj("graph")
        val gid = graph.str("id")
        assertEquals(listOf(0, 1), graph["series"]!!.jsonArray.map { it.jsonObject["color"]!!.jsonPrimitive.int }, "free colors in order")
        assertEquals("Overlay", graph.str("axes"))

        val second = call("POST", "/api/v1/profiles/$id/graphs", """{"name":"Lambda","series":[{"measurementId":"msv70/0x4807","color":5,"hidden":true}],"position":0}""")
        assertEquals(201, second.status, second.text)
        val list = call("GET", "/api/v1/profiles/$id/graphs").json()
        assertEquals(listOf("Lambda", "Temperatures"), list["graphs"]!!.jsonArray.map { it.jsonObject.str("name") })
        assertEquals(8, list.obj("limits")["palette"]!!.jsonArray.size)

        val put = call("PUT", "/api/v1/profiles/$id/graphs/$gid", """{"name":"Temps","series":[{"measurementId":"msv70/0x4300","color":2}],"axes":"bands"}""")
        assertEquals(200, put.status, put.text)
        val stored = fake.profileSet.profile(id)!!.graphs.first { it.id == gid }
        assertEquals("Temps", stored.name)
        assertEquals(GraphAxes.Bands, stored.axes)
        assertEquals(null, stored.windowSeconds, "replaced: absent fields take their default")
        assertEquals(gid, call("GET", "/api/v1/profiles/$id/graphs/$gid").json().str("id"))

        // strict validation, nothing written
        assertEquals("invalid_graph", call("POST", "/api/v1/profiles/$id/graphs", """{"name":"x","series":[{"measurementId":"a/1","color":9}]}""").code)
        assertEquals("invalid_graph", call("POST", "/api/v1/profiles/$id/graphs", """{"name":"x","measurementIds":["a/1","a/1"]}""").code)
        assertEquals("invalid_graph", call("POST", "/api/v1/profiles/$id/graphs", """{"name":"x","windowSeconds":2}""").code)
        assertEquals("invalid_graph", call("POST", "/api/v1/profiles/$id/graphs", """{"name":"${"n".repeat(30)}"}""").code)
        assertEquals("invalid_graph", call("POST", "/api/v1/profiles/$id/graphs", """{"name":"x","measurementIds":["a/1","a/2","a/3","a/4","a/5","a/6","a/7","a/8","a/9"]}""").code)
        assertEquals(400, call("POST", "/api/v1/profiles/$id/graphs", """{"name":"x","measurementIds":[],"series":[]}""").status)
        assertEquals(400, call("PUT", "/api/v1/profiles/$id/graphs/$gid", """{"id":"other","name":"x"}""").status)
        assertEquals("graph_not_found", call("PUT", "/api/v1/profiles/$id/graphs/nope", """{"name":"x"}""").code)
        assertEquals("profile_not_found", call("GET", "/api/v1/profiles/nope/graphs").code)
        assertEquals(2, fake.profileSet.profile(id)!!.graphs.size)

        assertEquals(204, call("DELETE", "/api/v1/profiles/$id/graphs/$gid").status)
        assertEquals(404, call("DELETE", "/api/v1/profiles/$id/graphs/$gid").status)
        assertEquals(pagesBefore, fake.profileSet.profile(id)!!.dashboard, "graph routes never change the pages")
        assertTrue(call("GET", "/api/v1/profiles/$id").json()["graphs"]!!.jsonArray.size == 1)
    }

    // ---------------------------------------------------------------- documentation

    @Test
    fun `every new route is in the openapi document`() {
        val doc = service.handle(HttpRequest("GET", "/api/v1/openapi.json", emptyMap(), emptyMap(), ByteArray(0), InetAddress.getLoopbackAddress())) as HttpResponse.Bytes
        val paths = ApiJson.parseToJsonElement(doc.body.toString(Charsets.UTF_8)).jsonObject.obj("paths")
        for (p in listOf(
            "/api/v1/permissions", "/api/v1/ecu-files", "/api/v1/ecu-files/{name}", "/api/v1/ecu-files/import-from-device",
            "/api/v1/bluetooth", "/api/v1/bluetooth/devices", "/api/v1/bluetooth/adapter", "/api/v1/bluetooth/adapter/read", "/api/v1/bluetooth/adapter/can-mode",
            "/api/v1/settings/gps", "/api/v1/settings/recording", "/api/v1/settings/translations", "/api/v1/settings/translations/update",
            "/api/v1/export/telegraf/auth", "/api/v1/export/telegraf/queue", "/api/v1/export/telegraf/rejected", "/api/v1/export/telegraf/rejected/retry",
            "/api/v1/profiles/import", "/api/v1/profiles/{profile}/graphs", "/api/v1/profiles/{profile}/graphs/{graph}",
        )) assertTrue(p in paths, p)
        val upload = paths.obj("/api/v1/ecu-files/{name}").obj("put").obj("requestBody").obj("content")
        assertTrue("application/octet-stream" in upload)
        // every $ref points to a schema that exists
        val text = doc.body.toString(Charsets.UTF_8)
        val schemas = ApiJson.parseToJsonElement(text).jsonObject.obj("components").obj("schemas").keys
        val refs = Regex("#/components/schemas/([A-Za-z]+)").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet<String>(), refs - schemas)
        val ids = service.routes.map { it.operationId }
        assertEquals(ids.size, ids.toSet().size, "operation ids are unique")
    }
}
