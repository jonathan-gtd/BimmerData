/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.zip.GZIPOutputStream

/** How the phone authenticates to Telegraf. */
sealed interface TelegrafAuth {
    data object None : TelegrafAuth

    /** `Authorization: Token <token>` (influxdb_v2_listener `token = ...`). */
    data class Token(val token: String) : TelegrafAuth

    /** HTTP basic auth (http_listener_v2 `basic_username` / `basic_password`). */
    data class Basic(val user: String, val password: String) : TelegrafAuth
}

/** Outcome of one HTTP write. */
sealed interface WriteResult {
    data object Ok : WriteResult

    /**
     * Anything that may work later: network error, timeout, 5xx, 429, 404 (wrong path or server
     * not ready), other 4xx. The batch stays queued and is retried.
     */
    data class Retry(val message: String) : WriteResult

    /** 401 / 403: wrong token or password; keep the batch, stop until the settings change. */
    data class AuthFailed(val message: String) : WriteResult

    /**
     * 400 / 413 / 422: the server says this batch itself is wrong (bad line protocol, too big).
     * It is moved aside (`rejected/`), never deleted automatically.
     */
    data class Rejected(val message: String) : WriteResult
}

/**
 * Posts line protocol to a Telegraf HTTP listener, e.g. `http://server:8186/api/v2/write`
 * (influxdb_v2_listener) or `http://server:8080/telegraf` (http_listener_v2). Queue batches are
 * already gzip data and are sent as-is with `Content-Encoding: gzip` (both listeners accept it);
 * other bodies over 1 KB are compressed here. Blocking; call it off the main thread.
 */
class TelegrafClient(
    private val url: String,
    private val auth: TelegrafAuth,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    /** Posts [body]; [gzipped] = [body] is already gzip data. */
    fun write(body: ByteArray, gzipped: Boolean = false): WriteResult {
        val target = try {
            parse(url)
        } catch (e: IllegalArgumentException) {
            // a settings problem, not the batch's fault: keep it
            return WriteResult.Retry(e.message ?: "Invalid URL")
        }
        val conn = try {
            target.toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return WriteResult.Retry(e.message ?: e.javaClass.simpleName)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.setRequestProperty("User-Agent", "BimmerData")
            when (auth) {
                TelegrafAuth.None -> Unit
                is TelegrafAuth.Token -> conn.setRequestProperty("Authorization", "Token ${auth.token}")
                is TelegrafAuth.Basic -> conn.setRequestProperty(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString("${auth.user}:${auth.password}".toByteArray(Charsets.UTF_8)),
                )
            }
            val payload = if (gzipped) {
                conn.setRequestProperty("Content-Encoding", "gzip")
                body
            } else if (body.size > 1024) {
                conn.setRequestProperty("Content-Encoding", "gzip")
                gzip(body)
            } else {
                body
            }
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val message = runCatching { (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrNull().orEmpty().trim().take(300)
            val detail = "HTTP $code" + if (message.isNotEmpty()) ": $message" else ""
            return when {
                code in 200..299 -> WriteResult.Ok
                code == 401 || code == 403 -> WriteResult.AuthFailed(detail)
                code == 400 || code == 413 || code == 422 -> WriteResult.Rejected(detail)
                else -> WriteResult.Retry(detail)
            }
        } catch (e: IOException) {
            return WriteResult.Retry(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /** Connection test: an empty write (nothing is stored). */
    fun test(): WriteResult = write(ByteArray(0))

    companion object {
        /** Checks a listener URL: http(s), with a host. */
        fun parse(url: String): URI {
            val u = try {
                URI(url.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid URL: ${e.message}")
            }
            require(u.scheme == "http" || u.scheme == "https") { "The URL must start with http:// or https://" }
            require(!u.host.isNullOrBlank()) { "The URL has no host" }
            return u
        }

        fun isValidUrl(url: String): Boolean = runCatching { parse(url) }.isSuccess

        private fun gzip(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size / 4 + 64)
            GZIPOutputStream(out).use { it.write(data) }
            return out.toByteArray()
        }
    }
}
