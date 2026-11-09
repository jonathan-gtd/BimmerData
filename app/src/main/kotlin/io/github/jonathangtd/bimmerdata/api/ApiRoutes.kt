/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpRequest
import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Who may call a route. */
sealed interface RouteAccess {
    /** No token needed (only the OpenAPI document). */
    data object Public : RouteAccess

    /** Any valid token, whatever its scopes. */
    data object AnyToken : RouteAccess

    data class Needs(val scope: Scope) : RouteAccess
}

/** A documented parameter, used both to read requests and to write the OpenAPI document. */
class Param(
    val name: String,
    /** "path" or "query". */
    val location: String,
    val description: String,
    val schema: JsonObject,
    val required: Boolean = location == "path",
    val example: JsonElement? = null,
)

/** Documented request body ([contentType] is JSON unless it is an upload). */
class Body(
    val schema: JsonObject,
    val example: JsonElement? = null,
    val required: Boolean = true,
    val description: String? = null,
    val contentType: String = "application/json",
)

/**
 * A route that takes a large raw body (file upload): the server does not buffer it but hands it
 * over as a stream ([Call.bodyStream]), up to [maxBytes] (other routes stay limited to 1 MB).
 */
class Upload(val maxBytes: Long)

/** Documented success response. */
class Reply(
    val status: Int = 200,
    val description: String,
    val schema: JsonObject? = null,
    val contentType: String = HttpServer.JSON,
    val example: JsonElement? = null,
)

/**
 * One operation of the REST API: its documentation (turned into `openapi.json`) and its handler.
 * [path] uses `{name}` segments, e.g. `/api/v1/dashboard/{target}/pages/{page}`.
 */
class Route(
    val method: String,
    val path: String,
    val access: RouteAccess,
    val tag: String,
    val operationId: String,
    val summary: String,
    val description: String,
    val params: List<Param> = emptyList(),
    val body: Body? = null,
    val reply: Reply,
    /** Error statuses this route can answer, besides 401 / 403 / 429 which every protected route has. */
    val errors: Map<Int, String> = emptyMap(),
    /** Set for upload routes: the body is streamed, not held in memory. */
    val upload: Upload? = null,
    val handler: (Call) -> HttpResponse,
) {
    internal val segments: List<String> = path.trim('/').split('/')

    /** Path parameters if [requestPath] matches, else null. */
    internal fun match(requestPath: List<String>): Map<String, String>? {
        if (requestPath.size != segments.size) return null
        val out = HashMap<String, String>()
        for (i in segments.indices) {
            val s = segments[i]
            if (s.startsWith("{") && s.endsWith("}")) {
                if (requestPath[i].isEmpty()) return null
                out[s.substring(1, s.length - 1)] = requestPath[i]
            } else if (s != requestPath[i]) return null
        }
        return out
    }
}

/** A request being handled: typed access to its parameters and body, errors as [ApiException]. */
class Call(val request: HttpRequest, private val pathParams: Map<String, String>, val access: ApiAccess?) {
    fun path(name: String): String = pathParams[name] ?: throw badRequest("Missing path parameter '$name'")

    fun query(name: String): String? = request.queryParam(name)?.takeIf { it.isNotEmpty() }

    fun queryList(name: String): List<String>? =
        request.query[name]?.flatMap { it.split(',') }?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    fun queryInt(name: String, default: Int, range: IntRange): Int {
        val raw = query(name) ?: return default
        val v = raw.toIntOrNull() ?: throw badRequest("Query parameter '$name' must be an integer (got '$raw')")
        if (v !in range) throw badRequest("Query parameter '$name' must be ${range.first}..${range.last} (got $v)")
        return v
    }

    fun queryBool(name: String, default: Boolean): Boolean = when (query(name)?.lowercase()) {
        null -> default
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> throw badRequest("Query parameter '$name' must be true or false")
    }

    /** The raw body as a stream (upload routes get it straight from the socket). */
    fun bodyStream(): java.io.InputStream = request.bodyStream ?: java.io.ByteArrayInputStream(request.body)

    /** Length of the body (Content-Length). */
    val bodyLength: Long get() = request.bodyLength

    /** The body as a JSON object; an empty body is `{}`. */
    fun bodyObject(): JsonObject {
        val text = request.bodyText().trim()
        if (text.isEmpty()) return JsonObject(emptyMap())
        val el = try {
            ApiJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw badRequest("Body is not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        return el as? JsonObject ?: throw badRequest("Body must be a JSON object")
    }

    fun <T> decode(strategy: DeserializationStrategy<T>, element: JsonElement = bodyObject(), what: String = "body"): T = try {
        ApiJson.decodeFromJsonElement(strategy, element)
    } catch (e: SerializationException) {
        throw badRequest("Invalid $what: ${e.message?.lineSequence()?.joinToString(" ")?.take(400)}")
    } catch (e: IllegalArgumentException) {
        throw badRequest("Invalid $what: ${e.message?.take(400)}")
    }
}

// ---- helpers to read body fields with precise errors ----

internal fun JsonObject.allowOnly(vararg keys: String) {
    val unknown = this.keys - keys.toSet()
    if (unknown.isNotEmpty()) throw badRequest("Unknown field(s) ${unknown.joinToString { "'$it'" }}; allowed: ${keys.joinToString()}")
}

internal fun JsonObject.string(key: String): String? = this[key]?.let {
    if (it is JsonPrimitive && it.isString) it.content else if (it is kotlinx.serialization.json.JsonNull) null else throw badRequest("'$key' must be a string")
}

internal fun JsonObject.int(key: String): Int? = this[key]?.let {
    if (it is kotlinx.serialization.json.JsonNull) null
    else (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: throw badRequest("'$key' must be an integer")
}

internal fun JsonObject.long(key: String): Long? = this[key]?.let {
    if (it is kotlinx.serialization.json.JsonNull) null
    else (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull ?: throw badRequest("'$key' must be an integer")
}

internal fun JsonObject.bool(key: String): Boolean? = this[key]?.let {
    if (it is kotlinx.serialization.json.JsonNull) null
    else (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: throw badRequest("'$key' must be true or false")
}

internal fun JsonObject.stringList(key: String): List<String>? = this[key]?.let { el ->
    if (el is kotlinx.serialization.json.JsonNull) return@let null
    val arr = el as? kotlinx.serialization.json.JsonArray ?: throw badRequest("'$key' must be an array of strings")
    arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw badRequest("'$key' must be an array of strings") }
}

internal fun json(status: Int, element: JsonElement, headers: Map<String, String> = emptyMap()): HttpResponse =
    HttpResponse.Bytes(status, HttpServer.JSON, ApiJson.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8), headers)

internal fun errorResponse(e: ApiException): HttpResponse {
    val err = kotlinx.serialization.json.buildJsonObject {
        put("error", kotlinx.serialization.json.buildJsonObject {
            put("code", JsonPrimitive(e.code))
            put("message", JsonPrimitive(e.message ?: e.code))
            e.details?.let { put("details", it) }
        })
    }
    return json(e.status, err, e.headers)
}

internal fun JsonElement.objectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
