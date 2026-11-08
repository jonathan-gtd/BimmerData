/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import kotlinx.coroutines.flow.StateFlow

/**
 * What the rest of the app may know about the car: the ECUs detected in the **current vehicle**
 * by the last scan (Faults tab). Read-only and cheap (no ECU traffic).
 *
 * Typical use (dashboard / measurement picker): list "detected in your car" first with
 * `source.sgbds()`, whose names are the same keys as [io.github.jonathangtd.bimmerdata.live.Measurement.sgbd]
 * (lower case, no extension, e.g. "msv70"). Collect [detectedEcus] to follow re-scans and vehicle
 * switches. Empty before the first scan or when no vehicle is selected.
 *
 * Implemented in the app by `io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository.get(context)`.
 */
interface DetectedEcuSource {
    val detectedEcus: StateFlow<List<EcuSummary>>

    /** Variant SGBDs of the detected ECUs that exist in the imported ECU folder, in address order. */
    fun sgbds(): List<String> = detectedEcus.value.filter { it.sgbdAvailable }.mapNotNull { it.sgbd }.distinct()
}

/** A detected ECU, as exposed by [DetectedEcuSource]. */
data class EcuSummary(
    /** Diagnosis address, e.g. 0x12. */
    val address: Int,
    /** Short name, e.g. "DME/DDE". */
    val name: String,
    /** Variant SGBD, lower case without extension (e.g. "msv70"), null if not identified. */
    val sgbd: String?,
    /** Group file, lower case without extension (e.g. "d_motor"). */
    val group: String?,
    /** [sgbd] is present in the imported ECU folder. */
    val sgbdAvailable: Boolean,
)

/** Summaries of a vehicle's ECUs, for [DetectedEcuSource]. */
fun Vehicle?.ecuSummaries(): List<EcuSummary> =
    this?.ecus?.map { EcuSummary(it.address, it.name, it.sgbd, it.group, it.sgbdAvailable) } ?: emptyList()
