/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.dashboard.GridLimits
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Tiny JSON Schema builder for the OpenAPI document. */
internal object S {
    fun ref(name: String) = JsonObject(mapOf("\$ref" to JsonPrimitive("#/components/schemas/$name")))

    private fun base(type: String, description: String?, extra: Map<String, JsonElement> = emptyMap()): JsonObject = buildJsonObject {
        put("type", type)
        description?.let { put("description", it) }
        extra.forEach { (k, v) -> put(k, v) }
    }

    fun str(description: String? = null, example: String? = null, enum: List<String>? = null, format: String? = null, default: String? = null) =
        base("string", description, buildMap {
            example?.let { put("example", JsonPrimitive(it)) }
            enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
            format?.let { put("format", JsonPrimitive(it)) }
            default?.let { put("default", JsonPrimitive(it)) }
        })

    fun int(description: String? = null, min: Long? = null, max: Long? = null, example: Long? = null, default: Long? = null, format: String? = null) =
        base("integer", description, buildMap {
            min?.let { put("minimum", JsonPrimitive(it)) }
            max?.let { put("maximum", JsonPrimitive(it)) }
            example?.let { put("example", JsonPrimitive(it)) }
            default?.let { put("default", JsonPrimitive(it)) }
            format?.let { put("format", JsonPrimitive(it)) }
        })

    fun num(description: String? = null, example: Double? = null) =
        base("number", description, buildMap { example?.let { put("example", JsonPrimitive(it)) } })

    fun bool(description: String? = null, default: Boolean? = null) =
        base("boolean", description, buildMap { default?.let { put("default", JsonPrimitive(it)) } })

    fun arr(items: JsonObject, description: String? = null, minItems: Int? = null, maxItems: Int? = null) =
        base("array", description, buildMap {
            put("items", items)
            minItems?.let { put("minItems", JsonPrimitive(it)) }
            maxItems?.let { put("maxItems", JsonPrimitive(it)) }
        })

    fun obj(description: String? = null, required: List<String> = emptyList(), additional: Boolean? = false, example: JsonElement? = null, vararg props: Pair<String, JsonObject>) =
        buildJsonObject {
            put("type", "object")
            description?.let { put("description", it) }
            if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            put("properties", JsonObject(props.toMap()))
            additional?.let { put("additionalProperties", it) }
            example?.let { put("example", it) }
        }

    fun map(values: JsonObject, description: String? = null) = buildJsonObject {
        put("type", "object")
        description?.let { put("description", it) }
        put("additionalProperties", values)
    }

    /** Adds a description to a `$ref` (OpenAPI 3.1 allows siblings of `$ref`). */
    fun refd(name: String, description: String) = JsonObject(ref(name) + ("description" to JsonPrimitive(description)))
}

/** Builds `openapi.json` (OpenAPI 3.1) from the route table plus the shared schemas below. */
internal object OpenApi {
    /** [scheme] is used when [serverUrl] is null (`http` or `https`, as set on the phone). */
    fun document(routes: List<Route>, serverUrl: String?, appVersion: String, scheme: String = "http"): JsonObject = buildJsonObject {
        put("openapi", "3.1.0")
        put("info", buildJsonObject {
            put("title", "BimmerData API")
            put("version", "1.0.0 (app $appVersion)")
            put("summary", "Live data, dashboard layouts and configuration of the BimmerData app, over the phone's local network.")
            put("description", INTRO)
            put("license", buildJsonObject { put("name", "GPL-3.0-or-later"); put("identifier", "GPL-3.0-or-later") })
        })
        put("servers", buildJsonArray {
            add(buildJsonObject {
                put("url", serverUrl ?: "$scheme://{host}:{port}")
                put("description", if (serverUrl != null) "The phone that served this document" else "The phone running BimmerData (address and port are shown in Settings › Local API)")
                if (serverUrl == null) put("variables", buildJsonObject {
                    put("host", buildJsonObject { put("default", "192.168.1.20") })
                    put("port", buildJsonObject { put("default", "8765") })
                })
            })
        })
        put("security", buildJsonArray { add(buildJsonObject { put("bearerAuth", JsonArray(emptyList())) }) })
        put("tags", buildJsonArray {
            for ((name, desc) in TAGS) add(buildJsonObject { put("name", name); put("description", desc) })
        })
        put("paths", paths(routes))
        put("components", buildJsonObject {
            put("securitySchemes", buildJsonObject {
                put("bearerAuth", buildJsonObject {
                    put("type", "http")
                    put("scheme", "bearer")
                    put("description", "The token shown in BimmerData › Settings › Local API, sent as `Authorization: Bearer <token>`. Each operation also needs one scope (read, layout or config) that the user enables in Settings; see `x-required-scope`.")
                })
            })
            put("schemas", schemas())
        })
    }

