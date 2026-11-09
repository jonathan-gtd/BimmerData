/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.cars

import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.newId
import io.github.jonathangtd.bimmerdata.graph.Graph
import io.github.jonathangtd.bimmerdata.live.vehicle.CarMatch
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VinDecision
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileExport
import io.github.jonathangtd.bimmerdata.profiles.ProfileJson
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import io.github.jonathangtd.bimmerdata.vehicle.Garage

/**
 * The user's cars (the garage, [Garage.currentId] = the selected car) and every profile, read and
 * changed together: each profile belongs to one car ([Profile.carId]) and the active profile is
 * always one of the selected car's.
 */
data class CarSpace(val garage: Garage = Garage(), val profiles: ProfileSet = ProfileSet()) {
    val cars: List<Vehicle> get() = garage.vehicles
    val selected: Vehicle? get() = garage.current

    fun car(id: String): Vehicle? = cars.firstOrNull { it.id == id }

    /** Profiles of car [carId], in list order. */
    fun profilesOf(carId: String): List<Profile> = profiles.profiles.filter { it.carId == carId }

    /** [car] in place of the car with its id. */
    fun withCar(car: Vehicle): CarSpace = copy(garage = garage.copy(vehicles = cars.map { if (it.id == car.id) car else it }))
}

/** What reading the VIN at connection did ([Cars.onVin]), for the message to the user. */
sealed interface VinOutcome {
    /** The selected car is that one, or nothing could be done. */
    data object Unchanged : VinOutcome

    /** Another known car is now selected, with its last used [profile] (null: it has none). */
    data class Switched(val car: Vehicle, val profile: Profile?) : VinOutcome

    /** Another known car, but a recording runs: its last used [profile] is only proposed. */
    data class Suggested(val car: Vehicle, val profile: Profile) : VinOutcome

    /** The selected car (added by hand) got its VIN at its first connection. */
    data class Attached(val car: Vehicle) : VinOutcome

    /** A new car; not [selected] while a recording runs. */
    data class Created(val car: Vehicle, val selected: Boolean) : VinOutcome
}

/**
 * Pure operations on a [CarSpace] ([CarBook] applies them to the garage and the profile store under
 * the profile lock; the REST API uses them through its backend).
 *
 * ECU lists: a car's list ([Vehicle.sgbds]) holds the ECUs its profiles may name, and every ECU a
 * profile names is in its car's list. The profile editor and the API only let the user pick ECUs
 * of the list ([foreignEcus]), except for a car without list yet; a profile given to a car (created
 * on a car without list, copied, imported, migrated) brings its ECUs to the list ([bringEcus]).
 * Nothing leaves a profile silently: an ECU that leaves the car's list while profiles use it is
 * kept until the user chooses to take it out of them too ([removeEcu], [EcuUsage]).
 */
object Cars {
    const val MAX_CARS = 20
    const val MAX_LABEL_LENGTH = 40
    const val MAX_FIELD_LENGTH = Profiles.MAX_FIELD_LENGTH

    /** ECUs a user may add by hand to one car (a scanned E9x has about 40). */
    const val MAX_ADDED_SGBDS = 64

    /** Id of a car made without VIN (by hand, or by the migration); the VIN read later does not change it. */
    fun newCarId(): String = "car-" + newId()

    fun cleanLabel(label: String): String = label.trim().replace(Regex("\\s+"), " ").take(MAX_LABEL_LENGTH)

    private fun cleanField(s: String?): String? = s?.trim()?.take(MAX_FIELD_LENGTH)?.takeIf { it.isNotEmpty() }

    fun cleanSgbds(list: List<String>): List<String> = Profiles.normalizeSgbds(list, MAX_ADDED_SGBDS)

    /**
     * Adds a car made by hand (no VIN until its first connection). It becomes the selected car only
     * when no car was selected. Throws [IllegalArgumentException] for an empty name or too many cars.
     */
    fun create(
        space: CarSpace, label: String, series: String? = null, model: String? = null, sgbds: List<String> = emptyList(),
        nowMs: Long, id: String = newCarId(),
    ): Pair<CarSpace, Vehicle> {
        require(space.cars.size < MAX_CARS) { "At most $MAX_CARS cars" }
        val name = cleanLabel(label)
        require(name.isNotEmpty()) { "A car needs a name" }
        val car = Vehicle(id = id, label = name, series = cleanField(series), model = cleanField(model), addedSgbds = cleanSgbds(sgbds))
        val garage = space.garage.copy(vehicles = space.cars + car, currentId = space.garage.currentId ?: car.id)
        return CarSpace(garage, align(space.profiles, garage.currentId, nowMs)) to car
    }

