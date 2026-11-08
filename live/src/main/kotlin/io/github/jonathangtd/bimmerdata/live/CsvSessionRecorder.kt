/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * CSV logger fed by a [LiveDataSource].
 *
 * Format (documented for users, keep stable):
 * - UTF-8 with BOM (Excel and LibreOffice then detect the encoding), `\r\n` line ends.
 * - Separator `;`, decimal point `.` (locale independent; in Excel/LibreOffice choose "." as
 *   decimal separator in the import dialog if the system uses a comma).
 * - Header `time_ms;iso_time;<label [unit]>...;marker`. `time_ms` counts from the start of the
 *   recording, `iso_time` is local time `yyyy-MM-ddTHH:mm:ss.SSS`.
 * - One row per new sample batch of the source (one engine cycle on the fast path). Each column
 *   holds the latest value of its measurement; it is left empty when the value is older than
 *   [staleAfterMs] (ECU not answering) or missing. State values are written as numbers; only
 *   results without a number are written as text. Cells with `;`, `"` or line breaks are quoted.
 * - [mark] writes its label (or "mark N") in the `marker` column of an immediate extra row.
 *
 * The columns are fixed at [start] (from [columns]); [pin] is called with them so the source
 * keeps reading them while recording, even if the dashboard shows another page.
 * The file is written through [output] (Android: a MediaStore stream), flushed about every
 * [flushIntervalMs], closed by [stop]. [start]/[stop]/[mark] never block.
 */
class CsvSessionRecorder(
    private val source: LiveDataSource,
    private val columns: () -> List<Measurement>,
    private val output: (fileName: String) -> OutputStream,
    private val scope: CoroutineScope,
    private val pin: (List<Measurement>?) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val flushIntervalMs: Long = 1000,
    private val staleAfterMs: Long = 5000,
) : SessionRecorder {

    private val _state = MutableStateFlow(RecordingState())
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val pendingMarker = AtomicReference<String?>(null)
    private val markTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var markCount = 0
    private var job: Job? = null
    private val lock = Any()

    override fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            val cols = columns().distinctBy { it.id }
            val startedAt = clock()
            val fileName = fileName(startedAt, zone)
            markCount = 0
            pendingMarker.set(null)
            pin(cols)
            _state.value = RecordingState(recording = true, fileName = fileName, startedAtMs = startedAt, rows = 0)
            job = scope.launch(dispatcher) { record(cols, fileName, startedAt) }
        }
    }

    override fun stop() {
        synchronized(lock) {
            val j = job ?: return
            job = null
            j.cancel()
            pin(null)
            _state.update { it.copy(recording = false) }
        }
    }

    override fun mark(label: String) {
        if (!_state.value.recording) return
        val text = synchronized(lock) {
            markCount++
            label.ifBlank { "mark $markCount" }
        }
        pendingMarker.getAndUpdate { old -> if (old == null) text else "$old / $text" }
        markTrigger.tryEmit(Unit)
    }

    private suspend fun record(cols: List<Measurement>, fileName: String, startedAt: Long) {
        val writer = try {
            BufferedWriter(OutputStreamWriter(output(fileName), Charsets.UTF_8), 64 * 1024)
        } catch (ex: Exception) {
            fail("Cannot create $fileName: ${ex.message}")
            return
        }
        var lastFlush = clock()
        var lastRowTime = Long.MIN_VALUE
        try {
            writer.write("﻿")
            writer.write(header(cols))
            writer.write("\r\n")
            merge(source.samples, markTrigger.map { source.samples.value }).collect { samples ->
                val marker = pendingMarker.getAndSet(null)
                val newest = cols.maxOfOrNull { samples[it.id]?.timestampMs ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
                val fresh = newest >= startedAt && newest > lastRowTime
                if (!fresh && marker == null) return@collect
                val rowTime = if (fresh) newest else maxOf(clock(), lastRowTime)
                writer.write(row(cols, samples, rowTime, startedAt, marker))
                writer.write("\r\n")
                lastRowTime = rowTime
                _state.update { it.copy(rows = it.rows + 1) }
                val now = clock()
                if (now - lastFlush >= flushIntervalMs || marker != null) {
                    writer.flush()
                    lastFlush = now
                }
            }
        } catch (ex: java.io.IOException) {
            fail("Write error on $fileName: ${ex.message}")
        } finally {
            runCatching { writer.flush() }
            runCatching { writer.close() }
        }
    }

    private fun fail(message: String) {
        synchronized(lock) {
            job = null
            pin(null)
        }
        _state.update { it.copy(recording = false, error = message) }
    }

    private fun header(cols: List<Measurement>): String = buildString {
        append("time_ms;iso_time")
        for (m in cols) {
            append(';')
            append(cell(if (m.unit.isBlank()) m.label else "${m.label} [${m.unit}]"))
        }
        append(";marker")
    }

    private fun row(cols: List<Measurement>, samples: Map<String, Sample>, rowTime: Long, startedAt: Long, marker: String?): String = buildString {
        append(rowTime - startedAt)
        append(';')
        append(IsoTime.format(Instant.ofEpochMilli(rowTime).atZone(zone)))
        for (m in cols) {
            append(';')
            val s = samples[m.id] ?: continue
            if (rowTime - s.timestampMs > staleAfterMs) continue
            val v = s.value
            if (v != null && v.isFinite()) append(number(v)) else if (s.text != null) append(cell(s.text))
        }
        append(';')
        if (marker != null) append(cell(marker))
    }

    companion object {
        private val IsoTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)
        private val FileTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

        const val MimeType = "text/csv"

        /** bimmerdata_YYYYMMDD_HHMMSS.csv (local time). */
        fun fileName(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            "bimmerdata_" + FileTime.format(Instant.ofEpochMilli(timeMs).atZone(zone)) + ".csv"

        /** Up to 6 decimals, no exponent, "." as decimal point, no trailing zeros. */
        fun number(v: Double): String {
            val s = String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
            return if (s == "-0") "0" else s
        }

        fun cell(text: String): String =
            if (text.any { it == ';' || it == '"' || it == '\n' || it == '\r' } || text != text.trim()) {
                "\"" + text.replace("\"", "\"\"") + "\""
            } else {
                text
            }

        /** Recorder for an [AcquisitionEngine]: records its current measurements and pins them while recording. */
        fun forEngine(
            engine: AcquisitionEngine,
            output: (fileName: String) -> OutputStream,
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): CsvSessionRecorder = CsvSessionRecorder(
            source = engine,
            columns = { engine.measurements.value },
            output = output,
            scope = scope,
            pin = engine::setPinnedMeasurements,
            dispatcher = dispatcher,
        )
    }
}
