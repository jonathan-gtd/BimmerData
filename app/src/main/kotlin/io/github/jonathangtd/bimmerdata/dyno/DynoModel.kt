/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dyno

import io.github.jonathangtd.bimmerdata.dashboard.newId
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.ValueKind
import io.github.jonathangtd.bimmerdata.live.dyno.DynoCapture
import io.github.jonathangtd.bimmerdata.live.dyno.DynoEnd
import io.github.jonathangtd.bimmerdata.live.dyno.DynoParams
import io.github.jonathangtd.bimmerdata.live.dyno.DynoProcessor
import io.github.jonathangtd.bimmerdata.live.dyno.DynoResult
import io.github.jonathangtd.bimmerdata.live.dyno.OverlayUnit
import io.github.jonathangtd.bimmerdata.live.dyno.TimedValue
import io.github.jonathangtd.bimmerdata.live.vehicle.CarPhysics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which values the virtual dyno reads for one car (measurement ids, any ECU, the GPS or a computed
 * value) and the driveline loss used for the engine estimate. Kept per car: the ids are those of
 * the car's ECUs, whatever profile is active. Null = not chosen ([DynoDefaults] picks one).
 */
@Serializable
data class DynoInputs(
    val rpm: String? = null,
    val speed: String? = null,
    val pedal: String? = null,
    val overlay: String? = null,
    val drivelineLossPct: Double = DynoParams.DEFAULT_LOSS_PCT,
)

/** An input of a run as it was when measured. */
@Serializable
data class DynoRunInput(val id: String, val label: String, val unit: String)

/** Readings of one input: times relative to [DynoRun.startedAtMs] (ms) and values. */
@Serializable
data class DynoSeries(val t: List<Long> = emptyList(), val v: List<Double> = emptyList()) {
    fun values(startMs: Long): List<TimedValue> = t.indices.filter { it < v.size }.map { TimedValue(startMs + t[it], v[it]) }

    companion object {
        fun of(list: List<TimedValue>, startMs: Long) = DynoSeries(list.map { it.tMs - startMs }, list.map { it.value })
    }
}

/** One measured pull, with every reading (the curve is computed again when shown, [DynoRuns.result]). */
@Serializable
data class DynoRun(
    val id: String,
    val carId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** [DynoEnd] name. */
    val end: String,
    val manual: Boolean = false,
    val profileName: String? = null,
    val rpm: DynoRunInput,
    val speed: DynoRunInput,
    val pedal: DynoRunInput? = null,
    val overlay: DynoRunInput? = null,
    /** The car's values when measured (used when the car is gone or has no mass any more). */
    val massKg: Double? = null,
    val dragAreaM2: Double? = null,
    val rollingResistance: Double? = null,
    val airDensity: Double? = null,
    val drivelineLossPct: Double = DynoParams.DEFAULT_LOSS_PCT,
    val rpmSeries: DynoSeries,
    val speedSeries: DynoSeries,
    val pedalSeries: DynoSeries = DynoSeries(),
    val overlaySeries: DynoSeries = DynoSeries(),
    /** Name given by the user (e.g. "4th, south"). */
    val note: String = "",
) {
    val physics: CarPhysics get() = CarPhysics(massKg, dragAreaM2, rollingResistance, airDensity)

    fun capture() = DynoCapture(rpmSeries.values(startedAtMs), speedSeries.values(startedAtMs), pedalSeries.values(startedAtMs), overlaySeries.values(startedAtMs))
}

/** Everything the dyno keeps on the phone: inputs per car and the runs (newest last). */
@Serializable
data class DynoBook(
    val version: Int = 1,
    val inputs: Map<String, DynoInputs> = emptyMap(),
    val runs: List<DynoRun> = emptyList(),
) {
    fun runsOf(carId: String): List<DynoRun> = runs.filter { it.carId == carId }
    fun run(id: String): DynoRun? = runs.firstOrNull { it.id == id }
}

object DynoRuns {
    /** Runs kept per car; the oldest go first. */
    const val MAX_RUNS_PER_CAR = 40
    const val MAX_NOTE_LENGTH = 40

