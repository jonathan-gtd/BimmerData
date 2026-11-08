/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

/**
 * Automatic refresh of cached translation files: at app start, at most once per [IntervalMs],
 * every cached file is asked again with HTTP validators (`If-None-Match` / `If-Modified-Since`),
 * so nothing is downloaded when the file did not change (HTTP 304).
 */
object TranslationRefresh {
    const val IntervalMs: Long = 24L * 60 * 60 * 1000

    /** True when the last successful check is older than [intervalMs] (or never ran, or the clock went back). */
    fun isDue(lastCheckMs: Long, nowMs: Long, intervalMs: Long = IntervalMs): Boolean =
        lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs

    /** HTTP validators of a downloaded file, stored next to it (`<sgbd>.csv.etag`). */
    data class Validators(val etag: String? = null, val lastModified: String? = null) {
        val isEmpty: Boolean get() = etag.isNullOrBlank() && lastModified.isNullOrBlank()

        /** Two lines `etag: …` / `last-modified: …`. */
        fun encode(): String = buildString {
            etag?.takeIf { it.isNotBlank() }?.let { append("etag: ").append(it.trim()).append('\n') }
            lastModified?.takeIf { it.isNotBlank() }?.let { append("last-modified: ").append(it.trim()).append('\n') }
        }

        /** Request headers for a conditional GET (empty without validators). */
        fun conditionalHeaders(): Map<String, String> = buildMap {
            etag?.takeIf { it.isNotBlank() }?.let { put("If-None-Match", it.trim()) }
            lastModified?.takeIf { it.isNotBlank() }?.let { put("If-Modified-Since", it.trim()) }
        }

        companion object {
            fun decode(text: String?): Validators {
                if (text.isNullOrBlank()) return Validators()
                var etag: String? = null
                var lastModified: String? = null
                text.lineSequence().forEach { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) return@forEach
                    val value = line.substring(i + 1).trim().takeIf { it.isNotEmpty() }
                    when (line.substring(0, i).trim().lowercase()) {
                        "etag" -> etag = value
                        "last-modified" -> lastModified = value
                    }
                }
                return Validators(etag, lastModified)
            }
        }
    }
}
