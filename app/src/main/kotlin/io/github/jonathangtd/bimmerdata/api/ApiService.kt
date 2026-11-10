/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpRequest
import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import io.github.jonathangtd.bimmerdata.cars.Cars
import io.github.jonathangtd.bimmerdata.cars.EcuUsage
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileExport
import io.github.jonathangtd.bimmerdata.profiles.ProfileJson
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import io.github.jonathangtd.bimmerdata.dashboard.GridLimits
import io.github.jonathangtd.bimmerdata.dashboard.DashboardReset
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.dashboard.Target
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The REST API: route table, authentication, scopes, rate limiting and handlers, on top of an
 * [ApiBackend]. Transport-agnostic ([handle] maps a request to a response) and pure JVM.
 */
class ApiService(
    private val backend: ApiBackend,
    private val access: () -> ApiAccess,
    extensions: List<ApiExtension> = emptyList(),
    /** Files of the embedded Swagger UI ([ApiDocs.ASSETS]); null = not available. */
    private val docsAssets: (String) -> ByteArray? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    /** Asks the user on the phone before a write to the Bluetooth adapter (CAN mode); default: refused. */
    private val guard: CarWriteGuard = CarWriteGuard.DenyAll,
    private val confirmTimeoutMs: Long = DiagApi.DEFAULT_CONFIRM_TIMEOUT_MS,
) {
    val history = SampleHistory()
    val reads = ApiReads(backend::setApiMeasurements, clock)
    private val limiter = RateLimiter(clock = clock)
    private val layoutLock = Any()
    private val streams = AtomicInteger()
    private val watches = ArrayList<ApiReads.Lease>()
    /** sgbd -> (time, ECU folder stamp, measurements by id). */
    private val catalogCache = ConcurrentHashMap<String, Triple<Long, Long, Map<String, Measurement>>>()
    @Volatile var stopped = false

    val routes: List<Route> = buildRoutes() +
        ProfileGraphRoutes(backend, clock, ::meta).routes() +
        CarRoutes(backend, clock).routes() +
        BackupRoutes(backend, clock).routes() +
        SetupRoutes(backend, guard, confirmTimeoutMs, clock, ::meta, catalogCache::clear).routes() +
        extensions.flatMap { ext ->
        ext.routes(ExtensionContext(backend, ext.guard)).onEach {
            require(it.access == RouteAccess.Needs(Scope.Diag)) { "Extension route ${it.method} ${it.path} must need the diag scope" }
            require(it.path.startsWith("/api/v1/diag/")) { "Extension routes live under /api/v1/diag/" }
        }
    }

    /** Called about every 100 ms by the server: feeds the history and expires leases. */
    fun tick() {
        history.record(backend.samples.value, clock())
        reads.prune()
    }

    fun handle(req: HttpRequest): HttpResponse {
        try {
            val wait = limiter.check(req.remote)
            if (wait > 0) throw ApiException(429, "rate_limited", "Too many requests; retry in $wait s", headers = mapOf("Retry-After" to wait.toString()))
            val segments = req.path.trim('/').split('/')
            if (segments.size < 2 || segments[0] != "api" || segments[1] != "v1") {
                throw notFound("not_found", "Unknown path ${req.path}; the API lives under /api/v1 (spec: GET /api/v1/openapi.json)")
            }
            val candidates = routes.mapNotNull { r -> r.match(segments)?.let { r to it } }
            if (candidates.isEmpty()) throw notFound("not_found", "Unknown path ${req.path}; see GET /api/v1/openapi.json")
            val method = if (req.method == "HEAD") "GET" else req.method
            val (route, params) = candidates.firstOrNull { it.first.method == method }
                ?: throw ApiException(405, "method_not_allowed", "${req.method} is not allowed on ${req.path}; use ${candidates.joinToString { it.first.method }}",
                    headers = mapOf("Allow" to candidates.joinToString(", ") { it.first.method }))
            val acc = authorize(req, route)
            return route.handler(Call(req, params, acc))
        } catch (e: ApiException) {
            return errorResponse(e)
        } catch (e: IllegalArgumentException) {
            return errorResponse(badRequest(e.message ?: "Bad request"))
        }
    }

    /**
     * For the HTTP server: the largest body of [method] [path] when it is an upload route (the
     * body is then streamed to the handler), null for every other request (1 MB in memory).
     */
    fun streamedBodyLimit(method: String, path: String): Long? {
        val segments = path.trim('/').split('/')
        return routes.firstOrNull { it.upload != null && it.method == method && it.match(segments) != null }?.upload?.maxBytes
    }

    private fun authorize(req: HttpRequest, route: Route): ApiAccess? {
        if (route.access == RouteAccess.Public) return null
        val current = access()
        val header = req.header("authorization")
        val given = header?.let { h -> if (h.length > 7 && h.substring(0, 7).equals("Bearer ", ignoreCase = true)) h.substring(7).trim() else null }
        if (given == null || !ApiTokens.matches(current.token, given)) {
            if (header != null) limiter.authFailed(req.remote)
            throw ApiException(
                401, "unauthorized",
                if (header == null) "Missing token: send 'Authorization: Bearer <token>' (token in BimmerData › Settings › Local API)" else "Wrong token (it may have been regenerated on the phone)",
                headers = mapOf("WWW-Authenticate" to "Bearer realm=\"BimmerData\""),
            )
        }
        val needs = route.access
        if (needs is RouteAccess.Needs && needs.scope !in current.scopes) {
            throw ApiException(403, "insufficient_scope", "This operation needs the '${needs.scope.id}' scope; enable it on the phone in Settings › Local API (enabled: ${current.scopes.joinToString { it.id }.ifEmpty { "none" }})")
        }
        return current
    }

    fun releaseAll() {
        synchronized(watches) { watches.toList().forEach { it.close() }; watches.clear() }
    }

    // ================================================================ helpers

    private fun ok(element: JsonElement, status: Int = 200) = json(status, element)

    private fun catalog(sgbd: String): Map<String, Measurement> {
        val key = sgbd.lowercase()
        val now = clock()
        // a new ECU folder (import from the phone or the in-app import) makes the cache stale at once
        val stamp = backend.ecuFiles?.ecuDir?.lastModified() ?: 0L
        catalogCache[key]?.let { (at, folder, map) -> if (now - at < 60_000 && folder == stamp) return map }
        val map = runCatching { backend.measurements(key) }.getOrElse { emptyList() }.associateBy { it.id }
        catalogCache[key] = Triple(now, stamp, map)
        return map
    }

    private fun meta(id: String): Measurement? = catalog(id.substringBefore('/')).get(id)

    /** Measurements for [ids]; 404 listing the unknown ones. */
    private fun resolve(ids: List<String>): List<Measurement> {
        if (ids.isEmpty()) throw badRequest("'ids' must list at least one measurement id")
        if (ids.size > 64) throw badRequest("At most 64 ids per request (got ${ids.size}); the K-line reads roughly 10–40 values per second in total")
        val found = ids.distinct().map { it to meta(it) }
        val unknown = found.filter { it.second == null }.map { it.first }
        if (unknown.isNotEmpty()) {
            throw ApiException(404, "unknown_measurement", "Unknown measurement id(s): ${unknown.joinToString()}. Ids look like '<sgbd>/<id>'; list them with GET /api/v1/ecus/{sgbd}/measurements",
                JsonArray(unknown.map(::JsonPrimitive)))
        }
        return found.map { it.second!! }
    }

    private fun measurementJson(m: Measurement): JsonObject = buildJsonObject {
        put("id", m.id); put("sgbd", m.sgbd); put("label", m.label); put("unit", m.unit)
        m.min?.let { put("min", it) }
        m.max?.let { put("max", it) }
        put("source", when (val s = m.source) {
            is MeasurementSource.MeasurementTable -> buildJsonObject {
                put("kind", "table"); put("tableId", "0x" + s.id.toString(16).uppercase(Locale.ROOT).padStart(4, '0')); put("resultName", s.resultName)
            }
            is MeasurementSource.JobResult -> buildJsonObject {
                put("kind", "job"); put("job", s.job); put("args", s.args); put("resultName", s.resultName); put("set", s.set)
            }
            is MeasurementSource.Sensor -> buildJsonObject {
                put("kind", "sensor"); put("sensor", s.sensor); put("field", s.field)
            }
        })
    }

    private fun valueJson(id: String, sample: Sample?, now: Long): JsonObject = buildJsonObject {
        put("id", id)
        meta(id)?.let { put("label", it.label); put("unit", it.unit) }
        if (sample == null) {
            put("status", "no_data")
        } else {
            sample.value?.takeUnless { it.isNaN() || it.isInfinite() }?.let { put("value", it) }
            sample.text?.let { put("text", it) }
            put("timestampMs", sample.timestampMs)
            val age = (now - sample.timestampMs).coerceAtLeast(0)
            put("ageMs", age)
            put("status", if (age <= FRESH_MS) "fresh" else "stale")
        }
    }

    private fun seriesJson(id: String, points: List<SampleHistory.Point>): JsonObject = buildJsonObject {
        put("id", id)
        meta(id)?.let { put("label", it.label); put("unit", it.unit) }
        val nums = points.mapNotNull { it.value }
        put("stats", buildJsonObject {
            put("count", nums.size)
            if (nums.isNotEmpty()) {
                put("min", nums.min()); put("max", nums.max()); put("mean", nums.average()); put("last", nums.last())
            }
        })
        putJsonArray("points") {
            for (p in points) addJsonObject {
                put("t", p.timestampMs)
                p.value?.let { put("v", it) }
                p.text?.let { put("text", it) }
            }
        }
    }

    private fun statusJson(): JsonObject {
        val s = backend.status()
        return buildJsonObject {
            put("connection", ApiJson.encodeToJsonElement(s.connection))
            put("acquisition", ApiJson.encodeToJsonElement(s.acquisition))
            put("recording", ApiJson.encodeToJsonElement(RecordingInfo.of(backend.recording.value)))
            put("measurementsRead", JsonArray(s.measurementsRead.map(::JsonPrimitive)))
            put("apiReads", JsonArray(reads.active().map(::JsonPrimitive)))
            put("driving", s.driving)
            put("appVersion", backend.appVersion)
        }
    }

    private fun requireConnected() {
        val c = backend.status().connection
        if (c.state != "connected") {
            throw ApiException(409, "not_connected", "Not reading the car (connection is ${c.state}${c.message?.let { ": $it" } ?: ""}); POST /api/v1/connect first (scope config), e.g. {\"mode\":\"simulation\"}")
        }
    }

    private fun dashboardJson(d: Dashboard) = ApiJson.encodeToJsonElement(Dashboard.serializer(), d)
    private fun pageJson(p: Page, index: Int? = null): JsonObject {
        val base = ApiJson.encodeToJsonElement(Page.serializer(), p).jsonObject
        return JsonObject(buildMap<String, JsonElement> {
            index?.let { put("index", JsonPrimitive(it)) }
            putAll(base)
            put("freeCells", JsonPrimitive(LayoutOps.freeCells(p)))
        })
    }
    private fun cardJson(c: Card) = ApiJson.encodeToJsonElement(Card.serializer(), c)

    // ---- profiles ----

    private fun profileJson(p: Profile) = ApiJson.encodeToJsonElement(Profile.serializer(), p)

    private fun profileSummaryJson(p: Profile, active: Boolean): JsonObject = buildJsonObject {
        put("id", p.id)
        put("name", p.name)
        put("carId", p.carId)
        put("active", active)
        put("vehicle", buildJsonObject {
            p.vehicle.series?.let { put("series", it) }
            p.vehicle.model?.let { put("model", it) }
            put("label", p.vehicle.label)
        })
        put("ecus", JsonArray(p.ecus.map(::JsonPrimitive)))
        put("phonePages", p.dashboard.phonePages.size)
        put("carPages", p.dashboard.carPages.size)
        put("lastUsedMs", p.lastUsedMs)
    }

    private fun invalidProfile(message: String) = ApiException(422, "invalid_profile", message)

    private fun requireProfile(set: ProfileSet, id: String): Profile =
        set.profile(id) ?: throw notFound("profile_not_found", "No profile '$id'; see GET /api/v1/profiles")

    /** series / model of [b] over [base] (a field given as "" is removed). */
    private fun profileVehicle(b: JsonObject, base: ProfileVehicle?): ProfileVehicle {
        fun field(key: String, current: String?): String? = if (key in b) b.string(key)?.trim()?.takeIf { it.isNotEmpty() } else current
        val series = field("series", base?.series)
        val model = field("model", base?.model)
        if (listOfNotNull(series, model).any { it.length > Profiles.MAX_FIELD_LENGTH }) {
            throw invalidProfile("'series' and 'model' have at most ${Profiles.MAX_FIELD_LENGTH} characters")
        }
        return ProfileVehicle(series, model)
    }

    /** `ecus` of [b], normalized; null when absent. */
    private fun profileEcus(b: JsonObject): List<String>? {
        val list = b.stringList("ecus") ?: return null
        if (list.size > Profiles.MAX_ECUS) throw invalidProfile("At most ${Profiles.MAX_ECUS} ECUs")
        val bad = list.filter { Profiles.normalizeEcus(listOf(it)).isEmpty() }
        if (bad.isNotEmpty()) throw invalidProfile("Invalid ECU name(s): ${bad.joinToString()} (SGBD names such as '<sgbd>', see GET /api/v1/ecus)")
        return Profiles.normalizeEcus(list)
    }

    /** Load → edit → save under one lock, so concurrent API edits never lose each other. */
    private fun <T> editLayout(op: (Dashboard) -> Pair<Dashboard, T>): T = synchronized(layoutLock) {
        backend.editDashboard(op)
    }

    /** Warnings about measurement ids that the ECU catalog does not know (the layout is still accepted). */
    private fun unknownMeasurementWarnings(d: Dashboard): List<LayoutIssue> {
        val out = ArrayList<LayoutIssue>()
        for (t in Target.entries) d.pages(t).forEachIndexed { pi, p ->
            p.cards.forEachIndexed { ci, c ->
                c.measurementIds.forEachIndexed { mi, id ->
                    if (id.contains('/') && meta(id) == null) {
                        out += LayoutIssue("${if (t == Target.Phone) "phonePages" else "carPages"}[$pi].cards[$ci].measurementIds[$mi]", "unknown_measurement",
                            "'$id' is not in the imported ECU folder: the card will show no data until its SGBD is imported")
                    }
                }
            }
        }
        return out
    }

    private fun cardWarnings(c: Card): JsonArray = JsonArray(c.measurementIds.filter { it.contains('/') && meta(it) == null }.map {
        JsonPrimitive("'$it' is not in the imported ECU folder: the card will show no data until its SGBD is imported")
    })

    private fun target(call: Call) = LayoutOps.parseTarget(call.path("target"))

    private fun png(bytes: ByteArray) = HttpResponse.Bytes(200, "image/png", bytes)

    private fun render(t: Target, page: Page, index: Int, count: Int, width: Int?, height: Int?): HttpResponse {
        val w = width ?: if (t == Target.Phone) 1080 else 1280
        val h = height ?: if (t == Target.Phone) 1920 else 480
        val ids = page.measurementIds()
        val metaIndex = ids.mapNotNull { id -> meta(id)?.let { id to it } }.toMap()
        val bytes = backend.renderPage(RenderRequest(t, page, index, count, w, h, metaIndex, backend.samples.value, history, backend.status(), backend.recording.value))
        return png(bytes)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = clock() + timeoutMs
        while (clock() < end) {
            if (condition()) return true
            sleep(50)
        }
        return condition()
    }

    // ================================================================ routes

    private fun buildRoutes(): List<Route> {
        val targetParam = Param("target", "path", "`phone` (portrait phone dashboard) or `car` (Android Auto).", S.str(enum = listOf("phone", "car")), example = JsonPrimitive("phone"))
        val pageParam = Param("page", "path", "Page id, or its 0-based index in the page list.", S.str(), example = JsonPrimitive("0"))
        val profileParam = Param("profile", "path", "Profile id (see GET /api/v1/profiles).", S.str(), example = JsonPrimitive("a1b2c3d4e5"))
        val cardParam = Param("card", "path", "Card id (see the page's `cards`).", S.str(), example = JsonPrimitive("k3j4h5g6f7"))
        val idsBody = "ids" to S.arr(S.str(example = "<sgbd>/<id>"), "Measurement ids (`<sgbd>/<key>`), at most 64.", 1, 64)
        val layoutErrors = mapOf(404 to "Unknown page or card.", 409 to "Conflict: overlapping cards, page full, duplicate id.", 422 to "Invalid card or grid (details list each problem).", 400 to "Malformed body.")

        return listOf(
            // ------------------------------------------------------------ general
            Route(
                "GET", "/api/v1/openapi.json", RouteAccess.Public, "General", "getOpenApi",
                "This OpenAPI 3.1 document",
                "Machine-readable description of every operation, with schemas and examples. Start here. Public: no token needed (it contains no data).",
                reply = Reply(description = "OpenAPI document", schema = S.obj("OpenAPI 3.1 document", additional = true)),
            ) { call ->
                val host = call.request.header("host")?.takeIf { h -> h.isNotBlank() && h.all { it.isLetterOrDigit() || it in ".:-[]" } }
                val scheme = if (call.request.secure) "https" else "http"
                ok(OpenApi.document(routes, host?.let { "$scheme://$it" }, backend.appVersion, scheme))
            },
            Route(
                "GET", "/api/v1/docs", RouteAccess.Public, "General", "getDocs",
                "Interactive documentation (Swagger UI)",
                "Swagger UI for this API, embedded in the app (nothing is loaded from the internet). Open it in a browser; use \"Authorize\" with the token to try the operations. Public.",
                reply = Reply(description = "HTML page", schema = S.str("HTML"), contentType = "text/html; charset=utf-8"),
            ) {
                HttpResponse.Bytes(200, "text/html; charset=utf-8", ApiDocs.html("/api/v1/docs/", specUrl = "/api/v1/openapi.json").toByteArray(Charsets.UTF_8), mapOf("Cache-Control" to "no-cache"))
            },
            Route(
                "GET", "/api/v1/docs/{file}", RouteAccess.Public, "General", "getDocsFile",
                "Swagger UI file",
                "Script, style sheet and license files of the embedded Swagger UI (Apache-2.0). Public.",
                params = listOf(Param("file", "path", "File name.", S.str(enum = ApiDocs.ASSETS.keys.toList()))),
                reply = Reply(description = "File", schema = S.str("File content", format = "binary"), contentType = "application/octet-stream"),
                errors = mapOf(404 to "Unknown file."),
            ) { call ->
                val name = call.path("file")
                val type = ApiDocs.ASSETS[name] ?: throw notFound("not_found", "Unknown documentation file '$name'")
                val bytes = docsAssets(name) ?: throw notFound("not_found", "The documentation files are not available")
                HttpResponse.Bytes(200, type, bytes, mapOf("Cache-Control" to "max-age=86400"))
            },
            Route(
                "GET", "/api/v1", RouteAccess.AnyToken, "General", "getIndex",
                "Check the token and list the granted scopes",
                "Returns the app version, the API version and the scopes enabled for the token. Use it to check that the base URL and token work.",
                reply = Reply(description = "API index", schema = S.obj(null, listOf("name", "apiVersion", "appVersion", "scopes"), false, null,
                    "name" to S.str(example = "BimmerData"), "apiVersion" to S.str(example = "1"), "appVersion" to S.str(),
                    "scopes" to S.arr(S.str(enum = Scope.entries.map { it.id }), "Scopes enabled for this token."),
                    "openapi" to S.str(example = "/api/v1/openapi.json"))),
            ) { call ->
                ok(buildJsonObject {
                    put("name", "BimmerData"); put("apiVersion", "1"); put("appVersion", backend.appVersion)
                    put("scopes", JsonArray(call.access!!.scopes.sortedBy { it.ordinal }.map { JsonPrimitive(it.id) }))
                    put("openapi", "/api/v1/openapi.json")
                })
            },
            Route(
                "GET", "/api/v1/status", RouteAccess.Needs(Scope.Read), "General", "getStatus",
                "Connection, read rate and recording state",
                "Whether the app is connected to the car (or the simulation), how many read cycles per second the engine achieves, what it reads, and whether a CSV recording runs.",
                reply = Reply(description = "Current status", schema = S.ref("Status")),
            ) { ok(statusJson()) },

            // ------------------------------------------------------------ live data
            Route(
                "GET", "/api/v1/ecus", RouteAccess.Needs(Scope.Read), "Live data", "listEcus",
                "List ECUs (SGBDs) that have live measurements",
                "SGBD description files of the imported ECU folder that have a measurement table. Detected ECUs of the connected car (when vehicle detection is available) come first with source `detected`. The first call after an import can take a few seconds.",
                reply = Reply(description = "ECUs", schema = S.obj(null, listOf("ecus"), false, null, "ecus" to S.arr(S.ref("Ecu")))),
            ) {
                ok(buildJsonObject { put("ecus", ApiJson.encodeToJsonElement(backend.ecus())) })
            },
            Route(
                "GET", "/api/v1/ecus/{sgbd}/measurements", RouteAccess.Needs(Scope.Read), "Live data", "listMeasurements",
                "List the measurements of one ECU",
                "All live values of the SGBD; `table` ones (MESSWERTETAB) are read fastest. Filter with `q` (case-insensitive, matches id, label, unit and result name).",
                params = listOf(
                    Param("sgbd", "path", "SGBD name from /ecus.", S.str(), example = JsonPrimitive("<sgbd>")),
                    Param("q", "query", "Search text, e.g. `temp`, `lambda`, `°C`.", S.str(), example = JsonPrimitive("temp")),
                    Param("limit", "query", "Max results (default 200).", S.int(min = 1, max = 2000, default = 200)),
                    Param("offset", "query", "Skip this many results.", S.int(min = 0, default = 0)),
                ),
                reply = Reply(description = "Measurements", schema = S.obj(null, listOf("sgbd", "total", "measurements"), false, null,
                    "sgbd" to S.str(), "total" to S.int("Matches before limit / offset."), "measurements" to S.arr(S.ref("Measurement")))),
                errors = mapOf(404 to "Unknown SGBD or no measurements."),
            ) { call ->
                val sgbd = call.path("sgbd").lowercase()
                val all = catalog(sgbd).values.toList()
                if (all.isEmpty()) throw notFound("unknown_sgbd", "No measurements for SGBD '$sgbd'; list the available ones with GET /api/v1/ecus")
                val matches = filterMeasurements(all, call.query("q"))
                val offset = call.queryInt("offset", 0, 0..Int.MAX_VALUE)
                val limit = call.queryInt("limit", 200, 1..2000)
                ok(buildJsonObject {
                    put("sgbd", sgbd); put("total", matches.size)
                    put("measurements", JsonArray(matches.drop(offset).take(limit).map(::measurementJson)))
                })
            },
            Route(
                "GET", "/api/v1/measurements", RouteAccess.Needs(Scope.Read), "Live data", "searchMeasurements",
                "Search measurements across all ECUs",
                "Searches every SGBD of /ecus. Slower than the per-ECU list on the first call (each SGBD is parsed once, then cached).",
                params = listOf(
                    Param("q", "query", "Search text (required, ≥ 2 characters).", S.str(), required = true, example = JsonPrimitive("oil")),
                    Param("limit", "query", "Max results (default 100).", S.int(min = 1, max = 1000, default = 100)),
                ),
                reply = Reply(description = "Matches", schema = S.obj(null, listOf("total", "measurements"), false, null, "total" to S.int(), "measurements" to S.arr(S.ref("Measurement")))),
            ) { call ->
                val q = call.query("q")?.takeIf { it.length >= 2 } ?: throw badRequest("Query parameter 'q' needs at least 2 characters")
                val limit = call.queryInt("limit", 100, 1..1000)
                val matches = backend.ecus().map { it.sgbd }.distinct().flatMap { filterMeasurements(catalog(it).values.toList(), q) }
                ok(buildJsonObject { put("total", matches.size); put("measurements", JsonArray(matches.take(limit).map(::measurementJson))) })
            },
            Route(
                "GET", "/api/v1/values", RouteAccess.Needs(Scope.Read), "Live data", "getValues",
                "Latest values (no waiting)",
                "Returns the latest sample of each id right away. Only measurements that someone reads (dashboard page on screen, Android Auto, recorder, API watch) stay fresh; others come back `stale` or `no_data`. Without `ids`, returns every measurement read right now. To make sure values are fresh, use POST /api/v1/values/read or a watch.",
                params = listOf(Param("ids", "query", "Comma-separated measurement ids.", S.str(), required = false, example = JsonPrimitive("<sgbd>/<id>,<sgbd>/<id2>"))),
                reply = Reply(description = "Values", schema = S.obj(null, listOf("values"), false, null, "values" to S.arr(S.ref("Value")), "connection" to S.str()),
                    example = buildJsonObject {
                        putJsonArray("values") { addJsonObject { put("id", "<sgbd>/<id>"); put("label", "Öltemperatur"); put("unit", "°C"); put("value", 92.4); put("timestampMs", 1762606992000); put("ageMs", 180); put("status", "fresh") } }
                        put("connection", "connected")
                    }),
            ) { call ->
                val now = clock()
                val snapshot = backend.samples.value
                val ids = call.queryList("ids") ?: backend.status().measurementsRead
                ok(buildJsonObject {
                    put("values", JsonArray(ids.distinct().map { valueJson(it, snapshot[it], now) }))
                    put("connection", backend.status().connection.state)
                })
            },
            Route(
                "POST", "/api/v1/values/read", RouteAccess.Needs(Scope.Read), "Live data", "readValues",
                "Read values now (waits for fresh samples), optionally sample for a while",
                "Asks the engine to read `ids` (together with what the screens show) and waits until each has a sample newer than the request (up to `timeoutMs`). With `seconds` > 0 it keeps reading for that long and also returns every sample with min / max / mean — handy to watch a value change (e.g. coolant temperature while warming up). Needs a connection (409 otherwise). Blocking: the answer comes after the sampling time.",
                body = Body(S.obj(null, listOf("ids"), false, null, idsBody,
                    "seconds" to S.int("Sample for this many seconds (0 = just one fresh value each).", 0, 60, default = 0),
                    "timeoutMs" to S.int("How long to wait for fresh values.", 100, 15000, default = 4000)),
                    example = buildJsonObject { putJsonArray("ids") { add("<sgbd>/<id>"); add("<sgbd>/<id2>") }; put("seconds", 5) }),
                reply = Reply(description = "Values (and series when seconds > 0)", schema = S.obj(null, listOf("values"), false, null,
                    "values" to S.arr(S.ref("Value")), "series" to S.arr(S.ref("Series"), "Only when seconds > 0."), "waitedMs" to S.int())),
                errors = mapOf(400 to "Malformed body.", 404 to "Unknown measurement id (details lists them).", 409 to "Not connected."),
            ) { call ->
                val body = call.bodyObject()
                body.allowOnly("ids", "seconds", "timeoutMs")
                val ids = body.stringList("ids") ?: throw badRequest("'ids' is required")
                val seconds = body.int("seconds") ?: 0
                if (seconds !in 0..60) throw badRequest("'seconds' must be 0..60")
                val timeout = (body.long("timeoutMs") ?: 4000L)
                if (timeout !in 100L..15000L) throw badRequest("'timeoutMs' must be 100..15000")
                val ms = resolve(ids)
                requireConnected()
                val start = clock()
                reads.acquire(ms, seconds * 1000L + timeout + 1000).use {
                    waitFor(timeout) { ms.all { m -> (backend.samples.value[m.id]?.timestampMs ?: Long.MIN_VALUE) >= start } }
                    if (seconds > 0) {
                        val end = start + seconds * 1000L
                        while (clock() < end && !stopped) { sleep(minOf(100L, (end - clock()).coerceAtLeast(1))); tick() }
                    }
                }
                tick()
                val now = clock()
                val snapshot = backend.samples.value
                ok(buildJsonObject {
                    put("values", JsonArray(ms.map { valueJson(it.id, snapshot[it.id], now) }))
                    if (seconds > 0) put("series", JsonArray(ms.map { seriesJson(it.id, history.points(it.id, start)) }))
                    put("waitedMs", now - start)
                })
            },
            Route(
                "POST", "/api/v1/values/watch", RouteAccess.Needs(Scope.Read), "Live data", "watchValues",
                "Keep reading measurements for a while (then poll GET /values or /history)",
                "Adds `ids` to what the engine reads for `seconds` (default 300) without blocking. Calling it again extends the watch. Useful for scripts that poll GET /api/v1/values or collect GET /api/v1/history. Each extra measurement slows the others down, so watch only what you need.",
                body = Body(S.obj(null, listOf("ids"), false, null, idsBody,
                    "seconds" to S.int("Watch duration.", 1, 3600, default = 300)),
                    example = buildJsonObject { putJsonArray("ids") { add("<sgbd>/<id>") }; put("seconds", 600) }),
                reply = Reply(description = "Watch registered", schema = S.obj(null, listOf("ids", "expiresAtMs"), false, null,
                    "ids" to S.arr(S.str()), "expiresAtMs" to S.int(format = "int64"))),
                errors = mapOf(404 to "Unknown measurement id."),
            ) { call ->
                val body = call.bodyObject()
                body.allowOnly("ids", "seconds")
                val ms = resolve(body.stringList("ids") ?: throw badRequest("'ids' is required"))
                val seconds = body.int("seconds") ?: 300
                if (seconds !in 1..3600) throw badRequest("'seconds' must be 1..3600")
                val lease = reads.acquire(ms, seconds * 1000L)
                synchronized(watches) { watches.removeAll { it.expiresAtMs <= clock() }; watches += lease }
                ok(buildJsonObject { put("ids", JsonArray(ms.map { JsonPrimitive(it.id) })); put("expiresAtMs", lease.expiresAtMs) })
            },
            Route(
                "DELETE", "/api/v1/values/watch", RouteAccess.Needs(Scope.Read), "Live data", "clearWatches",
                "Stop all watches",
                "Stops reading the measurements added by POST /values/watch (timed reads and streams in progress are not affected).",
                reply = Reply(204, "Watches cleared"),
            ) {
                releaseAll()
                HttpResponse.Bytes(204, HttpServer.JSON, ByteArray(0))
            },
            Route(
                "GET", "/api/v1/history", RouteAccess.Needs(Scope.Read), "Live data", "getHistory",
                "Recent samples kept by the API",
                "Samples recorded while the API server runs (last ${history.retentionSeconds / 60} minutes at most), whoever requested them. Without `ids`, lists the ids that have history.",
                params = listOf(
                    Param("ids", "query", "Comma-separated measurement ids.", S.str(), required = false),
                    Param("seconds", "query", "How far back (default 60).", S.int(min = 1, max = history.retentionSeconds, default = 60)),
                ),
                reply = Reply(description = "Series, or the list of ids", schema = S.obj(null, emptyList(), false, null, "series" to S.arr(S.ref("Series")), "ids" to S.arr(S.str()))),
            ) { call ->
                val ids = call.queryList("ids")
                if (ids == null) ok(buildJsonObject { put("ids", JsonArray(history.ids().map(::JsonPrimitive))) })
                else {
                    val seconds = call.queryInt("seconds", 60, 1..history.retentionSeconds.toInt())
                    val since = clock() - seconds * 1000L
                    ok(buildJsonObject { put("series", JsonArray(ids.distinct().map { seriesJson(it, history.points(it, since)) })) })
                }
            },
            Route(
                "GET", "/api/v1/stream", RouteAccess.Needs(Scope.Read), "Live data", "streamValues",
                "Server-Sent Events stream of live values",
                "`text/event-stream`. Events: `samples` (data: {\"t\":epochMs,\"values\":{id:{\"v\":number,\"text\":string,\"t\":epochMs}}}) with the samples that changed, at most every `intervalMs`; `status` (data: Status) every 5 s; comment lines `: ping` keep the connection alive. With `ids` the measurements are read for as long as the stream is open. At most 4 streams at once. Example: `curl -N -H 'Authorization: Bearer …' 'http://phone:8765/api/v1/stream?ids=<sgbd>/<id>'`.",
                params = listOf(
                    Param("ids", "query", "Comma-separated measurement ids; omitted = everything read right now.", S.str(), required = false),
                    Param("intervalMs", "query", "Minimum time between `samples` events (default 250).", S.int(min = 50, max = 10000, default = 250)),
                ),
                reply = Reply(description = "Event stream", schema = S.str("Server-Sent Events"), contentType = "text/event-stream"),
                errors = mapOf(404 to "Unknown measurement id.", 429 to "Too many open streams."),
            ) { call -> stream(call) },

            // ------------------------------------------------------------ dashboard
            Route(
                "GET", "/api/v1/dashboard", RouteAccess.Needs(Scope.Read), "Dashboard", "getDashboard",
                "The whole layout (phone and Android Auto pages)",
                "The layout as stored by the app; the same JSON can be sent back with PUT /api/v1/dashboard. Grid limits: phone ${GridLimits.Phone.maxColumns} columns × ${GridLimits.Phone.maxRows} rows (≤ ${GridLimits.Phone.maxCards} cards per page), car ${GridLimits.Car.maxColumns} × ${GridLimits.Car.maxRows} (≤ ${GridLimits.Car.maxCards} cards).",
                reply = Reply(description = "Dashboard", schema = S.ref("Dashboard")),
            ) { ok(dashboardJson(backend.loadDashboard())) },
            Route(
                "PUT", "/api/v1/dashboard", RouteAccess.Needs(Scope.Layout), "Dashboard", "setDashboard",
                "Replace the whole layout",
                "Validates strictly (no silent fixing) and saves. On error nothing is changed and `error.details` lists every problem with its path. Measurement ids that are not in the imported ECU folder are accepted but returned as `warnings`. Add `?dryRun=true` to only validate.",
                params = listOf(Param("dryRun", "query", "Validate only, do not save.", S.bool(default = false), required = false)),
                body = Body(S.ref("Dashboard"), example = buildJsonObject {
                    putJsonArray("phonePages") { addJsonObject {
                        put("name", "Engine"); put("columns", 2); put("rows", 4)
                        putJsonArray("cards") {
                            addJsonObject { put("type", "Dial"); putJsonArray("measurementIds") { add("<sgbd>/<id>") }; put("title", "Oil"); put("colSpan", 1); put("rowSpan", 2) }
                            addJsonObject { put("type", "Trend"); putJsonArray("measurementIds") { add("<sgbd>/<id2>") }; put("col", 1); put("rowSpan", 2) }
                        }
                    } }
                    putJsonArray("carPages") { addJsonObject { put("name", "Engine"); put("columns", 4); put("rows", 1); putJsonArray("cards") {} } }
                }),
                reply = Reply(description = "Saved (or valid, with dryRun)", schema = S.obj(null, listOf("saved", "dashboard"), false, null,
                    "saved" to S.bool(), "dashboard" to S.ref("Dashboard"), "warnings" to S.arr(S.ref("LayoutIssue")))),
                errors = mapOf(400 to "Not JSON or wrong field types / unknown fields.", 422 to "Layout rules violated (details: LayoutIssue[])."),
            ) { call ->
                val d = call.decode(Dashboard.serializer(), what = "dashboard")
                LayoutOps.requireValid(d)
                val dry = call.queryBool("dryRun", false)
                if (!dry) synchronized(layoutLock) { backend.saveDashboard(d) }
                ok(buildJsonObject {
                    put("saved", !dry)
                    put("dashboard", dashboardJson(d))
                    put("warnings", ApiJson.encodeToJsonElement(unknownMeasurementWarnings(d)))
                })
            },
            Route(
                "POST", "/api/v1/dashboard/validate", RouteAccess.Needs(Scope.Read), "Dashboard", "validateDashboard",
                "Check a layout without saving it",
                "Same checks as PUT /api/v1/dashboard. Always 200 when the body is a parsable dashboard: read `valid` and `issues`.",
                body = Body(S.ref("Dashboard")),
                reply = Reply(description = "Validation result", schema = S.obj(null, listOf("valid", "issues", "warnings"), false, null,
                    "valid" to S.bool(), "issues" to S.arr(S.ref("LayoutIssue")), "warnings" to S.arr(S.ref("LayoutIssue")))),
                errors = mapOf(400 to "Not JSON or wrong field types / unknown fields."),
            ) { call ->
                val d = call.decode(Dashboard.serializer(), what = "dashboard")
                val issues = LayoutOps.validate(d)
                ok(buildJsonObject {
                    put("valid", issues.isEmpty())
                    put("issues", ApiJson.encodeToJsonElement(issues))
                    put("warnings", ApiJson.encodeToJsonElement(unknownMeasurementWarnings(d)))
                })
            },
            Route(
                "POST", "/api/v1/dashboard/reset", RouteAccess.Needs(Scope.Layout), "Dashboard", "resetDashboard",
                "Empty both sides",
                "Removes every page of the phone AND of Android Auto (the empty first-launch state); the dashboard name is kept. To empty one side only, use POST /api/v1/dashboard/{target}/reset. Consider GET /api/v1/dashboard first to keep a copy.",
                reply = Reply(description = "The new layout", schema = S.ref("Dashboard")),
            ) {
                val d = editLayout { d -> DashboardReset.all(d).let { it to it } }
                ok(dashboardJson(d))
            },
            Route(
                "POST", "/api/v1/dashboard/{target}/reset", RouteAccess.Needs(Scope.Layout), "Dashboard", "resetTarget",
                "Empty one side",
                "Removes every page of `target` only (phone OR Android Auto); the other side and the dashboard name are not touched.",
                params = listOf(targetParam),
                reply = Reply(description = "The new layout", schema = S.ref("Dashboard")),
            ) { call ->
                val t = target(call)
                val d = editLayout { d -> DashboardReset.target(d, t).let { it to it } }
                ok(dashboardJson(d))
            },
            Route(
                "POST", "/api/v1/dashboard/car/from-phone", RouteAccess.Needs(Scope.Layout), "Dashboard", "carFromPhone",
                "Android Auto = the phone's cards",
                "Replaces every Android Auto page by the cards of the phone pages: one Android Auto page per 8 cards of a phone page on its 4 x 2 grid (both rows used from 4 cards on), " +
                    "in the phone's reading order (\"Engine\" with 10 cards gives \"Engine 1/2\" and \"Engine 2/2\"), one card per cell." +
                    "Cards keep their type, values, title, thresholds and decimals. Run it after changing the phone pages when Android Auto " +
                    "must show the same cards as the phone (layout rule of this API, see the dashboard docs).",
                reply = Reply(description = "The new layout", schema = S.ref("Dashboard")),
            ) {
                val d = editLayout { d -> io.github.jonathangtd.bimmerdata.dashboard.DashboardMirror.carFromPhone(d).let { it to it } }
                ok(dashboardJson(d))
            },
            Route(
                "GET", "/api/v1/dashboard/{target}/pages", RouteAccess.Needs(Scope.Read), "Dashboard", "listPages",
                "Pages of the phone or of Android Auto",
                "Pages in order, each with its `index`, cards and `freeCells` (free grid cells as `(col,row)`).",
                params = listOf(targetParam),
                reply = Reply(description = "Pages", schema = S.obj(null, listOf("target", "limits", "pages"), false, null,
                    "target" to S.str(), "limits" to S.obj(null, emptyList(), false, null, "maxColumns" to S.int(), "maxRows" to S.int(), "maxCards" to S.int()),
                    "pages" to S.arr(S.ref("Page")))),
            ) { call ->
                val t = target(call)
                val lim = GridLimits.of(t)
                ok(buildJsonObject {
                    put("target", LayoutOps.targetName(t))
                    put("limits", buildJsonObject { put("maxColumns", lim.maxColumns); put("maxRows", lim.maxRows); put("maxCards", lim.maxCards) })
                    put("pages", JsonArray(backend.loadDashboard().pages(t).mapIndexed { i, p -> pageJson(p, i) }))
                })
            },
            Route(
                "POST", "/api/v1/dashboard/{target}/pages", RouteAccess.Needs(Scope.Layout), "Dashboard", "addPage",
                "Add an empty page",
                "Creates an empty page with a `columns × rows` grid (defaults: phone 2×6, car 4×1); then add cards, which fill the first free cells unless given a position.",
                params = listOf(targetParam),
                body = Body(S.obj(null, listOf("name"), false, null,
                    "name" to S.str("Page title.", "Temperatures"),
                    "columns" to S.int(min = 1, max = 4), "rows" to S.int(min = 1, max = 8),
                    "position" to S.int("Insert at this index (default: last).", min = 0)),
                    example = buildJsonObject { put("name", "Temperatures"); put("columns", 2); put("rows", 3) }),
                reply = Reply(201, "The new page", S.ref("Page")),
                errors = layoutErrors,
            ) { call ->
                val t = target(call)
                val b = call.bodyObject()
                b.allowOnly("name", "columns", "rows", "position")
                val page = editLayout { d ->
                    LayoutOps.addPage(d, t, b.string("name") ?: throw badRequest("'name' is required"), b.int("columns"), b.int("rows"), b.int("position"))
                }
                ok(pageJson(page, backend.loadDashboard().pages(t).indexOfFirst { it.id == page.id }), 201)
            },
            Route(
                "GET", "/api/v1/dashboard/{target}/pages/{page}", RouteAccess.Needs(Scope.Read), "Dashboard", "getPage",
                "One page",
                "The page with its cards and `freeCells`.",
                params = listOf(targetParam, pageParam),
                reply = Reply(description = "Page", schema = S.ref("Page")),
                errors = mapOf(404 to "Unknown page."),
            ) { call ->
                val t = target(call)
                val (i, p) = LayoutOps.findPage(backend.loadDashboard(), t, call.path("page"))
                ok(pageJson(p, i))
            },
            Route(
                "PATCH", "/api/v1/dashboard/{target}/pages/{page}", RouteAccess.Needs(Scope.Layout), "Dashboard", "updatePage",
                "Rename, resize the grid or move a page",
                "Changing `columns` / `rows` keeps cards in place when they fit, else shrinks or moves them (409 if they cannot all be placed). `position` moves the page in the list.",
                params = listOf(targetParam, pageParam),
                body = Body(S.obj(null, emptyList(), false, null,
                    "name" to S.str(), "columns" to S.int(min = 1, max = 4), "rows" to S.int(min = 1, max = 8), "position" to S.int(min = 0)),
                    example = buildJsonObject { put("name", "Engine 2"); put("rows", 4) }),
                reply = Reply(description = "The updated page", schema = S.ref("Page")),
                errors = layoutErrors,
            ) { call ->
                val t = target(call)
                val b = call.bodyObject()
                b.allowOnly("name", "columns", "rows", "position")
                val page = editLayout { d -> LayoutOps.updatePage(d, t, call.path("page"), b.string("name"), b.int("columns"), b.int("rows"), b.int("position")) }
                ok(pageJson(page))
            },
            Route(
                "DELETE", "/api/v1/dashboard/{target}/pages/{page}", RouteAccess.Needs(Scope.Layout), "Dashboard", "deletePage",
                "Delete a page",
                "Deletes the page and its cards. Deleting the last page leaves the target empty (no page), which is valid.",
                params = listOf(targetParam, pageParam),
                reply = Reply(204, "Deleted"),
                errors = layoutErrors,
            ) { call ->
                val t = target(call)
                editLayout { d -> LayoutOps.deletePage(d, t, call.path("page")) to Unit }
                HttpResponse.Bytes(204, HttpServer.JSON, ByteArray(0))
            },
            Route(
                "POST", "/api/v1/dashboard/{target}/pages/{page}/cards", RouteAccess.Needs(Scope.Layout), "Dashboard", "addCard",
                "Add a card",
                "With `col` and/or `row` the card goes exactly there (409 `card_overlap` / 422 `out_of_grid` otherwise, with the free cells in the message). Without them it takes the first free slot of its span (reading order). The answer is the stored card with its id; `warnings` flags measurement ids missing from the ECU folder.",
                params = listOf(targetParam, pageParam),
                body = Body(S.ref("Card"), example = buildJsonObject {
                    put("type", "Dial"); putJsonArray("measurementIds") { add("<sgbd>/<id>") }; put("title", "Oil"); put("warnAbove", 120); put("colSpan", 1); put("rowSpan", 2)
                }),
                reply = Reply(201, "The new card", S.obj(null, listOf("card"), false, null, "card" to S.ref("Card"), "warnings" to S.arr(S.str()))),
                errors = layoutErrors,
            ) { call ->
                val t = target(call)
                val body = call.bodyObject()
                val card = call.decode(Card.serializer(), body, "card")
                val explicit = body.containsKey("col") || body.containsKey("row")
                val added = editLayout { d -> LayoutOps.addCard(d, t, call.path("page"), card, explicit) }
                ok(buildJsonObject { put("card", cardJson(added)); put("warnings", cardWarnings(added)) }, 201)
            },
            Route(
                "PATCH", "/api/v1/dashboard/{target}/pages/{page}/cards/{card}", RouteAccess.Needs(Scope.Layout), "Dashboard", "updateCard",
                "Change a card (move, resize, retitle, other measurement…)",
                "JSON merge patch on the card: listed fields replace the current ones, `null` resets an optional field to automatic, absent fields stay. The result must still fit the grid without overlapping (move another card first if needed).",
                params = listOf(targetParam, pageParam, cardParam),
                body = Body(S.ref("CardPatch"), example = buildJsonObject { put("col", 1); put("row", 0); put("warnAbove", 125) }),
                reply = Reply(description = "The updated card", schema = S.obj(null, listOf("card"), false, null, "card" to S.ref("Card"), "warnings" to S.arr(S.str()))),
                errors = layoutErrors,
            ) { call ->
                val t = target(call)
                val patch = call.bodyObject()
                val cardId = call.path("card")
                val updated = editLayout { d ->
                    val (_, page) = LayoutOps.findPage(d, t, call.path("page"))
                    val current = cardJson(LayoutOps.findCard(page, cardId)).jsonObject
                    if (patch["id"]?.let { it is JsonPrimitive && it.content != cardId } == true) throw badRequest("A card id cannot be changed")
                    val merged = JsonObject(current.toMutableMap().apply {
                        for ((k, v) in patch) if (v is JsonNull) remove(k) else put(k, v)
                    })
                    val card = call.decode(Card.serializer(), merged, "card")
                    LayoutOps.updateCard(d, t, page.id, cardId, card)
                }
                ok(buildJsonObject { put("card", cardJson(updated)); put("warnings", cardWarnings(updated)) })
            },
            Route(
                "DELETE", "/api/v1/dashboard/{target}/pages/{page}/cards/{card}", RouteAccess.Needs(Scope.Layout), "Dashboard", "deleteCard",
                "Delete a card",
                "Removes the card from the page.",
                params = listOf(targetParam, pageParam, cardParam),
                reply = Reply(204, "Deleted"),
                errors = layoutErrors,
            ) { call ->
                val t = target(call)
                editLayout { d -> LayoutOps.deleteCard(d, t, call.path("page"), call.path("card")) to Unit }
                HttpResponse.Bytes(204, HttpServer.JSON, ByteArray(0))
            },
            Route(
                "GET", "/api/v1/dashboard/{target}/pages/{page}/render.png", RouteAccess.Needs(Scope.Read), "Dashboard", "renderPage",
                "Picture of a page (PNG)",
                "Draws the page with the app's own renderer and the latest live values: the phone page as on a portrait phone (default 1080×1920), the car page as on a 1280×480 Android Auto head unit (with a stand-in for the system rail). Use it to check a layout you built. Values not being read show as \"--\".",
                params = listOf(targetParam, pageParam,
                    Param("width", "query", "Width in px (phone default 1080, car 1280).", S.int(min = 200, max = 2560), required = false),
                    Param("height", "query", "Height in px (phone default 1920, car 480).", S.int(min = 200, max = 2560), required = false)),
                reply = Reply(description = "PNG image", schema = S.str("PNG image", format = "binary"), contentType = "image/png"),
                errors = mapOf(404 to "Unknown page.", 400 to "Bad size."),
            ) { call ->
                val t = target(call)
                val d = backend.loadDashboard()
                val (i, p) = LayoutOps.findPage(d, t, call.path("page"))
                val w = call.query("width")?.let { call.queryInt("width", 0, 200..2560) }
                val h = call.query("height")?.let { call.queryInt("height", 0, 200..2560) }
                render(t, p, i, d.pages(t).size, w, h)
            },
            Route(
                "POST", "/api/v1/dashboard/{target}/render", RouteAccess.Needs(Scope.Read), "Dashboard", "renderDraftPage",
                "Picture of a page that is not saved (PNG preview)",
                "Same as render.png but for a page given in the body, to preview a design before saving it. The page must be valid for the target (422 otherwise).",
                params = listOf(targetParam,
                    Param("width", "query", "Width in px.", S.int(min = 200, max = 2560), required = false),
                    Param("height", "query", "Height in px.", S.int(min = 200, max = 2560), required = false)),
                body = Body(S.ref("Page")),
                reply = Reply(description = "PNG image", schema = S.str("PNG image", format = "binary"), contentType = "image/png"),
                errors = mapOf(400 to "Malformed page.", 422 to "Invalid page."),
            ) { call ->
                val t = target(call)
                val page = call.decode(Page.serializer(), what = "page")
                val probe = Dashboard(phonePages = listOf(if (t == Target.Phone) page else Page(name = "x", columns = 1, rows = 1)),
                    carPages = listOf(if (t == Target.Car) page else Page(name = "x", columns = 1, rows = 1)))
                LayoutOps.requireValid(probe)
                val w = call.query("width")?.let { call.queryInt("width", 0, 200..2560) }
                val h = call.query("height")?.let { call.queryInt("height", 0, 200..2560) }
                render(t, page, 0, 1, w, h)
            },
            // ------------------------------------------------------------ profiles
            Route(
                "GET", "/api/v1/profiles", RouteAccess.Needs(Scope.Read), "Profiles", "listProfiles",
                "Profiles of the selected car and the active one",
                "The profiles (main ECUs and their own phone and Android Auto pages) of the selected car (GET /api/v1/cars), or of car `car`, or of every car with `car=all`; `active` is set on the one shown, always one of the selected car's. The /api/v1/dashboard routes read and write the active profile's layout. `profiles` is empty on a fresh install.",
                params = listOf(Param("car", "query", "A car id, or `all`; default: the selected car.", S.str(), required = false)),
                reply = Reply(description = "Profiles", schema = S.obj(null, listOf("profiles"), false, null,
                    "selectedCarId" to S.str("Id of the selected car; absent when there is none."),
                    "activeId" to S.str("Id of the active profile; absent when there is none."),
                    "profiles" to S.arr(S.ref("ProfileSummary")))),
                errors = mapOf(404 to "Unknown car."),
            ) { call ->
                val space = backend.cars()
                val set = space.profiles
                val shown = when (val car = call.query("car")) {
                    "all" -> set.profiles
                    null -> space.garage.currentId?.let { space.profilesOf(it) }.orEmpty()
                    else -> space.profilesOf(requireCar(space, car).id)
                }
                ok(buildJsonObject {
                    space.garage.currentId?.let { put("selectedCarId", it) }
                    set.activeId?.let { put("activeId", it) }
                    put("profiles", JsonArray(shown.map { profileSummaryJson(it, it.id == set.activeId) }))
                })
            },
            Route(
                "POST", "/api/v1/profiles", RouteAccess.Needs(Scope.Layout), "Profiles", "createProfile",
                "Create a profile",
                "Creates a profile of car `car` (default: the selected car; with no car at all, one named \"My car\" is made) and, unless `activate` is false, makes it the active one, so the phone and Android Auto show it at once (activating a profile of another car selects that car). `ecus` must be in the car's ECU list (422 `ecu_not_in_car` lists the allowed ones); a car without ECU list takes them. It starts with no page; give `copyFrom` to copy the pages of another profile. A name already used gets a \" (2)\" suffix.",
                body = Body(S.obj(null, listOf("name"), false, null,
                    "name" to S.str("Profile name (≤ ${Profiles.MAX_NAME_LENGTH}).", "My car · Knock"),
                    "car" to S.str("Id of its car (GET /api/v1/cars); default: the selected car."),
                    "series" to S.str("Car series (free text, kept in shared profile files).", "<series>"),
                    "model" to S.str("Car model (free text, kept in shared profile files).", "<model>"),
                    "ecus" to S.arr(S.str(example = "<sgbd>"), "Main ECUs (SGBD names of the car's ECU list); the card picker opens on them.", 0, Profiles.MAX_ECUS),
                    "copyFrom" to S.str("Id of a profile whose pages are copied."),
                    "activate" to S.bool("Make it the active profile (default true). With false the active profile stays, except on a car that had no profile: a car with profiles always shows one.")),
                    example = buildJsonObject { put("name", "My car · Knock"); put("series", "<series>"); put("model", "<model>"); putJsonArray("ecus") { add("<sgbd>") } }),
                reply = Reply(201, "The new profile", S.ref("Profile")),
                errors = mapOf(400 to "Malformed body.", 404 to "Unknown car or copyFrom profile.", 422 to "Invalid name or ECU list (an ECU not in the car: ecu_not_in_car), or too many profiles."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("name", "car", "series", "model", "ecus", "copyFrom", "activate")
                val name = b.string("name")?.takeIf { it.isNotBlank() } ?: throw invalidProfile("'name' is required")
                val vehicle = profileVehicle(b, null)
                val ecus = profileEcus(b) ?: emptyList()
                val activate = b.bool("activate") ?: true
                val carId = b.string("car")?.trim()?.takeIf { it.isNotEmpty() }
                val created = backend.editCars { start ->
                    if (start.profiles.profiles.size >= Profiles.MAX_PROFILES) throw invalidProfile("At most ${Profiles.MAX_PROFILES} profiles")
                    val (space, car) = if (carId != null) start to requireCar(start, carId) else Cars.ensureSelected(start, backend.defaultCarLabel, clock())
                    requireCarEcus(car, ecus)
                    val pages = b.string("copyFrom")?.let { id -> requireProfile(space.profiles, id).dashboard } ?: Dashboard()
                    Cars.createProfile(space, car.id, name, vehicle, ecus, clock(), pages, activate = activate)
                }
                ok(profileJson(created), 201)
            },
            Route(
                "GET", "/api/v1/profiles/{profile}", RouteAccess.Needs(Scope.Read), "Profiles", "getProfile",
                "One profile, with its layout",
                "The profile with its `dashboard` (phone and Android Auto pages, same format as GET /api/v1/dashboard).",
                params = listOf(profileParam),
                reply = Reply(description = "Profile", schema = S.ref("Profile")),
                errors = mapOf(404 to "Unknown profile."),
            ) { call -> ok(profileJson(requireProfile(backend.profiles(), call.path("profile")))) },
            Route(
                "PUT", "/api/v1/profiles/{profile}", RouteAccess.Needs(Scope.Layout), "Profiles", "updateProfile",
                "Change a profile",
                "Changes the fields given (others are kept): name, series and model, ECUs (new ones must be in its car's ECU list: 422 `ecu_not_in_car`), and / or the whole `dashboard` (validated like PUT /api/v1/dashboard; nothing is changed on error). Works on any profile, active or not. Its car does not change: POST /api/v1/profiles/{profile}/copy puts a copy on another car. Taking out of `ecus` an ECU that still has cards or graph values in the profile answers 409 `ecu_in_use`, unless `removeCards` is true: its cards (phone and Android Auto) and graph values are then deleted too.",
                params = listOf(profileParam),
                body = Body(S.obj(null, emptyList(), false, null,
                    "name" to S.str("New name."),
                    "series" to S.str(), "model" to S.str(),
                    "ecus" to S.arr(S.str(), "Main ECUs (replaces the list).", 0, Profiles.MAX_ECUS),
                    "removeCards" to S.bool("Delete the cards and graph values of the ECUs taken out of `ecus` (default false: 409 when there are some)."),
                    "dashboard" to S.ref("Dashboard")),
                    example = buildJsonObject { putJsonArray("ecus") { add("<engine-sgbd>"); add("<gearbox-sgbd>") } }),
                reply = Reply(description = "The profile", schema = S.ref("Profile")),
                errors = mapOf(400 to "Malformed body.", 404 to "Unknown profile.", 409 to "An ECU taken out still has cards or graph values (code ecu_in_use).", 422 to "Invalid field or layout (details: LayoutIssue[]), or an ECU not in the car (ecu_not_in_car)."),
            ) { call ->
                val id = call.path("profile")
                val b = call.bodyObject()
                b.allowOnly("name", "series", "model", "ecus", "removeCards", "dashboard")
                val removeCards = b.bool("removeCards") ?: false
                val dashboard = b["dashboard"]?.let { el -> call.decode(Dashboard.serializer(), el, what = "dashboard").also { LayoutOps.requireValid(it) } }
                val ecus = profileEcus(b)
                val updated = backend.editCars { space ->
                    val current = requireProfile(space.profiles, id)
                    val name = b.string("name")?.also { if (it.isBlank()) throw invalidProfile("'name' may not be empty") }
                    val vehicle = if (listOf("series", "model").any { it in b }) profileVehicle(b, current.vehicle) else null
                    // the ECUs it already has stay allowed (the car's list may have changed since)
                    if (ecus != null) space.car(current.carId)?.let { requireCarEcus(it, ecus - current.ecus.toSet()) }
                    // an ECU taken out with cards or graph values left: never deleted without the explicit flag
                    val dropped = if (ecus == null) emptyList() else current.ecus.filter { it !in ecus }
                    val withCards = dropped
                        .mapNotNull { sgbd -> EcuUsage.use(current, sgbd)?.takeIf { it.cards > 0 || it.graphSeries > 0 }?.let { sgbd to listOf(it) } }.toMap()
                    if (withCards.isNotEmpty() && !removeCards) throw ecuInUse(withCards)
                    var next = Cars.editProfile(space, id, name, vehicle, ecus, dropCards = withCards.keys)
                    if (dashboard != null) next = next.copy(profiles = Profiles.writeDashboard(next.profiles, id, dashboard.copy(name = null)))
                    next to next.profiles.profile(id)!!
                }
                ok(profileJson(updated))
            },
            Route(
                "DELETE", "/api/v1/profiles/{profile}", RouteAccess.Needs(Scope.Layout), "Profiles", "deleteProfile",
                "Delete a profile and its layout",
                "Removes the profile with its pages. If it was active, the profile of the same car used most recently becomes active (none when it was the car's last). Consider GET /api/v1/profiles/{profile} first to keep a copy.",
                params = listOf(profileParam),
                reply = Reply(description = "Deleted", schema = S.obj(null, listOf("deleted"), false, null,
                    "deleted" to S.bool(), "activeId" to S.str("The active profile now; absent when there is none."))),
                errors = mapOf(404 to "Unknown profile."),
            ) { call ->
                val id = call.path("profile")
                val after = backend.editProfiles { set ->
                    requireProfile(set, id)
                    Profiles.delete(set, id).let { it to it }
                }
                ok(buildJsonObject {
                    put("deleted", true)
                    after.activeId?.let { put("activeId", it) }
                })
            },
            Route(
                "POST", "/api/v1/profiles/{profile}/activate", RouteAccess.Needs(Scope.Layout), "Profiles", "activateProfile",
                "Show a profile",
                "Makes the profile the active one (and its car the selected one): the phone dashboard and Android Auto switch to its pages at once, and the /api/v1/dashboard routes then act on it.",
                params = listOf(profileParam),
                reply = Reply(description = "The active profile", schema = S.ref("ProfileSummary")),
                errors = mapOf(404 to "Unknown profile."),
            ) { call ->
                val id = call.path("profile")
                val p = backend.editCars { space ->
                    requireProfile(space.profiles, id)
                    val next = Cars.activateProfile(space, id, clock())
                    next to next.profiles.profile(id)!!
                }
                ok(profileSummaryJson(p, true))
            },
            Route(
                "GET", "/api/v1/profiles/{profile}/export", RouteAccess.Needs(Scope.Read), "Profiles", "exportProfile",
                "Profile file to share",
                "The profile as the app exports it to share with other users (name, series, model, ECUs and pages): never the VIN nor the user's car. The phone imports it with Profiles › Import.",
                params = listOf(profileParam),
                reply = Reply(description = "Profile file", schema = S.obj("BimmerData profile file", listOf("kind", "name", "dashboard"), true, null,
                    "kind" to S.str(enum = listOf(ProfileExport.KIND)), "name" to S.str(), "series" to S.str(), "model" to S.str(),
                    "ecus" to S.arr(S.str()), "dashboard" to S.ref("Dashboard"))),
                errors = mapOf(404 to "Unknown profile."),
            ) { call ->
                val p = requireProfile(backend.profiles(), call.path("profile"))
                ok(ApiJson.parseToJsonElement(ProfileJson.export(p)))
            },
            Route(
                "GET", "/api/v1/card-types", RouteAccess.Needs(Scope.Read), "Dashboard", "listCardTypes",
                "Card types and how many measurements each takes",
                "What each card type draws and its measurement count.",
                reply = Reply(description = "Card types", schema = S.obj(null, listOf("types"), false, null, "types" to S.arr(S.obj(null, listOf("type", "minMeasurements", "maxMeasurements", "description"), false, null,
                    "type" to S.ref("CardType"), "minMeasurements" to S.int(), "maxMeasurements" to S.int(), "description" to S.str())))),
            ) {
                ok(buildJsonObject {
                    put("types", buildJsonArray {
                        for (t in GaugeType.entries) addJsonObject {
                            val r = Card.measurementRange(t)
                            put("type", t.name); put("minMeasurements", r.first); put("maxMeasurements", r.last); put("description", CARD_TYPE_DOCS[t])
                        }
                    })
                })
            },

            // ------------------------------------------------------------ connection & recording
            Route(
                "POST", "/api/v1/connect", RouteAccess.Needs(Scope.Config), "Connection", "connect",
                "Connect to the car or to the simulation",
                "`usb`: USB diagnostic cable plugged into the phone (the phone may ask the user for USB permission once). `bluetooth`: the Deep OBD Bluetooth adapter chosen in the phone's settings (paired on the phone). `simulation`: built-in simulated car (engine ECU with moving values, 14 ECUs with a few faults), no car needed — ideal to try layouts. Waits up to `waitMs` for the connection to settle and returns the status.",
                body = Body(S.obj(null, emptyList(), false, null,
                    "mode" to S.str("Default: the last mode used (`lastMode` of the settings, what the phone's Connect button uses); 409 when no mode was ever chosen.", enum = listOf("usb", "bluetooth", "simulation")),
                    "waitMs" to S.int(min = 0, max = 20000, default = 6000)), required = false,
                    example = buildJsonObject { put("mode", "simulation") }),
                reply = Reply(description = "Status after connecting", schema = S.ref("Status")),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("mode", "waitMs")
                val mode = b.string("mode")?.let { m -> ConnectMode.entries.firstOrNull { it.name.equals(m, ignoreCase = true) } ?: throw badRequest("'mode' must be usb, bluetooth or simulation") }
                val wait = (b.long("waitMs") ?: 6000L).also { if (it !in 0..20000) throw badRequest("'waitMs' must be 0..20000") }
                if (mode == null && backend.settings().lastMode == null) {
                    throw ApiException(409, "no_mode", "No connection mode chosen yet: send 'mode' (usb, bluetooth or simulation)")
                }
                backend.connect(mode)
                sleep(150)
                waitFor(wait) { backend.status().connection.state.let { it != "connecting" && it != "disconnected" } }
                ok(statusJson())
            },
            Route(
                "POST", "/api/v1/disconnect", RouteAccess.Needs(Scope.Config), "Connection", "disconnect",
                "Disconnect",
                "Stops reading (and any recording) and releases the cable.",
                reply = Reply(description = "Status after disconnecting", schema = S.ref("Status")),
            ) {
                backend.disconnect()
                waitFor(3000) { backend.status().connection.state == "disconnected" }
                ok(statusJson())
            },
            Route(
                "GET", "/api/v1/recording", RouteAccess.Needs(Scope.Read), "Connection", "getRecording",
                "CSV recording state",
                "Whether a recording runs, its file and row count.",
                reply = Reply(description = "Recording state", schema = S.ref("RecordingState")),
            ) { ok(ApiJson.encodeToJsonElement(RecordingInfo.of(backend.recording.value))) },
            Route(
                "POST", "/api/v1/recording/start", RouteAccess.Needs(Scope.Config), "Connection", "startRecording",
                "Start a CSV recording",
                "Records every measurement being read (one row per read cycle) into Documents/BimmerData/ on the phone. Needs a connection.",
                reply = Reply(description = "Recording state", schema = S.ref("RecordingState")),
                errors = mapOf(409 to "Not connected."),
            ) {
                requireConnected()
                backend.startRecording()
                waitFor(1000) { backend.recording.value.recording }
                ok(ApiJson.encodeToJsonElement(RecordingInfo.of(backend.recording.value)))
            },
            Route(
                "POST", "/api/v1/recording/stop", RouteAccess.Needs(Scope.Config), "Connection", "stopRecording",
                "Stop the recording",
                "Closes the CSV file (then listed by GET /api/v1/recordings).",
                reply = Reply(description = "Recording state", schema = S.ref("RecordingState")),
            ) {
                backend.stopRecording()
                waitFor(2000) { !backend.recording.value.recording }
                ok(ApiJson.encodeToJsonElement(RecordingInfo.of(backend.recording.value)))
            },
            Route(
                "POST", "/api/v1/recording/markers", RouteAccess.Needs(Scope.Config), "Connection", "addMarker",
                "Add a marker to the recording",
                "Writes a marker (optional label) into the next CSV row, like the \"Marker\" button.",
                body = Body(S.obj(null, emptyList(), false, null, "label" to S.str("Max 100 characters.", "full throttle 3rd gear")), required = false),
                reply = Reply(description = "Recording state", schema = S.ref("RecordingState")),
                errors = mapOf(409 to "No recording running."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("label")
                val label = b.string("label").orEmpty()
                if (label.length > 100) throw badRequest("'label' is limited to 100 characters")
                if (!backend.recording.value.recording) throw ApiException(409, "not_recording", "No recording is running; POST /api/v1/recording/start first")
                backend.addMarker(label)
                ok(ApiJson.encodeToJsonElement(RecordingInfo.of(backend.recording.value)))
            },
            Route(
                "GET", "/api/v1/recordings", RouteAccess.Needs(Scope.Read), "Connection", "listRecordings",
                "CSV recordings on the phone",
                "Recordings made by this installation of the app, newest first.",
                reply = Reply(description = "Recordings", schema = S.obj(null, listOf("recordings"), false, null, "recordings" to S.arr(S.ref("Recording")))),
            ) {
                ok(buildJsonObject { put("recordings", ApiJson.encodeToJsonElement(backend.recordings().sortedByDescending { it.modifiedMs })) })
            },
            Route(
                "GET", "/api/v1/recordings/{name}", RouteAccess.Needs(Scope.Read), "Connection", "downloadRecording",
                "Download a recording (CSV)",
                "The CSV file: a header row with the measurement labels / units, then one row per read cycle (time, values, markers).",
                params = listOf(Param("name", "path", "File name from GET /api/v1/recordings.", S.str(), example = JsonPrimitive("bimmerdata_2026-11-08_14-03-12.csv"))),
                reply = Reply(description = "CSV file", schema = S.str("CSV text"), contentType = "text/csv; charset=utf-8"),
                errors = mapOf(404 to "No such recording."),
            ) { call ->
                val name = call.path("name")
                val file = backend.recordings().firstOrNull { it.name == name } ?: throw notFound("recording_not_found", "No recording named '$name'; see GET /api/v1/recordings")
                if (file.sizeBytes > MAX_DOWNLOAD) throw ApiException(413, "too_large", "The recording is larger than ${MAX_DOWNLOAD / 1_000_000} MB; share it from the phone instead")
                val bytes = backend.openRecording(file.name)?.use { it.readBytes() } ?: throw notFound("recording_not_found", "Recording '$name' cannot be opened")
                HttpResponse.Bytes(200, "text/csv; charset=utf-8", bytes, mapOf("Content-Disposition" to "attachment; filename=\"${file.name.filter { it.isLetterOrDigit() || it in "._-" }}\""))
            },

            // ------------------------------------------------------------ settings
            Route(
                "GET", "/api/v1/settings", RouteAccess.Needs(Scope.Read), "Settings", "getSettings",
                "App settings",
                "Connection settings and (read-only) API settings. The token is never returned.",
                reply = Reply(description = "Settings", schema = S.ref("Settings")),
            ) { ok(ApiJson.encodeToJsonElement(backend.settings())) },
            Route(
                "PATCH", "/api/v1/settings", RouteAccess.Needs(Scope.Config), "Settings", "updateSettings",
                "Change app settings",
                "Changes `autoConnectOnCable` and / or `bluetoothConnectOnStart`. The Bluetooth adapter is chosen with PUT /api/v1/bluetooth/adapter; GPS, recording and translations have their own routes under /api/v1/settings/. The API settings (enable, port, token, scopes) can only be changed on the phone: sending `api` answers 403.",
                body = Body(S.obj(null, emptyList(), false, null,
                    "autoConnectOnCable" to S.bool(), "bluetoothConnectOnStart" to S.bool()),
                    example = buildJsonObject { put("autoConnectOnCable", true) }),
                reply = Reply(description = "Settings after the change", schema = S.ref("Settings")),
                errors = mapOf(400 to "Unknown field or wrong value.", 403 to "Tried to change API settings (phone only) or the config scope is not enabled."),
            ) { call ->
                val b = call.bodyObject()
                if ("api" in b) throw ApiException(403, "phone_only", "API settings (enable, port, token, scopes) can only be changed on the phone, in Settings › Local API")
                b.allowOnly("autoConnectOnCable", "bluetoothConnectOnStart")
                ok(ApiJson.encodeToJsonElement(backend.updateSettings(SettingsPatch(b.bool("autoConnectOnCable"), b.bool("bluetoothConnectOnStart")))))
            },

            // ------------------------------------------------------------ Telegraf export
            Route(
                "GET", "/api/v1/export/telegraf", RouteAccess.Needs(Scope.Config), "Export", "getTelegrafExport",
                "Telegraf export settings and queue",
                "Where live values are exported (phone → Telegraf → TimescaleDB → Grafana), and the state of the on-phone queue. The token / password is never returned.",
                reply = Reply(description = "Telegraf export", schema = S.ref("TelegrafExport")),
                errors = mapOf(501 to "Not available."),
            ) { ok(ApiJson.encodeToJsonElement(backend.telegraf() ?: throw ApiException(501, "not_available", "The Telegraf export is not available"))) },
            Route(
                "PATCH", "/api/v1/export/telegraf", RouteAccess.Needs(Scope.Config), "Export", "updateTelegrafExport",
                "Change the Telegraf export",
                "Changes `enabled`, `url`, `car` and / or `mode` (`live`: upload on any network; `afterTrip`: keep the points on the phone until an unmetered network such as home Wi-Fi). The authentication (`auth`, `username`, token, password) is set with PUT /api/v1/export/telegraf/auth: sending it here answers 403.",
                body = Body(S.obj(null, emptyList(), false, null,
                    "enabled" to S.bool(), "url" to S.str(example = "http://192.168.1.10:8186/api/v2/write"), "car" to S.str(example = "My car"),
                    "mode" to S.str(enum = listOf("live", "afterTrip"))),
                    example = buildJsonObject { put("enabled", true); put("mode", "afterTrip") }),
                reply = Reply(description = "Telegraf export after the change", schema = S.ref("TelegrafExport")),
                errors = mapOf(400 to "Unknown field or wrong value.", 403 to "Tried to change the authentication (use PUT /api/v1/export/telegraf/auth) or the config scope is not enabled.", 501 to "Not available."),
            ) { call ->
                val b = call.bodyObject()
                listOf("auth", "username", "token", "password").firstOrNull { it in b }?.let {
                    throw ApiException(403, "use_auth_route", "The Telegraf authentication is not changed here: use PUT /api/v1/export/telegraf/auth (write-only secrets)")
                }
                if ("pointsPerSecond" in b) throw badRequest("'pointsPerSecond' no longer exists: every reading is exported, at the read rate")
                b.allowOnly("enabled", "url", "car", "mode")
                val mode = b.string("mode")?.also { if (it != "live" && it != "afterTrip") throw badRequest("'mode' must be live or afterTrip") }
                val url = b.string("url")?.trim()?.also { u ->
                    if (u.isNotEmpty() && !(u.startsWith("http://") || u.startsWith("https://"))) throw badRequest("'url' must start with http:// or https://")
                }
                ok(ApiJson.encodeToJsonElement(backend.updateTelegraf(TelegrafPatch(b.bool("enabled"), url, b.string("car"), mode))))
            },
            Route(
                "POST", "/api/v1/export/telegraf/test", RouteAccess.Needs(Scope.Config), "Export", "testTelegrafExport",
                "Test the connection to Telegraf",
                "Sends an empty write with the stored settings (nothing is stored on the server) and says whether Telegraf accepted it.",
                reply = Reply(description = "Test result", schema = S.obj(null, listOf("ok", "message"), false, null, "ok" to S.bool(), "message" to S.str())),
                errors = mapOf(501 to "Not available."),
            ) { ok(ApiJson.encodeToJsonElement(backend.testTelegraf())) },
            Route(
                "POST", "/api/v1/export/telegraf/send", RouteAccess.Needs(Scope.Config), "Export", "sendTelegrafNow",
                "Upload the waiting points now",
                "Retries at once (also after an authentication error), as far as the mode and the network allow.",
                reply = Reply(description = "Telegraf export", schema = S.ref("TelegrafExport")),
                errors = mapOf(501 to "Not available."),
            ) {
                backend.sendTelegrafNow()
                ok(ApiJson.encodeToJsonElement(backend.telegraf() ?: throw ApiException(501, "not_available", "The Telegraf export is not available")))
            },
        )
    }

    private fun filterMeasurements(all: List<Measurement>, q: String?): List<Measurement> {
        if (q.isNullOrBlank()) return all
        val needle = q.trim().lowercase()
        return all.filter { m ->
            val src = when (val s = m.source) {
                is MeasurementSource.MeasurementTable -> s.resultName
                is MeasurementSource.JobResult -> s.job + " " + s.resultName
                is MeasurementSource.Sensor -> s.sensor + " " + s.field
            }
            m.id.lowercase().contains(needle) || m.label.lowercase().contains(needle) || m.unit.lowercase() == needle || src.lowercase().contains(needle)
        }
    }

    // ================================================================ SSE

    private fun stream(call: Call): HttpResponse {
        val ids = call.queryList("ids")
        val interval = call.queryInt("intervalMs", 250, 50..10000).toLong()
        val ms = ids?.let { resolve(it) }
        if (streams.get() >= MAX_STREAMS) {
            throw ApiException(429, "too_many_streams", "At most $MAX_STREAMS event streams at once; close one first", headers = mapOf("Retry-After" to "5"))
        }
        // Counted only once the stream really runs (the header write may still fail before).
        return HttpResponse.Stream(200, "text/event-stream; charset=utf-8", mapOf("X-Accel-Buffering" to "no")) { out ->
            streams.incrementAndGet()
            val lease = ms?.let { reads.acquire(it) }
            try {
                fun send(text: String) {
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                send("retry: 3000\n\n")
                val sent = HashMap<String, Long>()
                var lastStatus = 0L
                var lastPing = clock()
                while (!stopped) {
                    val now = clock()
                    if (now - lastStatus >= 5000) {
                        send("event: status\ndata: " + ApiJson.encodeToString(JsonElement.serializer(), statusJson()) + "\n\n")
                        lastStatus = now
                    }
                    val snapshot = backend.samples.value
                    val wanted = ids ?: backend.status().measurementsRead
                    val changed = wanted.mapNotNull { id -> snapshot[id]?.takeIf { it.timestampMs > (sent[id] ?: Long.MIN_VALUE) }?.let { id to it } }
                    if (changed.isNotEmpty()) {
                        val data = buildJsonObject {
                            put("t", now)
                            put("values", buildJsonObject {
                                for ((id, s) in changed) {
                                    sent[id] = s.timestampMs
                                    put(id, buildJsonObject {
                                        s.value?.takeUnless { it.isNaN() || it.isInfinite() }?.let { put("v", it) }
                                        s.text?.let { put("text", it) }
                                        put("t", s.timestampMs)
                                    })
                                }
                            })
                        }
                        send("event: samples\ndata: " + ApiJson.encodeToString(JsonElement.serializer(), data) + "\n\n")
                        lastPing = now
                    } else if (now - lastPing >= 15_000) {
                        send(": ping\n\n")
                        lastPing = now
                    }
                    sleep(interval)
                }
            } catch (_: IOException) {
                // client went away
            } catch (_: InterruptedException) {
                // server stopping
            } finally {
                lease?.close()
                streams.decrementAndGet()
            }
        }
    }

    companion object {
        const val FRESH_MS = 5_000L
        const val MAX_STREAMS = 4
        const val MAX_DOWNLOAD = 50_000_000L

        val CARD_TYPE_DOCS = mapOf(
            GaugeType.Dial to "270° arc with the value in the middle; for temperatures, rpm, pressures. Uses min / max for the scale and warnBelow / warnAbove for the amber zone.",
            GaugeType.BipolarBar to "Bars centred on 0, one row per measurement (1–4); for fuel trims per bank or other signed corrections. Scale ±25 by default.",
            GaugeType.BigNumber to "One big value with unit, label and the session min / max; good for a 2×2 hero card.",
            GaugeType.Trend to "Value with a curve of the last `windowSeconds` (5 s to 60 min, default 45 s); good for slowly changing values (temperatures, voltages).",
            GaugeType.MultiValue to "2–3 values side by side with short labels (lambda B1 · B2); use channelLabels to name them.",
            GaugeType.Status to "Text or state results (e.g. switch states, ECU text results).",
        )
    }
}
