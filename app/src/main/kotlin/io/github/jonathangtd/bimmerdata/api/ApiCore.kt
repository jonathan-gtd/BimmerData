/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/*
 * Pure JVM core of the embedded REST API (no Android class): the same code runs in the app and in
 * the JVM tests. Android glue lives in the `api.android` package.
 */

/** What a token may do. Each route needs one scope; the user picks the granted ones in Settings. */
enum class Scope(val id: String, val description: String) {
    Read("read", "Read live values, ECUs, measurements, the dashboard layout, recordings and settings; render pages."),
    Layout("layout", "Change the dashboard layout (pages, cards, profiles)."),
    Config("config", "Connect / disconnect, start and stop recordings, change app settings."),

    /**
     * Reserved for fault memory and other diagnostic jobs. Not granted by any build yet: every
     * route that writes to the car will also need a per-call confirmation on the phone and is
     * refused while driving (see [CarWriteGuard]).
     */
    Diag("diag", "Reserved: fault memory and diagnostic jobs (not available yet).");

    companion object {
        fun fromId(id: String): Scope? = entries.firstOrNull { it.id == id }
    }
}

/** Current credentials of the API, read on every request so changes in Settings apply at once. */
data class ApiAccess(val token: String, val scopes: Set<Scope>)

/** An error answered as `{"error":{"code","message","details"}}` with HTTP [status]. */
class ApiException(
    val status: Int,
    val code: String,
    message: String,
    val details: JsonElement? = null,
    val headers: Map<String, String> = emptyMap(),
) : Exception(message)

internal fun badRequest(message: String, details: JsonElement? = null) = ApiException(400, "bad_request", message, details)
internal fun notFound(code: String, message: String) = ApiException(404, code, message)

/** JSON settings of the API: strict on input (unknown keys are errors), nulls omitted on output. */
val ApiJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}

object ApiTokens {
    private val random = SecureRandom()

    /** "bd_" + 43 url-safe characters (256 random bits). */
    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return "bd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Constant-time comparison (does not leak how many leading characters match). */
    fun matches(expected: String, given: String): Boolean {
        if (expected.isEmpty()) return false
        val a = MessageDigest.getInstance("SHA-256").digest(expected.toByteArray(Charsets.UTF_8))
        val b = MessageDigest.getInstance("SHA-256").digest(given.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(a, b)
    }

    /** "bd_AbCd…wxyz" for logs and UI previews; never the whole token. */
    fun redact(token: String): String = if (token.length <= 10) "…" else token.take(6) + "…" + token.takeLast(4)
}

/** Which peers may talk to the server: the local network only (defence in depth on top of the bind addresses). */
object LanPolicy {
    fun isAllowedPeer(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        // IPv4-mapped / unique local IPv6 (fc00::/7)
        if (address !is Inet4Address) {
            val b = address.address
            if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true
        }
        return false
    }

    /** Private IPv4 address suitable for binding the server (10/8, 172.16/12, 192.168/16). */
    fun isBindable(address: InetAddress): Boolean = address is Inet4Address && address.isSiteLocalAddress
}
