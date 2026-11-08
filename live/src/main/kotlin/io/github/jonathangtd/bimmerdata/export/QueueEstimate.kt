/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

/**
 * How long the phone can keep queuing Telegraf points before reaching the free-space reserve
 * ("after the trip" mode keeps everything on the phone until home Wi-Fi). Nothing is deleted
 * when the reserve is reached: queuing stops.
 */
object QueueEstimate {
    /** Typical size of one line-protocol point (tags car/ecu/id/label/unit, value, ns time). */
    const val TYPICAL_LINE_BYTES = 110L

    /** Typical gzip ratio of line protocol (compressed / raw), until the queue measured its own. */
    const val TYPICAL_GZIP_RATIO = 0.1

    /** Queue sizes on disk from which the user is told the queue grows (one notification each). */
    val GROWTH_STEPS_BYTES = listOf(100L, 500L, 1000L, 2000L, 5000L, 10000L).map { it * LineQueue.MB }

    /** Index of the highest [GROWTH_STEPS_BYTES] reached by [bytes], -1 below the first. */
    fun growthLevel(bytes: Long): Int = GROWTH_STEPS_BYTES.indexOfLast { bytes >= it }

    /** Compressed / raw size of the queue, or [TYPICAL_GZIP_RATIO] while too little is queued to tell. */
    fun gzipRatio(diskBytes: Long, rawBytes: Long): Double =
        if (rawBytes >= 64 * 1024 && diskBytes > 0) (diskBytes.toDouble() / rawBytes).coerceIn(0.01, 1.0) else TYPICAL_GZIP_RATIO

    /**
     * Raw bytes per second the queue grows by: the measured rate while points are being queued,
     * else an estimate: every reading is exported, so [readsPerSecond] (read cycles per second)
     * × [measurements] points per second, [TYPICAL_LINE_BYTES] each.
     */
    fun rawBytesPerSecond(measuredRawBytesPerSecond: Double, readsPerSecond: Double, measurements: Int): Double =
        if (measuredRawBytesPerSecond > 0) measuredRawBytesPerSecond else readsPerSecond.coerceAtLeast(0.0) * measurements.coerceAtLeast(0) * TYPICAL_LINE_BYTES

    /**
     * Seconds of recording that still fit before the reserve: [roomBytes] on disk, growing by
     * [rawBytesPerSecond] × [gzipRatio]. Null when unknown (nothing queued per second).
     */
    fun autonomySeconds(roomBytes: Long, rawBytesPerSecond: Double, gzipRatio: Double): Long? {
        val disk = rawBytesPerSecond * gzipRatio
        if (disk <= 0.0) return null
        return (roomBytes.coerceAtLeast(0) / disk).toLong()
    }

    /** Raw bytes queued over a sliding window, for the queue growth rate. Thread safe. */
    class Rate(private val windowMs: Long = 60_000) {
        private val events = ArrayDeque<Pair<Long, Long>>()
        private var total = 0L

        @Synchronized
        fun add(timeMs: Long, bytes: Long) {
            events.addLast(timeMs to bytes)
            total += bytes
            trim(timeMs)
        }

        /** Average over the window; 0 when nothing was added in the last [windowMs]. */
        @Synchronized
        fun bytesPerSecond(nowMs: Long): Double {
            trim(nowMs)
            if (events.isEmpty()) return 0.0
            val span = (nowMs - events.first().first).coerceAtLeast(1_000).coerceAtMost(windowMs)
            return total * 1000.0 / span
        }

        private fun trim(nowMs: Long) {
            while (events.isNotEmpty() && nowMs - events.first().first > windowMs) total -= events.removeFirst().second
        }
    }
}
