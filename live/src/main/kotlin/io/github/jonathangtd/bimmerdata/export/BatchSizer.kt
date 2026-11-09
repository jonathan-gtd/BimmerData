/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

/**
 * Lines per upload batch for a server that may be slow (e.g. a Telegraf whose listener times out
 * on big batches, seen as 502 / 504 behind a reverse proxy):
 * - no limit ([Int.MAX_VALUE], whole queue segments) until a batch is too big;
 * - a batch too big halves the limit (down to [min]) and is remembered: the limit never grows
 *   back to a size that failed, so batches do not fail one time in two;
 * - after [growAfter] successes in a row the limit doubles (or goes halfway to the failed size);
 * - after [probeAfter] successes the failed size is forgotten, to follow a server that got faster.
 *
 * Not thread safe (used under the exporter's send lock).
 */
class BatchSizer(
    private val min: Int = 50,
    private val growAfter: Int = 10,
    private val probeAfter: Int = 100,
) {
    /** Lines per batch; [Int.MAX_VALUE] = no limit. */
    var limit: Int = Int.MAX_VALUE
        private set

    private var failedAt = Int.MAX_VALUE
    private var streak = 0
    private var sinceFailure = 0

    /** A batch of [lines] lines was too big. True when the limit went down (a smaller batch is worth a try now). */
    fun tooBig(lines: Int): Boolean {
        streak = 0
        sinceFailure = 0
        failedAt = minOf(failedAt, lines)
        if (lines <= min) return false
        val next = (lines / 2).coerceAtLeast(min)
        if (next >= limit) return false
        limit = next
        return true
    }

    /** A batch went through. */
    fun ok() {
        if (limit == Int.MAX_VALUE) return
        streak++
        sinceFailure++
        if (sinceFailure >= probeAfter) {
            failedAt = Int.MAX_VALUE
            sinceFailure = 0
        }
        if (streak < growAfter) return
        streak = 0
        val doubled = limit.toLong() * 2
        val next = if (doubled < failedAt) doubled else (limit.toLong() + failedAt) / 2
        if (next > limit) limit = next.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