    /** Selects car [id] and activates its last used profile (none if it has none); unchanged for an unknown id. */
    fun select(space: CarSpace, id: String, nowMs: Long): CarSpace {
        if (space.car(id) == null) return space
        return CarSpace(space.garage.copy(currentId = id), align(space.profiles, id, nowMs))
    }

    /**
     * Keeps the active profile among car [carId]'s: unchanged when it is one of them, else that
     * car's last used profile, else none.
     */
    fun align(set: ProfileSet, carId: String?, nowMs: Long): ProfileSet {
        if (carId != null && set.active?.carId == carId) return set
        val last = carId?.let { c -> set.profiles.filter { it.carId == c }.maxByOrNull { it.lastUsedMs } }
        return when {
            last != null -> Profiles.activate(set, last.id, nowMs)
            set.activeId == null -> set
            else -> set.copy(activeId = null)
        }
    }

    /**
     * Changes the fields given (null = unchanged; "" removes [series] / [model]). [sgbds] is the new
     * ECU list by hand: the ECUs found by the last scan always stay, so only the others are kept as
     * [Vehicle.addedSgbds].
     */
    fun update(space: CarSpace, id: String, label: String? = null, series: String? = null, model: String? = null, sgbds: List<String>? = null): CarSpace {
        val car = space.car(id) ?: return space
        val scanned = car.ecus.mapNotNull { it.sgbd }.toSet()
        return space.withCar(
            car.copy(
                label = label?.let(::cleanLabel)?.takeIf { it.isNotEmpty() } ?: car.label,
                series = if (series != null) cleanField(series) else car.series,
                model = if (model != null) cleanField(model) else car.model,
                addedSgbds = sgbds?.let { list -> cleanSgbds(list).filter { it !in scanned } } ?: car.addedSgbds,
            ),
        )
    }

    /**
     * Deletes car [id] with its profiles and its fault read. When it was selected, the first car
     * left is selected (with its last used profile).
     */
    fun delete(space: CarSpace, id: String, nowMs: Long): CarSpace {
        if (space.car(id) == null) return space
        val left = space.cars.filterNot { it.id == id }
        val current = if (space.garage.currentId == id) left.firstOrNull()?.id else space.garage.currentId
        val garage = space.garage.copy(vehicles = left, faults = space.garage.faults - id, currentId = current)
        val kept = space.profiles.profiles.filterNot { it.carId == id }
        val set = space.profiles.copy(profiles = kept, activeId = space.profiles.activeId?.takeIf { a -> kept.any { it.id == a } })
        return CarSpace(garage, align(set, current, nowMs))
    }

    /** Activates profile [profileId] and selects its car. */
    fun activateProfile(space: CarSpace, profileId: String, nowMs: Long): CarSpace {
        val p = space.profiles.profile(profileId) ?: return space
        val garage = if (space.car(p.carId) != null) space.garage.copy(currentId = p.carId) else space.garage
        return CarSpace(garage, Profiles.activate(space.profiles, profileId, nowMs))
    }

    /** The selected car; without one the first car is selected, without any car one named [defaultLabel] is made. */
    fun ensureSelected(space: CarSpace, defaultLabel: String, nowMs: Long): Pair<CarSpace, Vehicle> {
        space.selected?.let { return space to it }
        space.cars.firstOrNull()?.let { return select(space, it.id, nowMs) to it }
        return create(space, defaultLabel, nowMs = nowMs)
    }

    /** The ECUs of [ecus] that [car] does not have; none when the car has no ECU list yet (it takes them, [bringEcus]). */
    fun foreignEcus(car: Vehicle, ecus: List<String>): List<String> {
        val allowed = car.sgbds()
        return if (allowed.isEmpty()) emptyList() else ecus.filter { it !in allowed }
    }

    /** The ECUs of [ecus] missing from car [carId]'s list are added to it (by hand), so its profiles only name ECUs of their car. */
    fun bringEcus(space: CarSpace, carId: String, ecus: List<String>): CarSpace {
        val car = space.car(carId) ?: return space
        val missing = ecus.filter { it !in car.sgbds() }
        if (missing.isEmpty()) return space
        return space.withCar(car.copy(addedSgbds = cleanSgbds(car.addedSgbds + missing)))
    }

    /**
     * Which profiles of car [carId] use each of [sgbds] (main ECUs, cards, graphs): the ECUs that
     * may not leave the car's list without asking. Only the ECUs used appear.
     */
    fun ecusInUse(space: CarSpace, carId: String, sgbds: Collection<String>): Map<String, List<EcuUse>> =
        sgbds.associateWith { EcuUsage.uses(space.profilesOf(carId), it) }.filterValues { it.isNotEmpty() }