    private fun paths(routes: List<Route>): JsonObject {
        val byPath = LinkedHashMap<String, MutableList<Route>>()
        routes.forEach { byPath.getOrPut(it.path) { ArrayList() } += it }
        return buildJsonObject {
            for ((path, list) in byPath) put(path, buildJsonObject {
                for (r in list) put(r.method.lowercase(), operation(r))
            })
        }
    }

    private fun operation(r: Route): JsonObject = buildJsonObject {
        put("operationId", r.operationId)
        put("tags", JsonArray(listOf(JsonPrimitive(r.tag))))
        put("summary", r.summary)
        val scopeText = when (val a = r.access) {
            RouteAccess.Public -> "No token needed."
            RouteAccess.AnyToken -> "Needs a valid token (any scope)."
            is RouteAccess.Needs -> "Needs scope `${a.scope.id}`."
        }
        put("description", r.description + "\n\n" + scopeText)
        when (val a = r.access) {
            RouteAccess.Public -> put("security", JsonArray(emptyList()))
            is RouteAccess.Needs -> put("x-required-scope", a.scope.id)
            RouteAccess.AnyToken -> {}
        }
        if (r.params.isNotEmpty()) put("parameters", buildJsonArray {
            for (p in r.params) add(buildJsonObject {
                put("name", p.name)
                put("in", p.location)
                put("required", p.required)
                put("description", p.description)
                put("schema", p.schema)
                p.example?.let { put("example", it) }
            })
        })
        r.body?.let { b ->
            put("requestBody", buildJsonObject {
                put("required", b.required)
                b.description?.let { put("description", it) }
                put("content", buildJsonObject {
                    put("application/json", buildJsonObject {
                        put("schema", b.schema)
                        b.example?.let { put("example", it) }
                    })
                })
            })
        }
        put("responses", buildJsonObject {
            put(r.reply.status.toString(), buildJsonObject {
                put("description", r.reply.description)
                r.reply.schema?.let { schema ->
                    put("content", buildJsonObject {
                        put(r.reply.contentType.substringBefore(';'), buildJsonObject {
                            put("schema", schema)
                            r.reply.example?.let { put("example", it) }
                        })
                    })
                }
            })
            val errors = LinkedHashMap<Int, String>()
            errors.putAll(r.errors)
            if (r.access != RouteAccess.Public) {
                errors.putIfAbsent(401, "Missing or wrong token (`Authorization: Bearer <token>`).")
                val needs = r.access
                if (needs is RouteAccess.Needs) errors.putIfAbsent(403, "The token is valid but the scope `${needs.scope.id}` is not enabled in Settings › Local API.")
            }
            errors.putIfAbsent(429, "Too many requests (about 20/s per client) or too many wrong tokens; wait `Retry-After` seconds.")
            for ((status, desc) in errors.toSortedMap()) put(status.toString(), buildJsonObject {
                put("description", desc)
                put("content", buildJsonObject {
                    put("application/json", buildJsonObject { put("schema", S.ref("Error")) })
                })
            })
        })
    }

    private val TAGS = listOf(
        "General" to "Discovery and status.",
        "Live data" to "ECUs, measurements and live values. Values are only read while someone shows or requests them: the K-line is slow (≈ 10–40 reads/s in total), so request only what you need.",
        "Dashboard" to "Card layouts of the phone and of Android Auto. Changes are saved at once; Android Auto follows them live.",
        "Connection" to "Connect to the car (USB K+DCAN cable) or to the built-in simulated ECU, and record CSV logs.",
        "Settings" to "App settings that may be changed remotely. API settings (enable, port, token, scopes) can only be changed on the phone.",
        "Export" to "Export of the live values to Telegraf (line protocol over HTTP), which writes them to TimescaleDB for Grafana. See docs/TELEGRAF.md in the BimmerData repository.",
    )

