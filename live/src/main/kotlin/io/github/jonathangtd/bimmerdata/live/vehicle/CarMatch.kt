/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

/** What to do with the car whose short VIN was just read at connection ([CarMatch.decide]). */
sealed interface VinDecision {
    /** The selected car has this VIN: nothing to change. */
    data object Stay : VinDecision

    /** Another known car has this VIN: it becomes the selected car. */
    data class SwitchTo(val id: String) : VinDecision

    /**
     * Unknown VIN, and the selected car has no VIN yet (it was added by hand): this is its first
     * connection, the VIN is attached to it (its id stays, so its profiles stay).
     */
    data class AttachToSelected(val id: String) : VinDecision

    /** Unknown VIN and no car without VIN is selected: a new car. */
    data object NewCar : VinDecision
}

/**
 * Which known car a VIN or a scan result belongs to. Pure functions: the app keeps one car per VIN,
 * and a car added by hand takes the VIN of the first car it is connected to.
 */
object CarMatch {
    /** [v] is the car with short VIN [vinShort] (its id, its short VIN or the end of its full VIN; case ignored). */
    fun matchesVin(v: Vehicle, vinShort: String): Boolean =
        v.id.equals(vinShort, ignoreCase = true) || v.vinShort.equals(vinShort, ignoreCase = true) ||
            v.vin?.endsWith(vinShort, ignoreCase = true) == true

    fun findByVin(vehicles: List<Vehicle>, vinShort: String): Vehicle? = vehicles.firstOrNull { matchesVin(it, vinShort) }

    /**
     * The decision at connection for short VIN [vinShort], [selectedId] being the selected car:
     * a known VIN wins (even over a selected car without VIN), then the selected car without VIN
     * takes it, else it is a new car.
     */
    fun decide(vehicles: List<Vehicle>, selectedId: String?, vinShort: String): VinDecision {
        findByVin(vehicles, vinShort)?.let { known -> return if (known.id == selectedId) VinDecision.Stay else VinDecision.SwitchTo(known.id) }
        val selected = vehicles.firstOrNull { it.id == selectedId }
        return if (selected != null && !selected.hasVin) VinDecision.AttachToSelected(selected.id) else VinDecision.NewCar
    }

    /**
     * Puts the result [found] of a detection (full scan) into [vehicles] ([currentId]: the car
     * selected when it started). It goes into the car with the same VIN, else (no VIN read) into the
     * car the scanner continued ([found] has its id), else into the selected car if that one has no
     * VIN yet; only otherwise it is a new car. A merged car keeps its id, name, model and the SGBDs
     * added by hand; its scanned ECUs are replaced (new ones simply join the list). A car never
     * scanned also keeps the series typed by hand.
     *
     * An ECU of the car's list the detection did not find while its profiles use it ([inUse]: the
     * SGBDs the profiles of a car id use) is not dropped: it stays as added by hand, listed in
     * [ScanMerge.kept], so the app can ask the user whether to take it out of those profiles.
     */
    fun mergeScan(vehicles: List<Vehicle>, currentId: String?, found: Vehicle, inUse: (String) -> Set<String> = { emptySet() }): ScanMerge {
        val current = vehicles.firstOrNull { it.id == currentId }
        val target = found.vinShort?.let { findByVin(vehicles, it) }
            ?: vehicles.firstOrNull { it.id == found.id }
            ?: current?.takeIf { !it.hasVin }
            ?: return ScanMerge(vehicles + found, found, emptyList(), isNew = true)
        val merged = found.copy(
            id = target.id,
            label = target.label,
            vin = found.vin ?: target.vin,
            vinShort = found.vinShort ?: target.vinShort,
            series = if (target.scannedAtMs == null) target.series ?: found.series else found.series,
            model = target.model,
            addedSgbds = target.addedSgbds,
        )
        val used = inUse(target.id).map { it.lowercase() }.toSet()
        val kept = (target.sgbds() - merged.sgbds().toSet()).filter { it.lowercase() in used }
        val car = merged.copy(addedSgbds = merged.addedSgbds + kept)
        return ScanMerge(vehicles.map { if (it.id == target.id) car else it }, car, kept, isNew = false)
    }
}

/** Result of [CarMatch.mergeScan]: the new list, the car the detection now is, the ECUs [kept] although not found. */
data class ScanMerge(val vehicles: List<Vehicle>, val car: Vehicle, val kept: List<String>, val isNew: Boolean)
