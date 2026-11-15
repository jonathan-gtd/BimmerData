/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.dyno

import io.github.jonathangtd.bimmerdata.live.vehicle.CarPhysics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** One reading of a dyno input: time (epoch ms) and value. */
data class TimedValue(val tMs: Long, val value: Double)

/**
 * Everything a run read, at the acquisition's own rate (nothing averaged): engine speed (rpm),
 * vehicle speed (km/h), and optionally the pedal (%) and a value to compare with ([overlay], a
 * torque or a power given by the car, see [OverlayUnit]).
 */
data class DynoCapture(
    val rpm: List<TimedValue>,
    val speed: List<TimedValue>,
    val pedal: List<TimedValue> = emptyList(),
    val overlay: List<TimedValue> = emptyList(),
) {
    /** The capture without the values after [lastMs] (inclusive bound). */
    fun until(lastMs: Long) = DynoCapture(rpm.filter { it.tMs <= lastMs }, speed.filter { it.tMs <= lastMs }, pedal.filter { it.tMs <= lastMs }, overlay.filter { it.tMs <= lastMs })
}

/** What an overlay value is, from its unit: a torque (N·m) or a power ([toCh] converts it to ch). */
enum class OverlayUnit(val toCh: Double) {
    Torque(1.0), Ch(1.0), Kw(KW_TO_CH), Hp(HP_TO_CH);

    companion object {
        /** The overlay kind of a measurement unit; null when it is neither a torque nor a power. */
        fun of(unit: String): OverlayUnit? = when (unit.trim().lowercase().replace(" ", "")) {
            "nm", "n·m", "n.m", "n*m" -> Torque
            "ch", "ps", "cv", "pk" -> Ch
            "kw" -> Kw
            "hp", "bhp", "whp" -> Hp
            else -> null
        }
    }
}

const val KW_TO_CH = 1.35962
const val HP_TO_CH = 1.01387

/** N·m × rpm per kW (60000 / 2π). */
private const val NM_RPM_PER_KW = 60000.0 / (2 * PI)

private const val G = 9.80665

/** Settings of one computation: the car ([CarPhysics.Resolved]) and the driveline loss used for the engine estimate. */
data class DynoParams(
    val physics: CarPhysics.Resolved,
    /** Share of the engine power lost in the gearbox, shafts and tyres (%), for [DynoPoint.enginePowerCh]. */
    val drivelineLossPct: Double = DEFAULT_LOSS_PCT,
    val overlayUnit: OverlayUnit? = null,
) {
    companion object {
        const val DEFAULT_LOSS_PCT = 15.0
        val LOSS_RANGE = 0.0..40.0
    }
}

/** Why a run gave no curve. */
enum class DynoProblem { NoMass, TooFewSamples, NoSpeed, NotOneGear, NotAccelerating }

/** What to keep in mind about a curve. [AssumedFlat] is always there: the road slope is not known. */
enum class DynoWarning { FewSamples, LowSampleRate, AssumedFlat }

/**
 * One point of the curve (an engine speed bin), all the samples of the bin averaged.
 *
 * - [wheelPowerCh]: the power that reaches the road (what moves the car, plus drag and rolling).
 * - [torqueNm]: that power divided by the engine's angular speed, i.e. the torque at the crankshaft
 *   that would give it with no driveline loss ("at the engine, transmission losses not counted").
 * - [enginePowerCh]: the wheel power with [DynoParams.drivelineLossPct] added back (an estimate).
 * - [overlayTorqueNm] / [overlayPowerCh]: the car's own value (the overlay input), both ways.
 */
data class DynoPoint(
    val rpm: Double,
    val wheelPowerCh: Double,
    val torqueNm: Double,
    val enginePowerCh: Double,
    val overlayTorqueNm: Double? = null,
    val overlayPowerCh: Double? = null,
    val samples: Int = 1,
)

/** One sample after smoothing (CSV export): engine speed, speed from the gear ratio, acceleration, power. */
data class DynoSamplePoint(
    val tMs: Long,
    val rpmRaw: Double,
    val rpm: Double,
    val speedKmh: Double,
    val accelMs2: Double,
    val wheelPowerCh: Double,
    val torqueNm: Double,
    val overlay: Double? = null,
)

