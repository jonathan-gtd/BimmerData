/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.export.LineEncoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadStatsPointsTest {

    private val stats = ReadStats(
        blocks = listOf(ReadStats.BlockStats("msv70", 10, "Native", 100, 100, null), ReadStats.BlockStats("msv70", 9, "Native", 99, 99, null)),
        cycles = 199, slowCycles = 3, medianCycleMs = 126, p90CycleMs = 131, errorCount = 2, lastError = null,
        defines = 199, timeouts = 2, readsPerSecond = 7.9, readIntervalMs = 250, slowDowns = 1,
    )

    @Test
    fun `one point per statistic, exported as the pseudo ECU bimmerdata`() {
        val points = ReadStatsPoints.samples(stats, 1_000L).toMap()
        assertEquals(ReadStatsPoints.Field.entries.size, points.size)
        assertEquals(250.0, points["bimmerdata/stat_read_interval_ms"]!!.value)
        assertEquals(1.0, points["bimmerdata/stat_slowdowns"]!!.value)
        assertEquals(2.0, points["bimmerdata/stat_timeouts"]!!.value)
        assertEquals(10.0, points["bimmerdata/stat_values_per_block"]!!.value, "biggest block")
        assertEquals(7.9, points["bimmerdata/stat_reads_per_s"]!!.value)
        assertTrue(points.values.all { it.timestampMs == 1_000L })

        val m = ReadStatsPoints.measurement("bimmerdata/stat_read_interval_ms")!!
        assertEquals("ms", m.unit)
        val line = LineEncoder("E90").encode(m, points[m.id]!!)!!
        assertTrue(line.contains("ecu=bimmerdata") && line.contains("id=stat_read_interval_ms") && line.contains("unit=ms"), line)
        assertNull(ReadStatsPoints.measurement("msv70/0x4807"))
        assertTrue(stats.summary().contains("interval 250 ms, slow-downs 1"), stats.summary())
    }
}
