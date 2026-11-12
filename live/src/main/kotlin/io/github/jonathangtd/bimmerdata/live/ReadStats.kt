/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import java.util.Locale

/**
 * How the read loop is doing, to find out on the car why cycles are slow or whether an ECU
 * rebooted: per dynamic block, how it is read (Native = one `21 F0` telegram decoded by the app,
 * Interpreted = the EDIABAS job every time, much slower) and how often it was defined; for the
 * whole connection, the reads nobody answered ([timeouts]: an ECU rebooting answers nothing for a
 * second or two), the automatic slow-downs, the cycle times and the read interval in use.
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
    /** Block definitions sent since connecting (every read when redefined each read). */
    val defines: Long = 0,
    /** Reads without any answer (IFH-0009) since connecting. */
    val timeouts: Long = 0,
    /** Read cycles per second over the last seconds. */
    val readsPerSecond: Double = 0.0,
    /** Start-to-start ms between two reads of one ECU now (125 ms, more after a slow-down). */
    val readIntervalMs: Long = 0,
    /** Automatic slow-downs (interval doubled after a timeout / transmission error) since connecting. */
    val slowDowns: Long = 0,
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

    /** Values of the biggest block (0 without blocks). */
    val valuesPerBlock: Int get() = blocks.maxOfOrNull { it.values } ?: 0

    /** One line for the log and the REST API. */
    fun summary(): String = buildString {
        blocks.forEach { b ->
            append(b.sgbd).append(' ').append(b.values).append(" values ").append(b.mode)
            append(", reads ").append(b.reads).append(", defines ").append(b.defines)
            b.interpretedReason?.let { append(" (").append(it).append(')') }
            append("; ")
        }
        append("cycles ").append(cycles)
        append(String.format(Locale.ROOT, " (%.1f/s)", readsPerSecond))
        append(", median ").append(medianCycleMs).append(" ms, p90 ").append(p90CycleMs).append(" ms")
        append(", slow>").append(SLOW_MS).append("ms ")
        append(if (cycles > 0) String.format(Locale.ROOT, "%.1f%%", 100.0 * slowCycles / cycles) else "0%")
        append(", defines ").append(defines)
        append(", timeouts ").append(timeouts)
        append(", errors ").append(errorCount)
        append("; interval ").append(readIntervalMs).append(" ms, slow-downs ").append(slowDowns)
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

/**
 * The [ReadStats] as measurements of a pseudo ECU [SGBD] (`bimmerdata/stat_defines`...), sampled
 * every few seconds while connected, so they go to the Telegraf export next to the ECU values
 * (tag `ecu=bimmerdata`). Counters are cumulative since the connection.
 */
object ReadStatsPoints {
    const val SGBD = "bimmerdata"
    const val INTERVAL_MS = 5000L

    enum class Field(val key: String, val defaultLabel: String, val unit: String) {
        ReadsPerSecond("stat_reads_per_s", "Reads per second", "1/s"),
        ValuesPerBlock("stat_values_per_block", "Values in the biggest block", ""),
        ReadInterval("stat_read_interval_ms", "Time between two reads of one ECU", "ms"),
        SlowDowns("stat_slowdowns", "Automatic slow-downs", ""),
        Defines("stat_defines", "Block definitions", ""),
        Timeouts("stat_timeouts", "Reads without answer", ""),
        Errors("stat_errors", "Read errors", ""),
        SlowCycles("stat_slow_cycles", "Cycles over 150 ms", ""),
        MedianCycle("stat_median_cycle_ms", "Median cycle time", "ms"),
        P90Cycle("stat_p90_cycle_ms", "Cycle time p90", "ms"),
        ;

        val id: String get() = "$SGBD/$key"
    }

    fun isStatId(id: String): Boolean = id.startsWith("$SGBD/")

    /** The measurement of [id] (label from [label]), or null. */
    fun measurement(id: String, label: (Field) -> String = { it.defaultLabel }): Measurement? =
        Field.entries.firstOrNull { it.id == id }?.let { f ->
            Measurement(f.id, SGBD, label(f), f.unit, MeasurementSource.Sensor(SGBD, f.key), description = "BimmerData read statistics")
        }

    /** One sample per field at [timeMs]. */
    fun samples(s: ReadStats, timeMs: Long): List<Pair<String, Sample>> = Field.entries.map { f ->
        val v: Double = when (f) {
            Field.ReadsPerSecond -> s.readsPerSecond
            Field.ValuesPerBlock -> s.valuesPerBlock.toDouble()
            Field.ReadInterval -> s.readIntervalMs.toDouble()
            Field.SlowDowns -> s.slowDowns.toDouble()
            Field.Defines -> s.defines.toDouble()
            Field.Timeouts -> s.timeouts.toDouble()
            Field.Errors -> s.errorCount.toDouble()
            Field.SlowCycles -> s.slowCycles.toDouble()
            Field.MedianCycle -> s.medianCycleMs.toDouble()
            Field.P90Cycle -> s.p90CycleMs.toDouble()
        }
        f.id to Sample(v, null, timeMs)
    }
}