data class DynoResult(
    val problem: DynoProblem? = null,
    val warnings: List<DynoWarning> = emptyList(),
    /** Gear ratio as km/h per 1000 rpm (least squares over the run). */
    val kmhPer1000Rpm: Double? = null,
    /** RMS difference between the speed read and the gear ratio × rpm (%). */
    val ratioSpreadPct: Double? = null,
    val samples: Int = 0,
    val sampleRateHz: Double = 0.0,
    val durationS: Double = 0.0,
    val rpmFrom: Double? = null,
    val rpmTo: Double? = null,
    val binRpm: Int = 0,
    val points: List<DynoPoint> = emptyList(),
    val processed: List<DynoSamplePoint> = emptyList(),
    val peakWheelPowerCh: Double? = null,
    val peakWheelPowerRpm: Double? = null,
    val peakEnginePowerCh: Double? = null,
    val peakTorqueNm: Double? = null,
    val peakTorqueRpm: Double? = null,
    val peakOverlayTorqueNm: Double? = null,
    val peakOverlayPowerCh: Double? = null,
) {
    val ok: Boolean get() = problem == null && points.isNotEmpty()
}

/**
 * The virtual dyno: power at the wheels from how fast the car accelerates during a full-throttle
 * pull in one gear. Pure; nothing about a particular car or ECU.
 *
 * 1. In one gear the speed is proportional to the engine speed: v = k × rpm. k is fitted by least
 *    squares on the speed readings; a run whose speed / rpm ratio moves more than
 *    [MAX_RATIO_SPREAD] (gear change, clutch slipping, wheel spin) is refused.
 * 2. The engine speed is far finer than a speed read in whole km/h, so the speed used is k × rpm,
 *    rpm smoothed by a local quadratic fit over [SMOOTH_WINDOW_MS] (at least [SMOOTH_MIN_POINTS]
 *    readings, which suits irregular readings from 2 to 10 per second and noisy ones); its slope gives the
 *    acceleration.
 * 3. Power at the wheels = m·a·v + ½·ρ·CdA·v³ + Crr·m·g·v. The road is assumed flat and the
 *    rotating parts (wheels, gearbox, engine) are not counted: a low gear reads a little low.
 * 4. Averaged by engine speed bins of 100 to 250 rpm for the curve; peaks from the bins.
 */
object DynoProcessor {
    const val MIN_SAMPLES = 6
    const val FEW_SAMPLES = 15
    const val LOW_RATE_HZ = 3.0
    const val MAX_RATIO_SPREAD = 0.03
    /** Speed resolution allowance (a speed read in whole km/h is ±0.5 km/h). */
    const val SPEED_QUANTUM_KMH = 0.6
    /** Share of speed readings allowed off the ratio before the run is refused. */
    const val MAX_OFF_RATIO_SHARE = 0.15
    const val SMOOTH_WINDOW_MS = 1500L
    const val SMOOTH_MIN_POINTS = 7
    const val MIN_ACCEL_MS2 = 0.05

    fun process(capture: DynoCapture, physics: CarPhysics.Resolved?, lossPct: Double = DynoParams.DEFAULT_LOSS_PCT, overlayUnit: OverlayUnit? = null): DynoResult =
        if (physics == null) DynoResult(DynoProblem.NoMass, listOf(DynoWarning.AssumedFlat))
        else process(capture, DynoParams(physics, lossPct.coerceIn(DynoParams.LOSS_RANGE), overlayUnit))