    private val INTRO = """
BimmerData reads live data from BMW ECUs (E-series, K+DCAN cable) and shows it on card dashboards on the phone and in Android Auto. This API lets scripts and AI agents read values, build dashboard layouts and manage the configuration over the local network.

## Getting started
1. On the phone: Settings › Local API › enable, pick the scopes, copy the base URL and the token.
2. Send `Authorization: Bearer <token>` with every request (except this document). When HTTPS is on (Settings › Local API › HTTPS), use `https://` and trust the phone's own certificate: pin its public key (`curl --pinnedpubkey 'sha256//…'`) or trust the exported certificate file; both are shown on the phone.
3. `GET /api/v1/status` — is the app connected? If not, `POST /api/v1/connect` with `{"mode":"simulation"}` (no car needed) or `{"mode":"usb"}` / `{"mode":"bluetooth"}` (needs scope `config`).
4. Discover data: `GET /api/v1/ecus`, then `GET /api/v1/ecus/{sgbd}/measurements?q=temp`. A measurement id looks like `msv70/0x4402` (`<sgbd>/<key>`).
5. Read values: `POST /api/v1/values/read` with `{"ids":["msv70/0x4402"]}` waits for fresh samples; add `"seconds":10` to sample for a while and get min / max / mean.

## Building dashboards
- `GET /api/v1/dashboard` returns the whole layout: `phonePages` (portrait phone, grid up to ${GridLimits.Phone.maxColumns}×${GridLimits.Phone.maxRows}, ${GridLimits.Phone.maxCards} cards) and `carPages` (Android Auto, landscape, grid up to ${GridLimits.Car.maxColumns}×${GridLimits.Car.maxRows}, at most ${GridLimits.Car.maxCards} big cards).
- A page is a `columns × rows` grid; a card covers `colSpan × rowSpan` cells from its top-left cell (`col`, `row`, 0-based). Cards may not overlap or leave the grid.
- Card types (`GET /api/v1/card-types`): ${GaugeType.entries.joinToString { it.name }}. Most take one measurement; `BipolarBar` takes 1–4 (e.g. fuel trims per bank), `MultiValue` 2–3.
- Small edits: `POST …/pages`, `POST …/pages/{page}/cards`, `PATCH …/cards/{card}`, `DELETE …`. Whole layout: `PUT /api/v1/dashboard` (validate first with `POST /api/v1/dashboard/validate`). Errors say exactly what is wrong (`error.details` lists each problem with its JSON path).
- Look at what you built: `GET /api/v1/dashboard/{target}/pages/{page}/render.png` returns a PNG of the page drawn like on the phone or the car screen, with live values.
- `{page}` is a page id or its 0-based index; `{target}` is `phone` or `car`.

## Errors
Every error is `{"error":{"code":"…","message":"…","details":…}}` with a meaningful HTTP status (400 bad input, 401 no / wrong token, 403 scope not enabled, 404 unknown id, 409 conflict such as overlapping cards, 422 invalid layout, 429 rate limited).

## Safety
The API never writes to ECUs. Diagnostic operations (fault memory) will need the `diag` scope, a confirmation on the phone for each call, and will be refused while driving.
""".trim()

    // ---------------------------------------------------------------- schemas

