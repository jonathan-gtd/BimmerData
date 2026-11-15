/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

/**
 * What the virtual dyno needs to know about a car ([Vehicle.physics]), typed by the user: every
 * field is optional, null = not set. Only [massKg] is required for a measurement; the others have
 * defaults ([resolved]).
 */
data class CarPhysics(
    /** Mass while measuring, driver and fuel included (kg). */
    val massKg: Double? = null,
    /** Drag area Cd × A (m²); null = [DEFAULT_DRAG_AREA]. */
    val dragAreaM2: Double? = null,
    /** Rolling resistance coefficient; null = [DEFAULT_ROLLING]. */
    val rollingResistance: Double? = null,
    /** Air density (kg/m³); null = [DEFAULT_AIR_DENSITY]. */
    val airDensity: Double? = null,
) {
    val isEmpty: Boolean get() = massKg == null && dragAreaM2 == null && rollingResistance == null && airDensity == null

    /** The values used for a computation, defaults filled in; null without a mass. */
    fun resolved(): Resolved? = massKg?.let {
        Resolved(it, dragAreaM2 ?: DEFAULT_DRAG_AREA, rollingResistance ?: DEFAULT_ROLLING, airDensity ?: DEFAULT_AIR_DENSITY)
    }

    /** First field out of its range, as "field: allowed range"; null when all are valid. */
    fun problem(): String? = when {
        massKg != null && massKg !in MASS_RANGE -> "massKg must be in ${MASS_RANGE.start.toInt()}..${MASS_RANGE.endInclusive.toInt()}"
        dragAreaM2 != null && dragAreaM2 !in DRAG_AREA_RANGE -> "dragAreaM2 must be in ${DRAG_AREA_RANGE.start}..${DRAG_AREA_RANGE.endInclusive}"
        rollingResistance != null && rollingResistance !in ROLLING_RANGE -> "rollingResistance must be in ${ROLLING_RANGE.start}..${ROLLING_RANGE.endInclusive}"
        airDensity != null && airDensity !in AIR_DENSITY_RANGE -> "airDensity must be in ${AIR_DENSITY_RANGE.start}..${AIR_DENSITY_RANGE.endInclusive}"
        else -> null
    }

    data class Resolved(val massKg: Double, val dragAreaM2: Double, val rollingResistance: Double, val airDensity: Double)

    companion object {
        const val DEFAULT_DRAG_AREA = 0.65
        const val DEFAULT_ROLLING = 0.012
        const val DEFAULT_AIR_DENSITY = 1.2
        val MASS_RANGE = 300.0..6000.0
        val DRAG_AREA_RANGE = 0.1..3.0
        val ROLLING_RANGE = 0.003..0.05
        val AIR_DENSITY_RANGE = 0.8..1.5
    }
}
