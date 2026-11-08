/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import java.util.concurrent.ConcurrentHashMap

/**
 * Time window shown by a trend card: how far back the curve goes. Stored per card in seconds
 * ([io.github.jonathangtd.bimmerdata.dashboard.Card.windowSeconds]); null means [DEFAULT_SECONDS],
 * the fixed window of earlier versions, so old layouts look the same.
 */
object TrendWindow {
    const val DEFAULT_SECONDS = 45
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 3600
    const val DEFAULT_MS = DEFAULT_SECONDS * 1000L

    /** Quick choices offered by the editor (and cycled by a double tap on the card). */
    val QUICK: List<Int> = listOf(10, 30, 60, 120, 300, 600, 1800)

    fun seconds(windowSeconds: Int?): Int = (windowSeconds ?: DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun ms(windowSeconds: Int?): Long = seconds(windowSeconds) * 1000L

    fun isValid(seconds: Int): Boolean = seconds in MIN_SECONDS..MAX_SECONDS

    /** Next quick choice after [windowSeconds] (wraps around): 30 s → 1 min → … → 30 min → 10 s. */
    fun next(windowSeconds: Int?): Int {
        val cur = seconds(windowSeconds)
        return QUICK.firstOrNull { it > cur } ?: QUICK.first()
    }

    /** "45 s", "1 min", "1 min 30 s", "30 min", "60 min" (same symbols in French and English). */
    fun duration(seconds: Int): String {
        if (seconds < 60) return "$seconds$NBSP" + "s"
        val m = seconds / 60
        val s = seconds % 60
        return if (s == 0) "$m${NBSP}min" else "$m${NBSP}min$NBSP$s${NBSP}s"
    }

    private val tickCache = ConcurrentHashMap<Long, String>()

    /** Axis label of a point [agoMs] before now: "−30 s", "−5 min" (real minus sign); cached. */
    fun tickLabel(agoMs: Long): String = tickCache.getOrPut(agoMs) { MINUS + duration((agoMs / 1000).toInt()) }

    private val durationCache = ConcurrentHashMap<Long, String>()

    /** [duration] of a window given in ms, cached (drawn every frame). */
    fun durationLabel(windowMs: Long): String = durationCache.getOrPut(windowMs) { duration((windowMs / 1000).toInt()) }

    private val STEPS_S = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800)

    /** Grid step for a window: the smallest "nice" step giving at most [maxLines] lines. */
    fun gridStepMs(windowMs: Long, maxLines: Int = 5): Long {
        val sec = windowMs / 1000.0
        for (s in STEPS_S) if (sec / s <= maxLines + 1e-9) return s * 1000L
        return STEPS_S.last() * 1000L
    }

    private const val MINUS = "−"
    private const val NBSP = " "
}