    private fun schemas(): JsonObject = buildJsonObject {
        put("Error", S.obj(
            "Error answer of every operation.", listOf("error"), null, null,
            "error" to S.obj(null, listOf("code", "message"), null, null,
                "code" to S.str("Stable machine-readable code, e.g. `card_overlap`, `page_not_found`, `invalid_layout`, `insufficient_scope`.", "card_overlap"),
                "message" to S.str("Human-readable explanation with the values involved and, when possible, how to fix it."),
                "details" to buildJsonObject { put("description", "Optional: for `invalid_layout` / `invalid_card`, an array of LayoutIssue.") },
            ),
        ))
        put("LayoutIssue", S.obj(
            "One problem of a layout.", listOf("path", "code", "message"), false, null,
            "path" to S.str("Where: e.g. `phonePages[0].cards[2].measurementIds`.", "phonePages[0].cards[2]"),
            "code" to S.str("e.g. card_overlap, out_of_grid, measurement_count, invalid_grid, duplicate_id, too_many_cards, unknown_measurement (warning)."),
            "message" to S.str("Explanation."),
        ))
        put("Status", S.obj(
            "Connection, acquisition and recording state.", listOf("connection", "acquisition", "recording"), false, null,
            "connection" to S.obj(null, listOf("state"), false, null,
                "state" to S.str("disconnected, connecting, connected, failed. One connection for the whole app: Tool32 and fault jobs run on it between two read cycles.", enum = listOf("disconnected", "connecting", "connected", "failed")),
                "mode" to S.str("usb (K+DCAN cable), bluetooth (Deep OBD adapter) or simulation", enum = listOf("usb", "bluetooth", "simulation")),
                "label" to S.str("Interface name, e.g. \"Simulation (E90 with MSV70)\""),
                "message" to S.str("Why it failed."),
            ),
            "acquisition" to S.obj(null, listOf("state", "readsPerSecond", "errorCount"), false, null,
                "state" to S.str("Read loop: Stopped, Connecting, Running, Error", enum = listOf("Stopped", "Connecting", "Running", "Error")),
                "readsPerSecond" to S.num("Complete read cycles per second over the last seconds.", 8.4),
                "errorCount" to S.int("Read errors since connecting."),
                "message" to S.str("Engine message, e.g. the ECU name."),
            ),
            "recording" to S.ref("RecordingState"),
            "measurementsRead" to S.arr(S.str(), "Measurement ids read in each cycle right now (phone + Android Auto + recorder + API)."),
            "apiReads" to S.arr(S.str(), "Of those, the ids requested through this API (timed reads, watches, streams)."),
            "driving" to S.bool("True while the car is known to be moving."),
            "appVersion" to S.str(example = "0.1.0"),
        ))
        put("RecordingState", S.obj(
            "CSV recording state.", listOf("recording", "rows"), false, null,
            "recording" to S.bool(),
            "fileName" to S.str("CSV file being written (in Documents/BimmerData/).", "bimmerdata_2026-11-08_14-03-12.csv"),
            "startedAtMs" to S.int("Start, epoch milliseconds.", format = "int64"),
            "rows" to S.int("Rows written so far.", format = "int64"),
            "error" to S.str("Why the last recording stopped by itself."),
        ))
        put("Ecu", S.obj(
            "An ECU description file (SGBD) that has live measurements.", listOf("sgbd", "source"), false, null,
            "sgbd" to S.str("SGBD name, lower case, no extension.", "msv70"),
            "source" to S.str("`detected` = identified on the connected car; `catalog` = present in the imported ECU folder.", enum = listOf("detected", "catalog")),
        ))
        put("Measurement", S.obj(
            "A live value that can be read, logged or shown on a card.", listOf("id", "sgbd", "label", "unit", "source"), false, null,
            "id" to S.str("Stable id used everywhere (cards, values, CSV): `<sgbd>/<key>`.", "msv70/0x4402"),
            "sgbd" to S.str(example = "msv70"),
            "label" to S.str("Display label from the SGBD.", "Öltemperatur"),
            "unit" to S.str("Unit from the SGBD (may be empty).", "°C"),
            "min" to S.num("Plausible minimum, when known (gauge scales)."),
            "max" to S.num("Plausible maximum, when known."),
            "source" to S.obj("How it is read.", listOf("kind"), false, null,
                "kind" to S.str("`table`: fast path (MESSWERTETAB row, read in blocks); `job`: generic EDIABAS job result (slower).", enum = listOf("table", "job")),
                "tableId" to S.str("table: row id", "0x4402"),
                "resultName" to S.str(example = "STAT_OELTEMPERATUR_WERT"),
                "job" to S.str("job: job name"),
                "args" to S.str("job: arguments"),
                "set" to S.int("job: result set"),
            ),
        ))
        put("Value", S.obj(
            "Latest value of a measurement.", listOf("id", "status"), false, null,
            "id" to S.str(example = "msv70/0x4402"),
            "label" to S.str(example = "Öltemperatur"),
            "unit" to S.str(example = "°C"),
            "value" to S.num("Numeric value (absent for text results or no data).", 92.4),
            "text" to S.str("Text result (states)."),
            "timestampMs" to S.int("When it was read, epoch ms.", format = "int64"),
            "ageMs" to S.int("How old the value is.", format = "int64"),
            "status" to S.str("`fresh` (≤ 5 s old), `stale` (older: the value is not being read any more), `no_data` (never read).", enum = listOf("fresh", "stale", "no_data")),
        ))
        put("Series", S.obj(
            "Samples of one measurement over time.", listOf("id", "points"), false, null,
            "id" to S.str(),
            "label" to S.str(),
            "unit" to S.str(),
            "stats" to S.obj("Over the numeric points.", listOf("count"), false, null,
                "count" to S.int(), "min" to S.num(), "max" to S.num(), "mean" to S.num(), "last" to S.num(),
            ),
            "points" to S.arr(S.obj(null, listOf("t"), false, null,
                "t" to S.int("Epoch ms", format = "int64"),
                "v" to S.num("Numeric value"),
                "text" to S.str("Text value"),
            )),
        ))
        put("Dashboard", S.obj(
            "The whole layout. Unknown fields are rejected.", listOf("phonePages", "carPages"), false, null,
            "formatVersion" to S.int("Layout format, currently 1.", 1, 1, default = 1),
            "phonePages" to S.arr(S.ref("Page"), "Pages of the phone dashboard (portrait), in swipe order. At least one."),
            "carPages" to S.arr(S.ref("Page"), "Pages of the Android Auto dashboard (landscape ~1280×480). At least one."),
        ))
        put("Page", S.obj(
            "One screen of cards on a columns × rows grid that fills the screen.", listOf("name", "columns", "rows"), false, null,
            "id" to S.str("Unique id (any string; omit when creating to get one).", "a1b2c3d4e5"),
            "name" to S.str("Shown as the page title.", "Engine"),
            "columns" to S.int("Grid columns: phone 1..${GridLimits.Phone.maxColumns}, car 1..${GridLimits.Car.maxColumns}.", 1, 4, 2),
            "rows" to S.int("Grid rows: phone 1..${GridLimits.Phone.maxRows}, car 1..${GridLimits.Car.maxRows}.", 1, 8, 6),
            "cards" to S.arr(S.ref("Card"), "Cards, in reading order. Phone ≤ ${GridLimits.Phone.maxCards}, car ≤ ${GridLimits.Car.maxCards}."),
        ))
        val cardProps = arrayOf(
            "id" to S.str("Unique id in the dashboard; omit when creating.", "k3j4h5g6f7"),
            "type" to S.ref("CardType"),
            "measurementIds" to S.arr(S.str(example = "msv70/0x4402"), "Measurement ids shown by the card: 1 for most types, 1–4 for BipolarBar, 2–3 for MultiValue.", 1, 4),
            "title" to S.str("Card title; default = the first measurement's label.", "Oil"),
            "channelLabels" to S.arr(S.str(), "Short label per measurement for multi-value cards (e.g. [\"B1\",\"B2\"]); default derived from the labels."),
            "min" to S.num("Scale minimum; default from the measurement or its unit."),
            "max" to S.num("Scale maximum (> min)."),
            "warnBelow" to S.num("Card turns amber below this value."),
            "warnAbove" to S.num("Card turns amber above this value.", 120.0),
            "decimals" to S.int("Decimals shown, 0..4; default from the unit.", 0, 4),
            "windowSeconds" to S.int("Trend cards: seconds of history shown by the curve, ${TrendWindow.MIN_SECONDS}..${TrendWindow.MAX_SECONDS}; default ${TrendWindow.DEFAULT_SECONDS}. Ignored by other card types.", TrendWindow.MIN_SECONDS.toLong(), TrendWindow.MAX_SECONDS.toLong(), 300),
            "col" to S.int("Left column, 0-based.", 0, 3, 0, 0),
            "row" to S.int("Top row, 0-based.", 0, 7, 0, 0),
            "colSpan" to S.int("Width in cells.", 1, 4, 1, 1),
            "rowSpan" to S.int("Height in cells.", 1, 8, 2, 1),
        )
        put("Card", S.obj(
            "A card placed on the grid: what it shows and where. Omitted optional fields = automatic.", listOf("type", "measurementIds"), false,
            buildJsonObject {
                put("type", "Dial"); put("measurementIds", JsonArray(listOf(JsonPrimitive("msv70/0x4402"))))
                put("title", "Oil"); put("warnAbove", 120); put("col", 0); put("row", 0); put("colSpan", 1); put("rowSpan", 2)
            },
            *cardProps,
        ))
        put("CardPatch", S.obj(
            "Fields to change on a card (JSON merge: absent = unchanged, null = back to automatic). The card must still fit where it is.", emptyList(), false,
            buildJsonObject { put("warnAbove", 130); put("rowSpan", 2) },
            *cardProps.filter { it.first != "id" }.toTypedArray(),
        ))
        put("CardType", S.str(
            "Dial: 270° arc with the value (oil, coolant, rpm). BipolarBar: bars centred on 0, one row per measurement (fuel trims). " +
                "BigNumber: hero value with session min / max. Trend: value with a sparkline of the last minute. " +
                "MultiValue: 2–3 values side by side (lambda B1 · B2). Status: text or state result.",
            enum = GaugeType.entries.map { it.name },
        ))
        put("Preset", S.obj(
            "A ready-made arrangement of a page; applying it re-lays the cards in reading order into its slots (extra cards are dropped).", listOf("id", "target", "columns", "rows", "slots"), false, null,
            "id" to S.str(example = "big_left"),
            "target" to S.str(enum = listOf("phone", "car")),
            "columns" to S.int(), "rows" to S.int(),
            "slots" to S.arr(S.obj(null, listOf("col", "row", "colSpan", "rowSpan"), false, null,
                "col" to S.int(), "row" to S.int(), "colSpan" to S.int(), "rowSpan" to S.int(),
            )),
        ))
        put("Recording", S.obj(
            "A CSV recording in Documents/BimmerData/ (made by this installation of the app).", listOf("name", "sizeBytes", "modifiedMs"), false, null,
            "name" to S.str(example = "bimmerdata_2026-11-08_14-03-12.csv"),
            "sizeBytes" to S.int(format = "int64"),
            "modifiedMs" to S.int(format = "int64"),
        ))
        put("TelegrafExport", S.obj(
            "Telegraf export (secrets are never returned).", listOf("enabled", "url", "auth", "username", "secretSet", "car", "pointsPerSecond", "mode", "state"), false, null,
            "enabled" to S.bool(), "url" to S.str("Telegraf listener URL.", "http://192.168.1.10:8186/api/v2/write"),
            "auth" to S.str("Authentication (phone only).", enum = listOf("none", "token", "basic")), "username" to S.str("User for basic auth."),
            "secretSet" to S.bool("A token / password is stored, encrypted, on the phone."), "car" to S.str("Value of the `car` tag."),
            "pointsPerSecond" to S.int("At most this many points per measurement and second.", 1, 5), "mode" to S.str(enum = listOf("live", "afterTrip")),
            "state" to S.obj("Queue on the phone.", listOf("phase", "queuedPoints", "queuedBytes", "droppedPoints", "sentPoints"), false, null,
                "phase" to S.str(enum = listOf("off", "idle", "waitingForNetwork", "waitingForUnmetered", "sending", "retrying", "authFailed")),
                "queuedPoints" to S.int(), "queuedBytes" to S.int(), "droppedPoints" to S.int("Lost because the queue was full or refused by Telegraf."),
                "sentPoints" to S.int("Since the app started."), "lastSuccessMs" to S.int(format = "int64"), "lastError" to S.str()),
        ))
        put("Settings", S.obj(
            "App settings.", listOf("autoConnectOnCable", "recordingFolder", "api"), false, null,
            "lastMode" to S.str("Mode of the last connection (read-only), used by `POST /connect` without a mode; absent until the first connection.", enum = listOf("usb", "bluetooth", "simulation")),
            "autoConnectOnCable" to S.bool("Connect over USB as soon as the K+DCAN cable is plugged in (off by default)."),
            "recordingFolder" to S.str("Where CSV recordings go (read-only).", "Documents/BimmerData/"),
            "bluetoothAdapter" to S.obj("Deep OBD Bluetooth adapter chosen on the phone (read-only); absent when none.", listOf("address"), false, null,
                "name" to S.str(example = "Deep OBD BMW"), "address" to S.str(example = "00:11:22:33:44:55")),
            "bluetoothConnectOnStart" to S.bool("Connect to the Bluetooth adapter when the app starts."),
            "api" to S.obj("Read-only here: change them on the phone.", listOf("enabled", "port", "scopes", "https"), false, null,
                "enabled" to S.bool(), "port" to S.int(), "scopes" to S.arr(S.str(enum = Scope.entries.map { it.id })),
                "https" to S.bool("HTTPS only, with the phone's self-signed certificate."),
                "certificateSha256" to S.str("SHA-256 of the certificate to trust (HTTPS on).", "3F:A2:…"),
                "publicKeyPin" to S.str("Server public-key pin for `curl --pinnedpubkey` (HTTPS on).", "sha256//…"),
            ),
        ))
    }
}
