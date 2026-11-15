/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.dyno

/** The inputs of a dyno run. */
enum class DynoRole { Rpm, Speed, Pedal, Overlay }

/** Why a run ended. */
enum class DynoEnd { PedalReleased, RpmDropped, GearChanged, Stopped, Disarmed, TooLong, NoData }

/** What happened to the run in progress, for the screen and the store. */
sealed interface DynoEvent {
    data class Started(val atMs: Long, val manual: Boolean) : DynoEvent

    /** A run with enough readings: [capture] holds every reading kept. */
    data class Finished(val capture: DynoCapture, val startedAtMs: Long, val endedAtMs: Long, val end: DynoEnd) : DynoEvent

    /** A run too short to be worth keeping ([rpmSamples] engine speed readings). */
    data class Discarded(val end: DynoEnd, val rpmSamples: Int) : DynoEvent
}

data class DynoDetectorConfig(
    /** Pedal at or above this (%) with the engine speed rising starts an armed run. */
    val startPedalPct: Double = 90.0,
    /** Pedal below this (%) ends the run. */
    val endPedalPct: Double = 80.0,
    /** Engine speed this far below the run's highest ends the run (lift, gear change). */
    val rpmDrop: Double = 250.0,
    /** Speed / rpm ratio this far (relative) from the run's start ends the run (gear change, clutch). */
    val ratioChange: Double = 0.08,
    val maxDurationMs: Long = 60_000,
    /** No engine speed for this long ends the run. */
    val noDataMs: Long = 3_000,
    /** Readings kept before the start (the beginning of the pull). */
    val preRollMs: Long = 1_000,
    val minRpmSamples: Int = DynoProcessor.MIN_SAMPLES,
)

/**
 * Finds the runs in the stream of readings. Pure state machine (one thread at a time; the caller
 * gives the readings in time order).
 *
 * - [arm]: a run starts by itself when the pedal (if [hasPedal]) reaches
 *   [DynoDetectorConfig.startPedalPct] and the engine speed rises; after a run the pedal has to be
 *   released before the next one. Without a pedal input only [start] starts a run.
 * - A run ends on pedal release, engine speed drop, gear change (speed / rpm ratio), [stop],
 *   [disarm], [DynoDetectorConfig.maxDurationMs], or no engine speed for
 *   [DynoDetectorConfig.noDataMs] ([tick]). The readings after the end (the lift) are left out.
 */
class DynoDetector(private val hasPedal: Boolean, private val config: DynoDetectorConfig = DynoDetectorConfig()) {
    enum class State { Idle, Armed, Running }

    var state: State = State.Idle
        private set

    /** Engine speed readings of the run in progress. */
    val runSamples: Int get() = rpm.size

    private var armed = false
    private var startedAt = 0L
    private var manual = false
    private var needRelease = false
    private var pedalHigh = false
    private var lastRpm: TimedValue? = null
    private var maxRpm: TimedValue? = null
    private var refRatio: Double? = null
    private val ratios = ArrayList<Double>()
    private var offRatioSince: Long? = null
    private var lastRpmAt = 0L

    private val rpm = ArrayList<TimedValue>()
    private val speed = ArrayList<TimedValue>()
    private val pedal = ArrayList<TimedValue>()
    private val overlay = ArrayList<TimedValue>()

    /** Recent readings while armed, for the beginning of the next run. */
    private val pre = ArrayDeque<Pair<DynoRole, TimedValue>>()

    fun arm() {
        armed = true
        if (state == State.Idle) state = State.Armed
    }

    /** Disarms; a run in progress ends ([DynoEnd.Disarmed]). */
    fun disarm(nowMs: Long): DynoEvent? {
        armed = false
        pre.clear()
        val ev = if (state == State.Running) finish(nowMs, DynoEnd.Disarmed, null) else null
        state = State.Idle
        return ev
    }

    /** Starts a run now (manual), armed or not. */
    fun start(nowMs: Long): DynoEvent? {
        if (state == State.Running) return null
        begin(nowMs, manual = true, fromMs = nowMs)
        return DynoEvent.Started(nowMs, true)
    }

    /** Ends the run in progress ([DynoEnd.Stopped]); stays armed. */
    fun stop(nowMs: Long): DynoEvent? = if (state == State.Running) finish(nowMs, DynoEnd.Stopped, null) else null

    /** Ends a run without engine speed for too long. */
    fun tick(nowMs: Long): DynoEvent? {
        if (state != State.Running) return null
        if (nowMs - startedAt > config.maxDurationMs) return finish(nowMs, DynoEnd.TooLong, null)
        if (nowMs - maxOf(lastRpmAt, startedAt) > config.noDataMs) return finish(nowMs, DynoEnd.NoData, null)
        return null
    }