    /**
     * The user's "Remove": [sgbd] leaves car [carId]'s ECUs added by hand and every profile of
     * that car ([EcuUsage.remove]: main ECUs, cards, graph values). The ECUs found by the last
     * detection stay in the list (a new detection decides about them).
     */
    fun removeEcu(space: CarSpace, carId: String, sgbd: String): CarSpace {
        val car = space.car(carId) ?: return space
        val withCar = space.withCar(car.copy(addedSgbds = car.addedSgbds.filterNot { it.equals(sgbd, ignoreCase = true) }))
        val set = withCar.profiles.copy(profiles = withCar.profiles.profiles.map { p -> if (p.carId == carId) EcuUsage.remove(p, sgbd) else p })
        return withCar.copy(profiles = set)
    }

    /** Undo of [removeEcu]: car [carId] and its profiles as they were in [before] (other cars untouched). */
    fun restoreCar(space: CarSpace, before: CarSpace, carId: String): CarSpace {
        val car = before.car(carId) ?: return space
        val old = before.profilesOf(carId).associateBy { it.id }
        val set = space.profiles.copy(profiles = space.profiles.profiles.map { old[it.id] ?: it })
        return space.withCar(car).copy(profiles = set)
    }

    /**
     * New profile of car [carId] (it must exist). When [activate] it becomes the active profile and
     * its car the selected one; else the active profile stays. The caller checks [foreignEcus] first.
     */
    fun createProfile(
        space: CarSpace, carId: String, name: String, vehicle: ProfileVehicle, ecus: List<String>, nowMs: Long,
        dashboard: Dashboard = Dashboard(), graphs: List<Graph> = emptyList(), activate: Boolean = true,
    ): Pair<CarSpace, Profile> {
        require(space.car(carId) != null) { "No car '$carId'" }
        val (set, p) = Profiles.create(space.profiles, name, vehicle, ecus, nowMs, dashboard, graphs, carId)
        return settle(bringEcus(space.copy(profiles = set), carId, p.ecus), p, space.profiles.activeId, activate, nowMs) to p
    }

    /**
     * Changes name, metadata and / or ECUs of profile [id] (null = unchanged); the car's list gets
     * the new ECUs it lacks ([bringEcus]). The cards and graph values of [dropCards] (ECUs the user
     * unticked and chose to remove with their cards) are deleted ([EcuUsage.remove]).
     */
    fun editProfile(
        space: CarSpace, id: String, name: String?, vehicle: ProfileVehicle?, ecus: List<String>?, dropCards: Collection<String> = emptyList(),
    ): CarSpace {
        var set = Profiles.edit(space.profiles, id, name, vehicle, ecus)
        if (dropCards.isNotEmpty()) set = set.copy(profiles = set.profiles.map { p -> if (p.id == id) dropCards.fold(p, EcuUsage::remove) else p })
        val p = set.profile(id) ?: return space
        return bringEcus(space.copy(profiles = set), p.carId, p.ecus)
    }

    /**
     * Copy of profile [profileId] (pages, graphs, metadata, ECUs) onto car [carId], whose list gets
     * the ECUs it lacks ([bringEcus]); never used yet, so it is not activated. Null when either is unknown.
     */
    fun copyProfile(space: CarSpace, profileId: String, carId: String, nowMs: Long): Pair<CarSpace, Profile>? {
        val src = space.profiles.profile(profileId) ?: return null
        space.car(carId) ?: return null
        require(space.profiles.profiles.size < Profiles.MAX_PROFILES) { "At most ${Profiles.MAX_PROFILES} profiles" }
        val copy = src.copy(id = newId(), carId = carId, name = Profiles.uniqueName(space.profiles, src.name), lastUsedMs = 0)
        val set = space.profiles.copy(profiles = space.profiles.profiles + copy)
        val withCopy = bringEcus(space.copy(profiles = set), carId, copy.ecus)
        // the selected car had no profile: the copy is its only one, so it is shown
        return withCopy.copy(profiles = align(withCopy.profiles, withCopy.garage.currentId, nowMs)) to copy
    }

    /** A shared profile file as a new profile of car [carId] (it must exist), whose list gets the file's ECUs it lacks. */
    fun importProfile(
        space: CarSpace, export: ProfileExport, carId: String, nowMs: Long, fallbackName: String, activate: Boolean = true,
    ): Pair<CarSpace, Profile> {
        require(space.car(carId) != null) { "No car '$carId'" }
        val (set, p) = ProfileJson.importInto(space.profiles, export, nowMs, fallbackName, carId)
        return settle(bringEcus(space.copy(profiles = set), carId, p.ecus), p, space.profiles.activeId, activate, nowMs) to p
    }

