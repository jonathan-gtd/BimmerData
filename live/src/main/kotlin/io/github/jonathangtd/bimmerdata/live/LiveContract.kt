/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow

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
    /** Number (gauges) or text / state (status cards). */
    val kind: ValueKind = ValueKind.Number,
    /** Optional longer text for pickers: what the job does, what the values mean. */
    val description: String = "",
)

/** What a [Measurement] returns. */
enum class ValueKind { Number, Text }

sealed interface MeasurementSource {
    /**
     * Fast path: a row of the SGBD's MESSWERTETAB (e.g. id 0x4402, resultName
     * "STAT_OELTEMPERATUR_WERT"). Several of them are read together through one dynamically
     * defined local identifier (KWP2000 $2C defined once, then only $21 F0 in a loop).
     */
    data class MeasurementTable(val id: Int, val resultName: String) : MeasurementSource

    /** Generic path: run [job] with [args] and take result [resultName] from result set [set]. */
    data class JobResult(val job: String, val args: String, val resultName: String, val set: Int = 1) : MeasurementSource

    /**
     * A sensor of the phone, not an ECU (e.g. [sensor] "gps", [field] "speed"): never read on the
     * cable, its samples come from the phone (see [PhoneSensors]).
     */
    data class Sensor(val sensor: String, val field: String) : MeasurementSource

    /**
     * A value computed by the app from other measurements ([inputs], in the order of
     * [ComputedKind.roles]), e.g. the power from a torque and an engine speed. Never read on the
     * cable itself: its inputs are, and its samples are computed from theirs (see [ComputedValues]).
     */
    data class Computed(val kind: ComputedKind, val inputs: List<Measurement>) : MeasurementSource
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

/**
 * The live reading stopped for a while because a one-off operation (Tool32 job, fault read...) had
 * the cable: no sample between [startMs] and [endMs] (epoch ms). Reported only above a threshold.
 */
data class AcquisitionPause(val startMs: Long, val endMs: Long, val reason: String) {
    val durationMs: Long get() = endMs - startMs
}

/** Producer side: reads the requested measurements in a loop while running. */
interface LiveDataSource {
    /** Latest sample per [Measurement.id]. */
    val samples: StateFlow<Map<String, Sample>>
    val status: StateFlow<AcquisitionStatus>

    /**
     * Every reading as it is made, in order (one list per read group), for consumers that must
     * not miss any value ([samples] is a state and skips intermediate values when its collector
     * is slow). Default: derived from [samples] (enough for fakes and tests).
     */
    val readings: Flow<List<Pair<String, Sample>>> get() = samples.map { m -> m.entries.map { it.key to it.value } }

    /** Gaps in the reading caused by one-off operations on the shared cable (default: none). */
    val pauses: Flow<AcquisitionPause> get() = emptyFlow()

    /**
     * Requested measurements the car cannot give although it answers: the ECU refuses the job or
     * the value, a result is missing, the protocol of that SGBD is not implemented... Id -> reason.
     * They are retried now and then; cards show them as "not available" instead of "no data".
     */
    val unavailable: StateFlow<Map<String, String>> get() = NoUnavailableMeasurements

    /** Replaces the set of measurements to read (e.g. those visible on the current page). */
    fun setMeasurements(measurements: List<Measurement>)
}

/** An SGBD (ECU description file) of the imported ECU folder, as listed by the picker. */
data class SgbdInfo(
    /** File name without extension, lower case, e.g. "msv70". */
    val name: String,
    /** ECU text of the SGBD, e.g. "MSV70 fuer N52" or "KOMBI E85"; may be empty. */
    val description: String = "",
    /** About how many measurements [MeasurementCatalog.measurements] returns (0 = unknown). */
    val measurementCount: Int = 0,
)

/** Lists what can be measured on an ECU, for the card editor's picker. */
interface MeasurementCatalog {
    /** SGBDs (.prg) of the imported ECU folder that have at least one measurement. */
    fun sgbdsWithMeasurements(): List<String>

    /** All measurements of [sgbd] (fast-path ones from MESSWERTETAB first). */
    fun measurements(sgbd: String): List<Measurement>

    /** [sgbdsWithMeasurements] with their ECU descriptions (default: names only). */
    fun sgbdInfos(): List<SgbdInfo> = sgbdsWithMeasurements().map { SgbdInfo(it) }
}

/**
 * SGBDs found in the connected car (vehicle identification), lower case without extension. The
 * picker lists them first; [None] until a vehicle detection provides them.
 */
fun interface DetectedEcus {
    fun sgbds(): List<String>

    companion object {
        val None = DetectedEcus { emptyList() }
    }
}

data class RecordingState(
    val recording: Boolean = false,
    val fileName: String? = null,
    val startedAtMs: Long? = null,
    val rows: Long = 0,
    /** Why the last recording stopped by itself (file could not be created / written), else null. */
    val error: String? = null,
)

/** CSV session logger fed by a [LiveDataSource]. */
interface SessionRecorder {
    val state: StateFlow<RecordingState>
    fun start()
    fun stop()

    /** Adds a marker in the current recording (button "Repère"). */
    fun mark(label: String = "")
}

/** Default of [LiveDataSource.unavailable]: nothing. */
val NoUnavailableMeasurements: StateFlow<Map<String, String>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