    fun process(capture: DynoCapture, params: DynoParams): DynoResult {
        val rpm = clean(capture.rpm).filter { it.value > 300.0 }
        val warnings = ArrayList<DynoWarning>()
        val durationS = if (rpm.size > 1) (rpm.last().tMs - rpm.first().tMs) / 1000.0 else 0.0
        val rate = if (durationS > 0) (rpm.size - 1) / durationS else 0.0
        val base = DynoResult(samples = rpm.size, sampleRateHz = rate, durationS = durationS, rpmFrom = rpm.firstOrNull()?.value, rpmTo = rpm.maxOfOrNull { it.value })
        if (rpm.size < MIN_SAMPLES) return base.copy(problem = DynoProblem.TooFewSamples, warnings = listOf(DynoWarning.AssumedFlat))
        if (rpm.size < FEW_SAMPLES) warnings += DynoWarning.FewSamples
        if (rate < LOW_RATE_HZ) warnings += DynoWarning.LowSampleRate
        warnings += DynoWarning.AssumedFlat

        // 1. gear ratio
        val t0 = rpm.first().tMs - 250
        val t1 = rpm.last().tMs + 250
        val pairs = clean(capture.speed).filter { it.tMs in t0..t1 && it.value > 3.0 }.map { s -> s.value to interpolate(rpm, s.tMs) }
        if (pairs.size < 3) return base.copy(problem = DynoProblem.NoSpeed, warnings = warnings)
        val k = pairs.sumOf { it.first * it.second } / pairs.sumOf { it.second * it.second }
        var off = 0
        var sq = 0.0
        for ((v, r) in pairs) {
            val fit = k * r
            val res = v - fit
            if (abs(res) > MAX_RATIO_SPREAD * fit + SPEED_QUANTUM_KMH) off++
            sq += (res / fit) * (res / fit)
        }
        val spread = sqrt(sq / pairs.size) * 100
        val withRatio = base.copy(kmhPer1000Rpm = k * 1000, ratioSpreadPct = spread, warnings = warnings)
        if (off > MAX_OFF_RATIO_SHARE * pairs.size) return withRatio.copy(problem = DynoProblem.NotOneGear)

        // 2. smoothed engine speed and its slope
        val p = params.physics
        val overlay = clean(capture.overlay)
        val points = ArrayList<DynoSamplePoint>()
        val first = rpm.first().tMs
        val last = rpm.last().tMs
        for ((i, s) in rpm.withIndex()) {
            val (r, slope) = localQuadratic(rpm, i)
            // the first and last instants have a one-sided fit: kept only when the run is short
            if (rpm.size >= 2 * MIN_SAMPLES && (s.tMs - first < 200 || last - s.tMs < 200)) continue
            val vKmh = k * r
            val v = vKmh / 3.6
            val a = k * slope / 3.6
            val watts = p.massKg * a * v + 0.5 * p.airDensity * p.dragAreaM2 * v * v * v + p.rollingResistance * p.massKg * G * v
            val omega = r * 2 * PI / 60
            val ch = watts / 1000 * KW_TO_CH
            val torque = if (omega > 0) watts / omega else 0.0
            val ov = if (overlay.isEmpty() || s.tMs < overlay.first().tMs - 500 || s.tMs > overlay.last().tMs + 500) null else interpolate(overlay, s.tMs)
            points += DynoSamplePoint(s.tMs, s.value, r, vKmh, a, ch, torque, ov)
        }
        if (points.size < MIN_SAMPLES / 2 + 1) return withRatio.copy(problem = DynoProblem.TooFewSamples)
        val medianAccel = points.map { it.accelMs2 }.sorted()[points.size / 2]
        if (medianAccel < MIN_ACCEL_MS2) return withRatio.copy(problem = DynoProblem.NotAccelerating, processed = points)

        // 3. curve by engine speed bins
        val keep = 1.0 - params.drivelineLossPct / 100.0
        val lo = points.minOf { it.rpm }
        val hi = points.maxOf { it.rpm }
        val bin = binWidth(hi - lo, points.size)
        val bins = points.groupBy { floor(it.rpm / bin).toInt() }.toSortedMap().map { (_, list) ->
            val power = list.map { it.wheelPowerCh }.average()
            val center = list.map { it.rpm }.average()
            val torque = list.map { it.torqueNm }.average()
            val ovs = list.mapNotNull { it.overlay }
            val ovAvg = if (ovs.isEmpty()) null else ovs.average()
            val (ovTorque, ovPower) = when (val u = params.overlayUnit) {
                null -> null to null
                OverlayUnit.Torque -> ovAvg to ovAvg?.let { it * center / NM_RPM_PER_KW * KW_TO_CH }
                else -> ovAvg?.let { it * u.toCh / KW_TO_CH * NM_RPM_PER_KW / center } to ovAvg?.let { it * u.toCh }
            }
            DynoPoint(center, power, torque, power / keep, ovTorque, ovPower, list.size)
        }
        val peakP = bins.maxByOrNull { it.wheelPowerCh }
        val peakT = bins.maxByOrNull { it.torqueNm }
        return withRatio.copy(
            binRpm = bin, points = bins, processed = points,
            peakWheelPowerCh = peakP?.wheelPowerCh, peakWheelPowerRpm = peakP?.rpm, peakEnginePowerCh = peakP?.enginePowerCh,
            peakTorqueNm = peakT?.torqueNm, peakTorqueRpm = peakT?.rpm,
            peakOverlayTorqueNm = bins.mapNotNull { it.overlayTorqueNm }.maxOrNull(),
            peakOverlayPowerCh = bins.mapNotNull { it.overlayPowerCh }.maxOrNull(),
        )
    }