    /** After adding [p]: activate it (and select its car), or put [previousActive] back. */
    private fun settle(space: CarSpace, p: Profile, previousActive: String?, activate: Boolean, nowMs: Long): CarSpace =
        if (activate) activateProfile(space, p.id, nowMs)
        else space.copy(profiles = align(space.profiles.copy(activeId = previousActive), space.garage.currentId, nowMs))

    /**
     * The short VIN [vinShort] was read at connection ([CarMatch.decide]): switch to the known car
     * (with its last used profile), attach the VIN to the selected car added by hand, or add a new
     * car named [newLabel] (id: the short VIN, like a scan). While [recording], nothing that changes
     * the active profile happens: another car's profile is only proposed, a new car is not selected.
     */
    fun onVin(space: CarSpace, vinShort: String, recording: Boolean, newLabel: String, nowMs: Long): Pair<CarSpace, VinOutcome> =
        when (val d = CarMatch.decide(space.cars, space.garage.currentId, vinShort)) {
            VinDecision.Stay -> space to VinOutcome.Unchanged
            is VinDecision.SwitchTo -> {
                val car = space.car(d.id)!!
                if (recording) {
                    val last = space.profilesOf(d.id).maxByOrNull { it.lastUsedMs }
                    space to (last?.let { VinOutcome.Suggested(car, it) } ?: VinOutcome.Unchanged)
                } else {
                    val next = select(space, d.id, nowMs)
                    next to VinOutcome.Switched(car, next.profiles.active)
                }
            }
            is VinDecision.AttachToSelected -> {
                val car = space.car(d.id)!!.copy(vinShort = vinShort)
                space.withCar(car) to VinOutcome.Attached(car)
            }
            VinDecision.NewCar -> if (space.cars.size >= MAX_CARS) {
                space to VinOutcome.Unchanged
            } else {
                val (withCar, made) = create(space, newLabel, nowMs = nowMs, id = vinShort)
                val car = made.copy(vinShort = vinShort)
                val added = withCar.withCar(car)
                if (recording || added.garage.currentId == car.id) added to VinOutcome.Created(car, added.garage.currentId == car.id)
                else select(added, car.id, nowMs) to VinOutcome.Created(car, true)
            }
        }

    /**
     * Gives every profile without a known car one, then keeps the active profile among the selected
     * car's ([align]). Idempotent: the migration of the profiles written before cars existed, and
     * a safety net afterwards.
     *
     * A profile linked to a car of the Faults tab ([ProfileVehicle.vehicleId]) goes to that car; the
     * others are grouped by [ProfileVehicle.label] ("E90 330i") and each group gets a new car without
     * VIN of that name ([defaultLabel] for an empty one), its series and model, and as ECU list the
     * union of its profiles' ECUs (a linked car gets those it lacks). When profiles were given a car,
     * the active profile's car becomes the selected one. Returns [space] itself when nothing changes.
     */
    fun repair(space: CarSpace, defaultLabel: String, nowMs: Long): CarSpace {
        var garage = space.garage
        var set = space.profiles
        val known = garage.vehicles.mapTo(HashSet()) { it.id }
        val orphans = set.profiles.filter { it.carId !in known }
        if (orphans.isNotEmpty()) {
            val assigned = HashMap<String, String>()
            val byLabel = LinkedHashMap<String, Vehicle>()
            for (p in orphans) {
                val linked = p.vehicle.vehicleId?.let { vid -> CarMatch.findByVin(garage.vehicles, vid) }
                assigned[p.id] = linked?.id ?: byLabel.getOrPut(p.vehicle.label) {
                    Vehicle(newCarId(), cleanLabel(p.vehicle.label).ifEmpty { defaultLabel }, series = p.vehicle.series, model = p.vehicle.model)
                }.id
            }
            set = set.copy(profiles = set.profiles.map { p -> assigned[p.id]?.let { p.copy(carId = it, vehicle = p.vehicle.copy(vehicleId = null)) } ?: p })
            var next = CarSpace(garage.copy(vehicles = garage.vehicles + byLabel.values, currentId = set.active?.carId ?: garage.currentId), set)
            for (carId in assigned.values.distinct()) next = bringEcus(next, carId, orphans.filter { assigned[it.id] == carId }.flatMap { it.ecus })
            garage = next.garage
        }
        if (garage.current == null && garage.vehicles.isNotEmpty()) garage = garage.copy(currentId = garage.vehicles.first().id)
        set = align(set, garage.currentId, nowMs)
        return if (garage == space.garage && set == space.profiles) space else CarSpace(garage, set)
    }
}
