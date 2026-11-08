/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import kotlinx.coroutines.flow.StateFlow

/*
 * Contract between the acquisition engine (producer) and the dashboard / Android Auto / logger
 * (consumers). Keep it small and stable: both sides are developed against these types only.
 */

/** One live value the user can put on a card or log: an EDIABAS result read from an ECU. */
data class Measurement(
    /** Stable key, unique per measurement, used in layouts and CSV headers, e.g. "msv70/0x4402". */
    val id: String,
    /** SGBD (ECU description file) without extension, lower case, e.g. "msv70". */
    val sgbd: String,
    /** Short label to display; defaults to the SGBD's INFO text, may be renamed by the user. */
    val label: String,
    /** Unit as given by the SGBD (EINHEIT), e.g. "°C", "%", "rpm"; empty if none. */
    val unit: String,
    val source: MeasurementSource,
    /** Optional plausible range, for gauge scales; null = unknown (the UI picks one). */
    val min: Double? = null,
    val max: Double? = null,
)

sealed interface MeasurementSource {
    /**
     * Fast path: a row of the SGBD's MESSWERTETAB (e.g. id 0x4402, resultName
     * "STAT_OELTEMPERATUR_WERT"). Several of them are read together through one dynamically
     * defined local identifier (KWP2000 $2C defined once, then only $21 F0 in a loop).
     */
    data class MeasurementTable(val id: Int, val resultName: String) : MeasurementSource

    /** Generic path: run [job] with [args] and take result [resultName] from result set [set]. */
    data class JobResult(val job: String, val args: String, val resultName: String, val set: Int = 1) : MeasurementSource
}

/** Latest value of a measurement. Numeric results fill [value]; text results fill [text]. */
data class Sample(val value: Double?, val text: String?, val timestampMs: Long)

enum class AcquisitionState { Stopped, Connecting, Running, Error }

data class AcquisitionStatus(
    val state: AcquisitionState = AcquisitionState.Stopped,
    /** Complete read cycles per second over the last few seconds. */
    val cyclesPerSecond: Double = 0.0,
    val errorCount: Int = 0,
    val message: String? = null,
)

/** Producer side: reads the requested measurements in a loop while running. */
interface LiveDataSource {
    /** Latest sample per [Measurement.id]. */
    val samples: StateFlow<Map<String, Sample>>
    val status: StateFlow<AcquisitionStatus>

    /** Replaces the set of measurements to read (e.g. those visible on the current page). */
    fun setMeasurements(measurements: List<Measurement>)
}

/** Lists what can be measured on an ECU, for the card editor's picker. */
interface MeasurementCatalog {
    /** SGBDs available in the imported ECU folder that have a measurement table. */
    fun sgbdsWithMeasurements(): List<String>

    /** All measurements of [sgbd] (fast-path ones from MESSWERTETAB first). */
    fun measurements(sgbd: String): List<Measurement>
}

data class RecordingState(
    val recording: Boolean = false,
    val fileName: String? = null,
    val startedAtMs: Long? = null,
    val rows: Long = 0,
)

/** CSV session logger fed by a [LiveDataSource]. */
interface SessionRecorder {
    val state: StateFlow<RecordingState>
    fun start()
    fun stop()

    /** Adds a marker in the current recording (button "Repère"). */
    fun mark(label: String = "")
}