    /** Sorted by time, one value per instant (the last one), finite values only. */
    internal fun clean(list: List<TimedValue>): List<TimedValue> =
        list.filter { it.value.isFinite() }.sortedBy { it.tMs }.let { s -> s.filterIndexed { i, v -> i == s.lastIndex || s[i + 1].tMs != v.tMs } }

    /** Linear interpolation of [series] (sorted) at [t]; the end values outside. */
    internal fun interpolate(series: List<TimedValue>, t: Long): Double {
        if (t <= series.first().tMs) return series.first().value
        if (t >= series.last().tMs) return series.last().value
        var lo = 0
        var hi = series.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (series[mid].tMs <= t) lo = mid else hi = mid
        }
        val a = series[lo]
        val b = series[hi]
        return a.value + (b.value - a.value) * (t - a.tMs) / (b.tMs - a.tMs).toDouble()
    }

    /** Bin width (rpm): about two samples per bin, 100 to 250 rpm, a multiple of 50. */
    internal fun binWidth(span: Double, samples: Int): Int {
        val raw = span / max(1.0, samples / 2.0)
        return (ceil(raw / 50.0) * 50.0).toInt().coerceIn(100, 250)
    }

    /**
     * Value and slope (per second) at reading [i] of a weighted quadratic fit over the readings
     * within ±[SMOOTH_WINDOW_MS]/2 (at least the [SMOOTH_MIN_POINTS] nearest); a straight line when
     * the quadratic is not determined.
     */
    internal fun localQuadratic(series: List<TimedValue>, i: Int): Pair<Double, Double> {
        val center = series[i].tMs
        var lo = i
        var hi = i
        while (lo > 0 && center - series[lo - 1].tMs <= SMOOTH_WINDOW_MS / 2) lo--
        while (hi < series.lastIndex && series[hi + 1].tMs - center <= SMOOTH_WINDOW_MS / 2) hi++
        while (hi - lo + 1 < SMOOTH_MIN_POINTS && (lo > 0 || hi < series.lastIndex)) {
            val left = if (lo > 0) center - series[lo - 1].tMs else Long.MAX_VALUE
            val right = if (hi < series.lastIndex) series[hi + 1].tMs - center else Long.MAX_VALUE
            if (left <= right) lo-- else hi++
        }
        val reach = max(1L, maxOf(center - series[lo].tMs, series[hi].tMs - center)) * 1.25
        // weighted sums for y = a + b·x + c·x², x in seconds from the center
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var y0 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (j in lo..hi) {
            val dt = (series[j].tMs - center).toDouble()
            val u = abs(dt) / reach
            val w = (1 - u * u * u).let { it * it * it }
            val x = dt / 1000.0
            val y = series[j].value
            s0 += w; s1 += w * x; s2 += w * x * x; s3 += w * x * x * x; s4 += w * x * x * x * x
            y0 += w * y; y1 += w * x * y; y2 += w * x * x * y
        }
        val det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
        if (hi - lo >= 2 && abs(det) > 1e-12) {
            val a = (y0 * (s2 * s4 - s3 * s3) - s1 * (y1 * s4 - s3 * y2) + s2 * (y1 * s3 - s2 * y2)) / det
            val b = (s0 * (y1 * s4 - y2 * s3) - y0 * (s1 * s4 - s3 * s2) + s2 * (s1 * y2 - y1 * s2)) / det
            return a to b
        }
        val d2 = s0 * s2 - s1 * s1
        if (abs(d2) < 1e-12) return series[i].value to 0.0
        val b = (s0 * y1 - s1 * y0) / d2
        val a = (y0 - b * s1) / s0
        return a to b
    }
}
