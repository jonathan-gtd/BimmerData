/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import java.util.Locale

/**
 * How the read loop is doing, to find out on the car why cycles are slow: per dynamic block, how
 * it is read (Native = one `21 F0` telegram decoded by the app, Interpreted = the EDIABAS job
 * every time, much slower) and how often it had to be defined again; for the cycles, their time.
 */
data class ReadStats(
    val blocks: List<BlockStats>,
    /** Read cycles since connecting. */
    val cycles: Long,
    /** Cycles that took more than [SLOW_MS] (start to start). */
    val slowCycles: Long,
    /** Over the last cycles (up to [WINDOW]). */
    val medianCycleMs: Long,
    val p90CycleMs: Long,
    val errorCount: Int,
    val lastError: String?,
) {
    data class BlockStats(
        val sgbd: String,
        val values: Int,
        /** Native, Interpreted or Undefined. */
        val mode: String,
        val reads: Long,
        val defines: Long,
        /** Why the block is read through the job (Interpreted), if it is. */
        val interpretedReason: String?,
    )

    /** One line for the log and the REST API. */
    fun summary(): String = buildString {
        blocks.forEach { b ->
            append(b.sgbd).append(' ').append(b.values).append(" values ").append(b.mode)
            append(", reads ").append(b.reads).append(", defines ").append(b.defines)
            b.interpretedReason?.let { append(" (").append(it).append(')') }
            append("; ")
        }
        append("cycles ").append(cycles)
        append(", median ").append(medianCycleMs).append(" ms, p90 ").append(p90CycleMs).append(" ms")
        append(", slow>").append(SLOW_MS).append("ms ")
        append(if (cycles > 0) String.format(Locale.ROOT, "%.1f%%", 100.0 * slowCycles / cycles) else "0%")
        append(", errors ").append(errorCount)
        lastError?.let { append(" (last: ").append(it).append(')') }
    }

    companion object {
        const val SLOW_MS = 150L
        const val WINDOW = 256

        /** Median and 90th percentile of [ms] (0 when empty). */
        fun percentiles(ms: LongArray): Pair<Long, Long> {
            if (ms.isEmpty()) return 0L to 0L
            val s = ms.sorted()
            return s[s.size / 2] to s[((s.size - 1) * 9) / 10]
        }
    }
}