    /** One reading of [role]; returns what it started or ended, if anything. */
    fun onValue(role: DynoRole, tMs: Long, value: Double): DynoEvent? {
        if (!value.isFinite()) return null
        val v = TimedValue(tMs, value)
        return when (state) {
            State.Idle -> {
                if (role == DynoRole.Rpm) lastRpm = v
                null
            }
            State.Armed -> armed(role, v)
            State.Running -> running(role, v)
        }
    }

    private fun armed(role: DynoRole, v: TimedValue): DynoEvent? {
        pre.addLast(role to v)
        while (pre.isNotEmpty() && v.tMs - pre.first().second.tMs > config.preRollMs) pre.removeFirst()
        when (role) {
            DynoRole.Pedal -> {
                pedalHigh = v.value >= config.startPedalPct
                if (v.value < config.endPedalPct) needRelease = false
            }
            DynoRole.Rpm -> {
                val prev = lastRpm
                lastRpm = v
                if (hasPedal && pedalHigh && !needRelease && prev != null && v.value > prev.value && v.tMs > prev.tMs) {
                    begin(v.tMs, manual = false, fromMs = prev.tMs)
                    return DynoEvent.Started(prev.tMs, false)
                }
            }
            else -> Unit
        }
        return null
    }

    private fun begin(nowMs: Long, manual: Boolean, fromMs: Long) {
        clearRun()
        state = State.Running
        this.manual = manual
        startedAt = nowMs
        lastRpmAt = nowMs
        // the readings from the start of the pull on (the previous engine speed reading included)
        for ((role, tv) in pre) if (tv.tMs >= fromMs) add(role, tv)
        pre.clear()
        maxRpm = rpm.maxByOrNull { it.value }
    }

    private fun add(role: DynoRole, v: TimedValue) {
        when (role) {
            DynoRole.Rpm -> { rpm += v; lastRpm = v; lastRpmAt = v.tMs }
            DynoRole.Speed -> speed += v
            DynoRole.Pedal -> pedal += v
            DynoRole.Overlay -> overlay += v
        }
    }

    private fun running(role: DynoRole, v: TimedValue): DynoEvent? {
        when (role) {
            DynoRole.Pedal -> if (hasPedal && v.value < config.endPedalPct) {
                // the readings of the same instant are already past the lift
                return finish(v.tMs, DynoEnd.PedalReleased, v.tMs - 1)
            }
            DynoRole.Rpm -> {
                val top = maxRpm
                if (top != null && v.value < top.value - config.rpmDrop) return finish(v.tMs, DynoEnd.RpmDropped, top.tMs)
                if (top == null || v.value >= top.value) maxRpm = v
            }
            DynoRole.Speed -> {
                val r = lastRpm?.takeIf { it.value > 500 && v.value > 5 && rpm.isNotEmpty() }?.let { v.value / it.value }
                if (r != null) {
                    val ref = refRatio
                    if (ref == null) {
                        ratios += r
                        if (ratios.size >= 3) refRatio = ratios.sorted()[ratios.size / 2]
                    } else if (kotlin.math.abs(r - ref) / ref > config.ratioChange) {
                        val since = offRatioSince ?: v.tMs.also { offRatioSince = it }
                        // twice in a row: not a single late reading
                        if (since != v.tMs) return finish(v.tMs, DynoEnd.GearChanged, since - 1)
                    } else {
                        offRatioSince = null
                    }
                }
            }
            DynoRole.Overlay -> Unit
        }
        add(role, v)
        if (v.tMs - startedAt > config.maxDurationMs) return finish(v.tMs, DynoEnd.TooLong, null)
        return null
    }

    /** Ends the run at [nowMs]; readings after [keepUntil] (when given) are left out. */
    private fun finish(nowMs: Long, end: DynoEnd, keepUntil: Long?): DynoEvent {
        var capture = DynoCapture(rpm.toList(), speed.toList(), pedal.toList(), overlay.toList())
        if (keepUntil != null) capture = capture.until(keepUntil)
        val started = capture.rpm.firstOrNull()?.tMs ?: startedAt
        val ended = capture.rpm.lastOrNull()?.tMs ?: nowMs
        clearRun()
        state = if (armed) State.Armed else State.Idle
        // a run that ended with the pedal still down: the next one needs a release first
        needRelease = hasPedal && end != DynoEnd.PedalReleased
        pedalHigh = false
        return if (capture.rpm.size >= config.minRpmSamples) DynoEvent.Finished(capture, started, ended, end)
        else DynoEvent.Discarded(end, capture.rpm.size)
    }

    private fun clearRun() {
        rpm.clear(); speed.clear(); pedal.clear(); overlay.clear()
        maxRpm = null
        refRatio = null
        ratios.clear()
        offRatioSince = null
    }
}
