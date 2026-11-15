/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dyno

import io.github.jonathangtd.bimmerdata.live.Measurement
import kotlinx.coroutines.flow.StateFlow

/** Where the dyno is. */
enum class DynoPhase { Idle, Armed, Running }

/** Live state of the dyno (screen and API). */
data class DynoLiveState(
    val phase: DynoPhase = DynoPhase.Idle,
    /** The car being measured (the selected car when armed). */
    val carId: String? = null,
    /** Engine speed readings of the run in progress. */
    val runSamples: Int = 0,
    val runStartedAtMs: Long? = null,
    /** Last run kept. */
    val lastRunId: String? = null,
    /** Last run left out because too short: its engine speed readings; null when the last one was kept. */
    val lastDiscardedSamples: Int? = null,
    /** Readings received since armed (all inputs), to show that values come in. */
    val readings: Long = 0,
    /** Latest engine speed while armed. */
    val rpm: Double? = null,
    val pedal: Double? = null,
)

/** Why the dyno cannot be armed; [code] is the API error code. */
class DynoNotReady(val code: String, message: String) : Exception(message)

/**
 * The virtual dyno as the REST API and the screen drive it ([DynoRuntime] on the phone). Runs and
 * inputs are per car; arming uses the selected car.
 */
interface DynoControl {
    val state: StateFlow<DynoLiveState>

    /** Runs and inputs as saved. */
    fun book(): DynoBook

    /** The inputs of car [carId] as saved (blanks = defaults, see [resolvedInputs]). */
    fun inputs(carId: String): DynoInputs = book().inputs[carId] ?: DynoInputs()

    /** [inputs] with the defaults filled in from the active profile's values ([DynoDefaults]). */
    fun resolvedInputs(carId: String): DynoInputs

    /** Values the inputs may be (the active profile's numeric values and the GPS speed). */
    fun candidates(): List<Measurement>

    fun setInputs(carId: String, inputs: DynoInputs)

    /** Arms on the selected car (a run starts at full throttle); throws [DynoNotReady]. */
    fun arm()

    fun disarm()

    /** Starts a run now (arms first when needed); throws [DynoNotReady]. */
    fun start()

    /** Ends the run in progress; stays armed. */
    fun stop()

    fun deleteRun(id: String): Boolean

    fun renameRun(id: String, note: String): Boolean
}