    fun add(book: DynoBook, run: DynoRun): DynoBook {
        val mine = book.runsOf(run.carId)
        val drop = if (mine.size >= MAX_RUNS_PER_CAR) mine.sortedBy { it.startedAtMs }.take(mine.size - MAX_RUNS_PER_CAR + 1).map { it.id }.toSet() else emptySet()
        return book.copy(runs = book.runs.filterNot { it.id in drop } + run)
    }

    fun delete(book: DynoBook, id: String): DynoBook = book.copy(runs = book.runs.filterNot { it.id == id })

    fun rename(book: DynoBook, id: String, note: String): DynoBook =
        book.copy(runs = book.runs.map { if (it.id == id) it.copy(note = note.trim().take(MAX_NOTE_LENGTH)) else it })

    /** The book without the runs and inputs of cars not in [carIds] (a car was deleted). */
    fun keepCars(book: DynoBook, carIds: Set<String>): DynoBook =
        book.copy(inputs = book.inputs.filterKeys { it in carIds }, runs = book.runs.filter { it.carId in carIds })

    /**
     * A run as built from a finished capture: the inputs as measured, the car's values then
     * ([physics]), the readings relative to the start.
     */
    fun of(
        carId: String, capture: DynoCapture, startedAtMs: Long, endedAtMs: Long, end: DynoEnd, manual: Boolean, profileName: String?,
        rpm: DynoRunInput, speed: DynoRunInput, pedal: DynoRunInput?, overlay: DynoRunInput?, physics: CarPhysics, lossPct: Double,
        id: String = newId(),
    ): DynoRun {
        val start = minOf(startedAtMs, capture.rpm.firstOrNull()?.tMs ?: startedAtMs)
        return DynoRun(
            id = id, carId = carId, startedAtMs = start, endedAtMs = endedAtMs, end = end.name, manual = manual, profileName = profileName,
            rpm = rpm, speed = speed, pedal = pedal, overlay = overlay,
            massKg = physics.massKg, dragAreaM2 = physics.dragAreaM2, rollingResistance = physics.rollingResistance, airDensity = physics.airDensity,
            drivelineLossPct = lossPct,
            rpmSeries = DynoSeries.of(capture.rpm, start), speedSeries = DynoSeries.of(capture.speed, start),
            pedalSeries = DynoSeries.of(capture.pedal, start), overlaySeries = DynoSeries.of(capture.overlay, start),
        )
    }

    /**
     * The curve of [run], computed now: with the car's current values when they have a mass
     * ([carPhysics], so a corrected mass applies to every run), else with those of the run.
     */
    fun result(run: DynoRun, carPhysics: CarPhysics?): DynoResult {
        val physics = carPhysics?.resolved() ?: run.physics.resolved()
        return DynoProcessor.process(run.capture(), physics, run.drivelineLossPct, run.overlay?.unit?.let(OverlayUnit::of))
    }
}

/** Which measurements suit which input, and the defaults: nothing about a particular car. */
object DynoDefaults {
    private val RPM_UNITS = setOf("rpm", "1/min", "u/min", "tr/min", "min-1", "min^-1", "1/mn")
    private val SPEED_UNITS = setOf("km/h", "kmh", "km/std")
    private val PEDAL_WORDS = Regex("(?i)p[ée]dal|accelerat|fahrpedal|throttle|fpm|apps")

    fun isRpm(m: Measurement) = m.kind == ValueKind.Number && m.unit.trim().lowercase(Locale.ROOT) in RPM_UNITS
    fun isSpeed(m: Measurement) = m.kind == ValueKind.Number && m.unit.trim().lowercase(Locale.ROOT) in SPEED_UNITS
    fun isPercent(m: Measurement) = m.kind == ValueKind.Number && m.unit.trim() == "%"
    fun isOverlay(m: Measurement) = m.kind == ValueKind.Number && OverlayUnit.of(m.unit) != null

