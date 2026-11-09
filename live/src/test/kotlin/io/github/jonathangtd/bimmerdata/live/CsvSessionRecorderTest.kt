/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneId

class CsvSessionRecorderTest {

    private class FakeSource : LiveDataSource {
        override val samples = MutableStateFlow<Map<String, Sample>>(emptyMap())
        val pauseFlow = kotlinx.coroutines.flow.MutableSharedFlow<AcquisitionPause>(extraBufferCapacity = 16)
        override val pauses: kotlinx.coroutines.flow.Flow<AcquisitionPause> get() = pauseFlow
        override val status: StateFlow<AcquisitionStatus> = MutableStateFlow(AcquisitionStatus())
        var requested: List<Measurement> = emptyList()
        override fun setMeasurements(measurements: List<Measurement>) {
            requested = measurements
        }
    }

    private class Sink : ByteArrayOutputStream() {
        @Volatile var closed = false
        override fun close() {
            closed = true
        }
    }

    private val oil = Measurement("msv70/0x4402", "msv70", "Öltemperatur", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OELTEMPERATUR_WERT"))
    private val lambda = Measurement("msv70/0x5A50", "msv70", "Lambda; Bank 1", "", MeasurementSource.MeasurementTable(0x5A50, "STAT_LAMBDA_BANK1_WERT"))
    private val state = Measurement("x/state", "msv70", "Status", "0-n", MeasurementSource.JobResult("STATUS_X", "", "STAT_X_TEXT"))

    private suspend fun awaitUntil(cond: () -> Boolean) = withTimeout(5000) { while (!cond()) delay(5) }

    @Test
    fun `a pause that arrives after the next values is still written before them`() = runBlocking {
        val source = FakeSource()
        val sink = Sink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val t0 = 1_762_590_652_000L
        val recorder = CsvSessionRecorder(
            source = source, columns = { listOf(oil) }, output = { sink }, scope = scope,
            dispatcher = Dispatchers.Default, clock = { t0 }, zone = ZoneId.of("UTC"), pauseGapMs = 1000, pauseWaitMs = 2000,
        )
        try {
            recorder.start()
            awaitUntil { source.pauseFlow.subscriptionCount.value > 0 }
            source.samples.value = mapOf(oil.id to Sample(90.0, null, t0 + 100))
            awaitUntil { recorder.state.value.rows == 1L }
            // the cable was busy from t0+150 to t0+1950; the values read right after the pause
            // reach the recorder first, the pause 300 ms later (as through the app's relay)
            source.samples.value = mapOf(oil.id to Sample(91.0, null, t0 + 2000))
            delay(300)
            source.pauseFlow.emit(AcquisitionPause(t0 + 150, t0 + 1950, "Faults: late"))
            awaitUntil { recorder.state.value.rows == 3L }
            source.samples.value = mapOf(oil.id to Sample(92.0, null, t0 + 2050))
            awaitUntil { recorder.state.value.rows == 4L }
            recorder.stop()
            awaitUntil { sink.closed }
            val lines = sink.toString(Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }.drop(1)
            assertEquals(4, lines.size, lines.joinToString("\n"))
            assertEquals("100", lines[0].substringBefore(';'))
            assertTrue(lines[1].endsWith("pause 1800 ms: Faults: late"), lines[1])
            assertEquals("150", lines[1].substringBefore(';'), "pause line at the time the reading stopped")
            assertEquals("2000", lines[2].substringBefore(';'))
            assertEquals("2050", lines[3].substringBefore(';'))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a gap without any pause only delays the row a little`() = runBlocking {
        val source = FakeSource()
        val sink = Sink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val t0 = 1_762_590_652_000L
        val recorder = CsvSessionRecorder(
            source = source, columns = { listOf(oil) }, output = { sink }, scope = scope,
            dispatcher = Dispatchers.Default, clock = { t0 }, zone = ZoneId.of("UTC"), pauseGapMs = 1000, pauseWaitMs = 200,
        )
        try {
            recorder.start()
            awaitUntil { source.pauseFlow.subscriptionCount.value > 0 }
            source.samples.value = mapOf(oil.id to Sample(90.0, null, t0 + 100))
            awaitUntil { recorder.state.value.rows == 1L }
            source.samples.value = mapOf(oil.id to Sample(91.0, null, t0 + 3000)) // slow ECU, no pause
            awaitUntil { recorder.state.value.rows == 2L }
            recorder.stop()
            awaitUntil { sink.closed }
            val lines = sink.toString(Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }.drop(1)
            assertEquals(listOf("100", "3000"), lines.map { it.substringBefore(';') })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `CSV format, markers and pinning`() = runBlocking {
        val source = FakeSource()
        val sink = Sink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = 1_762_590_652_000L // 2025-11-08T08:30:52Z
        val names = ArrayList<String>()
        var pinned: List<Measurement>? = null
        val recorder = CsvSessionRecorder(
            source = source,
            columns = { listOf(oil, lambda, state) },
            output = { names += it; sink },
            scope = scope,
            pin = { pinned = it },
            dispatcher = Dispatchers.Default,
            clock = { now },
            zone = ZoneId.of("UTC"),
        )
        try {
            recorder.start()
            assertEquals(listOf(oil, lambda, state), pinned)
            assertTrue(recorder.state.value.recording)
            awaitUntil { names.isNotEmpty() }
            assertEquals("bimmerdata_20251108_083052.csv", names.single())
            assertEquals(names.single(), recorder.state.value.fileName)

            source.samples.value = mapOf(oil.id to Sample(14.5, null, now - 10_000)) // older than the start: no row
            delay(50)
            now += 100
            source.samples.value = mapOf(
                oil.id to Sample(14.500000223517418, null, now),
                lambda.id to Sample(1.0, null, now),
                state.id to Sample(null, "LSH_OFF; idle", now),
            )
            awaitUntil { recorder.state.value.rows == 1L }
            now += 60
            source.samples.value = source.samples.value + (lambda.id to Sample(0.987654321, null, now))
            awaitUntil { recorder.state.value.rows == 2L }
            recorder.mark("full throttle")
            awaitUntil { recorder.state.value.rows == 3L }
            now += 7000 // oil and state are stale now
            source.samples.value = source.samples.value + (lambda.id to Sample(-0.0000001, null, now))
            awaitUntil { recorder.state.value.rows == 4L }
            recorder.mark()
            awaitUntil { recorder.state.value.rows == 5L }
            recorder.stop()
            assertEquals(null, pinned)
            assertFalse(recorder.state.value.recording)
            awaitUntil { sink.closed }
            recorder.mark("ignored")

            val bytes = sink.toByteArray()
            assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF }, "UTF-8 BOM")
            val lines = String(bytes, Charsets.UTF_8).removePrefix("﻿").split("\r\n")
            lines.forEach { println(it) }
            assertEquals("time_ms;iso_time;Öltemperatur [°C];\"Lambda; Bank 1\";Status [0-n];marker", lines[0])
            assertEquals("100;2025-11-08T08:30:52.100+00:00;14.5;1;\"LSH_OFF; idle\";", lines[1])
            assertEquals("160;2025-11-08T08:30:52.160+00:00;14.5;0.987654;\"LSH_OFF; idle\";", lines[2])
            assertTrue(lines[3].endsWith(";full throttle"), lines[3])
            assertEquals("7160;2025-11-08T08:30:59.160+00:00;;0;;", lines[4])
            assertTrue(lines[5].endsWith(";mark 2"), lines[5])
            assertEquals("", lines[6])
            assertEquals(7, lines.size)
            assertEquals(5L, recorder.state.value.rows)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an output that cannot be created stops the recording with an error`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val recorder = CsvSessionRecorder(
                FakeSource(), { listOf(oil) }, { throw IOException("disk full") }, scope, dispatcher = Dispatchers.Default,
            )
            recorder.start()
            awaitUntil { !recorder.state.value.recording }
            assertTrue(recorder.state.value.error!!.contains("disk full"))
            assertEquals(null, recorder.state.value.fileName?.takeIf { recorder.state.value.recording })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `file prefix and minimum row interval`() = runBlocking {
        val source = FakeSource()
        val sink = Sink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val now = 1_762_590_652_000L
        val names = ArrayList<String>()
        val recorder = CsvSessionRecorder(
            source = source, columns = { listOf(oil) }, output = { names += it; sink }, scope = scope,
            dispatcher = Dispatchers.Default, clock = { now }, zone = ZoneId.of("UTC"),
            fileNamer = { CsvSessionRecorder.fileName(it, ZoneId.of("UTC"), "e90 trip/1") },
            minRowIntervalMs = { 500 },
        )
        try {
            recorder.start()
            awaitUntil { names.isNotEmpty() }
            assertEquals("e90trip1_20251108_083052.csv", names.single())
            for (i in 0 until 20) { // 20 reads at 100 ms -> rows at 0, 500, 1000, 1500
                source.samples.value = mapOf(oil.id to Sample(i.toDouble(), null, now + i * 100L))
                delay(15)
            }
            awaitUntil { recorder.state.value.rows == 4L }
            recorder.mark("m") // markers are never thinned out
            awaitUntil { recorder.state.value.rows == 5L }
            delay(50)
            assertEquals(5L, recorder.state.value.rows)
        } finally {
            recorder.stop()
            scope.cancel()
        }
        assertEquals("bimmerdata", CsvSessionRecorder.safePrefix("  /// "))
    }

    @Test
    fun `number format`() {
        assertEquals("14.5", CsvSessionRecorder.number(14.500000223517418))
        assertEquals("0", CsvSessionRecorder.number(2.220446E-14))
        assertEquals("0", CsvSessionRecorder.number(-0.0000001))
        assertEquals("-1.643372", CsvSessionRecorder.number(-1.643372))
        assertEquals("700", CsvSessionRecorder.number(700.0))
        assertEquals("123456789", CsvSessionRecorder.number(123456789.0))
    }

    @Suppress("unused")
    private fun OutputStream.unused() = Unit
}