    /**
     * [saved] with its blanks filled from [candidates] (the active profile's values, then the GPS):
     * the first engine speed, the first speed of an ECU (more precise and quicker than the GPS),
     * a pedal only when its name says so; never an overlay (the user picks it).
     */
    fun resolve(saved: DynoInputs, candidates: List<Measurement>): DynoInputs {
        val speeds = candidates.filter(::isSpeed)
        return saved.copy(
            rpm = saved.rpm ?: candidates.firstOrNull(::isRpm)?.id,
            speed = saved.speed ?: (speeds.firstOrNull { !it.id.startsWith("gps/") } ?: speeds.firstOrNull())?.id,
            pedal = saved.pedal ?: candidates.firstOrNull { isPercent(it) && PEDAL_WORDS.containsMatchIn(it.label) }?.id,
        )
    }
}

/** Storage and export formats. */
object DynoJson {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(book: DynoBook): String = json.encodeToString(DynoBook.serializer(), book)

    fun decode(text: String): DynoBook = json.decodeFromString(DynoBook.serializer(), text)
}

/** CSV of a run: header lines (#), every reading after smoothing, then the curve by engine speed bins. */
object DynoCsv {
    fun fileName(run: DynoRun, zone: ZoneId = ZoneId.systemDefault()): String =
        "bimmerdata-dyno-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.ofEpochMilli(run.startedAtMs).atZone(zone)) + ".csv"

    fun encode(run: DynoRun, result: DynoResult, carLabel: String?): String = buildString {
        fun n(v: Double?, d: Int = 2) = v?.let { String.format(Locale.ROOT, "%.${d}f", it) }.orEmpty()
        append("# BimmerData virtual dyno\n")
        append("# car,").append(csv(carLabel.orEmpty())).append('\n')
        append("# started,").append(Instant.ofEpochMilli(run.startedAtMs)).append('\n')
        append("# end,").append(run.end).append('\n')
        append("# engine speed,").append(csv(run.rpm.label)).append(" (").append(run.rpm.id).append(")\n")
        append("# speed,").append(csv(run.speed.label)).append(" (").append(run.speed.id).append(")\n")
        run.overlay?.let { append("# overlay,").append(csv(it.label)).append(" (").append(it.id).append(", ").append(it.unit).append(")\n") }
        append("# mass kg,").append(n(run.massKg, 0)).append(",drag area m2,").append(n(run.dragAreaM2, 3))
            .append(",rolling,").append(n(run.rollingResistance, 4)).append(",air density,").append(n(run.airDensity, 3)).append('\n')
        append("# driveline loss %,").append(n(run.drivelineLossPct, 1)).append('\n')
        append("# km/h per 1000 rpm,").append(n(result.kmhPer1000Rpm, 3)).append(",ratio spread %,").append(n(result.ratioSpreadPct, 2)).append('\n')
        append("# samples,").append(result.samples).append(",rate Hz,").append(n(result.sampleRateHz, 2)).append('\n')
        result.problem?.let { append("# problem,").append(it.name).append('\n') }
        append("# road assumed flat; rotating parts not counted; torque = wheel power at the engine, transmission losses not counted\n")
        append("time_ms,rpm_read,rpm_smoothed,speed_kmh,accel_ms2,wheel_power_ch,torque_nm,overlay\n")
        for (p in result.processed) {
            append(p.tMs - run.startedAtMs).append(',').append(n(p.rpmRaw, 0)).append(',').append(n(p.rpm, 1)).append(',').append(n(p.speedKmh, 2))
                .append(',').append(n(p.accelMs2, 3)).append(',').append(n(p.wheelPowerCh, 1)).append(',').append(n(p.torqueNm, 1)).append(',').append(n(p.overlay, 1)).append('\n')
        }
        append('\n')
        append("rpm,wheel_power_ch,torque_nm,engine_power_ch_estimated,overlay_torque_nm,overlay_power_ch,samples\n")
        for (p in result.points) {
            append(n(p.rpm, 0)).append(',').append(n(p.wheelPowerCh, 1)).append(',').append(n(p.torqueNm, 1)).append(',').append(n(p.enginePowerCh, 1))
                .append(',').append(n(p.overlayTorqueNm, 1)).append(',').append(n(p.overlayPowerCh, 1)).append(',').append(p.samples).append('\n')
        }
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
