/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.profiles

import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.newId
import io.github.jonathangtd.bimmerdata.graph.Graph
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * What a profile file says about the car it was made for: [series] and [model] are free text ("E90",
 * "330i"), shared with the profile. [vehicleId] is the link to a car of the Faults tab written before
 * profiles belonged to a car ([Profile.carId]): only read by the migration ([io.github.jonathangtd.bimmerdata.cars.Cars.repair]),
 * which then clears it.
 */
@Serializable
data class ProfileVehicle(
    val series: String? = null,
    val model: String? = null,
    val vehicleId: String? = null,
) {
    /** "E90 330i"; empty when neither is set. */
    val label: String get() = listOfNotNull(series.clean(), model.clean()).joinToString(" ")

    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * A user profile of one car: its main ECUs and its own dashboard (phone and Android Auto pages).
 * Examples: "E90 330i · Lambda" (msv70), "E90 330i · Knock" (msv70), "120d E87" (dde...).
 */
@Serializable
data class Profile(
    val id: String = newId(),
    val name: String,
    /**
     * The car it belongs to ([io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle.id]). Empty only
     * in files written before cars existed and in a profile created with no car at all: the store
     * gives it one on the next read ([io.github.jonathangtd.bimmerdata.cars.Cars.repair]).
     */
    val carId: String = "",
    val vehicle: ProfileVehicle = ProfileVehicle(),
    /** Main ECUs (SGBD names, lower case, no extension): the card picker opens on them. */
    val ecus: List<String> = emptyList(),
    /** Stored without [Dashboard.name]: the header shows the profile [name]. */
    val dashboard: Dashboard = Dashboard(),
    /** Last time it was the active profile (epoch ms), to pick between profiles of one car. */
    val lastUsedMs: Long = 0,
    /** Graphs of the Graph tab: values of this profile's cards on a time axis. */
    val graphs: List<Graph> = emptyList(),
) {
    /** The layout as the dashboard screens see it: named after the profile. */
    fun shownDashboard(): Dashboard = dashboard.copy(name = name)
}

/** Every profile of the app and which one is active (null: none yet, first launch). */
@Serializable
data class ProfileSet(
    val formatVersion: Int = FORMAT_VERSION,
    val profiles: List<Profile> = emptyList(),
    val activeId: String? = null,
) {
    val active: Profile? get() = activeId?.let { id -> profiles.firstOrNull { it.id == id } }

    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }

    /** The active layout (empty when there is no profile). */
    fun activeDashboard(): Dashboard = active?.shownDashboard() ?: Dashboard()

    /** The profiles of car [carId] only, with the active one if it is among them (what the profile screens list). */
    fun forCar(carId: String?): ProfileSet {
        val list = profiles.filter { it.carId == carId }
        return copy(profiles = list, activeId = activeId?.takeIf { id -> list.any { it.id == id } })
    }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** Pure operations on a [ProfileSet] (the store applies them under its lock). */
object Profiles {
    const val MAX_NAME_LENGTH = Dashboard.MAX_NAME_LENGTH
    const val MAX_FIELD_LENGTH = 24
    const val MAX_ECUS = 8
    const val MAX_PROFILES = 50

    /** Lower case, without `.prg` / `.grp`, no blanks or duplicates, at most [MAX_ECUS]. */
    fun normalizeEcus(list: List<String>): List<String> = normalizeSgbds(list, MAX_ECUS)

    /** SGBD names as [normalizeEcus] cleans them, at most [max] (also the ECU list of a car). */
    fun normalizeSgbds(list: List<String>, max: Int): List<String> = list
        .map { it.trim().lowercase(Locale.ROOT).removeSuffix(".prg").removeSuffix(".grp") }
        .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
        .distinct()
        .take(max)

    fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)

    fun cleanVehicle(v: ProfileVehicle): ProfileVehicle = ProfileVehicle(
        series = v.series?.trim()?.take(MAX_FIELD_LENGTH)?.takeIf { it.isNotEmpty() },
        model = v.model?.trim()?.take(MAX_FIELD_LENGTH)?.takeIf { it.isNotEmpty() },
        vehicleId = v.vehicleId?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** [base], or "[base] (2)", "(3)"... so that no two profiles share a name. */
    fun uniqueName(set: ProfileSet, base: String, exceptId: String? = null): String {
        val clean = cleanName(base).ifEmpty { "1" }
        val taken = set.profiles.filter { it.id != exceptId }.map { it.name.lowercase(Locale.ROOT) }.toSet()
        if (clean.lowercase(Locale.ROOT) !in taken) return clean
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val candidate = clean.take(MAX_NAME_LENGTH - suffix.length) + suffix
            if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
            n++
        }
    }

    /** Adds a profile to car [carId] and makes it active. */
    fun create(
        set: ProfileSet, name: String, vehicle: ProfileVehicle = ProfileVehicle(), ecus: List<String> = emptyList(),
        nowMs: Long, dashboard: Dashboard = Dashboard(), graphs: List<Graph> = emptyList(), carId: String,
    ): Pair<ProfileSet, Profile> {
        require(set.profiles.size < MAX_PROFILES) { "At most $MAX_PROFILES profiles" }
        val p = Profile(
            name = uniqueName(set, name), carId = carId, vehicle = cleanVehicle(vehicle), ecus = normalizeEcus(ecus),
            dashboard = dashboard.copy(name = null), lastUsedMs = nowMs, graphs = graphs,
        )
        return set.copy(profiles = set.profiles + p, activeId = p.id) to p
    }

    /** Makes [id] the active profile; unchanged if it does not exist. */
    fun activate(set: ProfileSet, id: String, nowMs: Long): ProfileSet {
        if (set.profile(id) == null) return set
        return set.copy(activeId = id, profiles = set.profiles.map { if (it.id == id) it.copy(lastUsedMs = nowMs) else it })
    }

    /** Copy of [id] (same car, ECUs and pages) named [name] or "<name> (2)"; the copy becomes active. */
    fun duplicate(set: ProfileSet, id: String, nowMs: Long, name: String? = null): Pair<ProfileSet, Profile>? {
        val src = set.profile(id) ?: return null
        if (set.profiles.size >= MAX_PROFILES) return null
        val copy = src.copy(id = newId(), name = uniqueName(set, name ?: src.name), lastUsedMs = nowMs)
        val i = set.profiles.indexOf(src)
        val list = set.profiles.toMutableList().apply { add(i + 1, copy) }
        return set.copy(profiles = list, activeId = copy.id) to copy
    }

    /** Changes name, car and / or ECUs of [id] (null = unchanged). */
    fun edit(set: ProfileSet, id: String, name: String? = null, vehicle: ProfileVehicle? = null, ecus: List<String>? = null): ProfileSet =
        set.copy(profiles = set.profiles.map { p ->
            if (p.id != id) p else p.copy(
                name = name?.let { cleanName(it) }?.takeIf { it.isNotEmpty() }?.let { uniqueName(set, it, exceptId = id) } ?: p.name,
                vehicle = vehicle?.let(::cleanVehicle) ?: p.vehicle,
                ecus = ecus?.let(::normalizeEcus) ?: p.ecus,
            )
        })

    /** Removes [id]; if it was active, the most recently used one of the same car becomes active (or none). */
    fun delete(set: ProfileSet, id: String): ProfileSet {
        val gone = set.profile(id) ?: return set
        val left = set.profiles.filterNot { it.id == id }
        val active = if (set.activeId == id) left.filter { it.carId == gone.carId }.maxByOrNull { it.lastUsedMs }?.id else set.activeId
        return set.copy(profiles = left, activeId = active)
    }

    /**
     * Writes [dashboard] into profile [profileId] (the profile the editor showed, even if another
     * one became active meanwhile). A non-blank [Dashboard.name] renames the profile.
     */
    fun writeDashboard(set: ProfileSet, profileId: String, dashboard: Dashboard): ProfileSet {
        if (set.profile(profileId) == null) return set
        return set.copy(profiles = set.profiles.map { p ->
            if (p.id != profileId) p else {
                val newName = dashboard.name?.let { cleanName(it) }?.takeIf { it.isNotEmpty() }?.let { uniqueName(set, it, exceptId = p.id) }
                p.copy(name = newName ?: p.name, dashboard = dashboard.copy(name = null))
            }
        })
    }

    /**
     * Writes [dashboard] into the active profile. Without a profile, one is created on car [carId]
     * (the selected car; "" when there is none, see [Profile.carId]) for a layout that has pages or a
     * name (REST API writes), named after it or [defaultName]; an empty layout creates nothing.
     */
    fun writeActiveDashboard(set: ProfileSet, dashboard: Dashboard, defaultName: String, nowMs: Long, carId: String): ProfileSet {
        set.activeId?.let { id -> if (set.profile(id) != null) return writeDashboard(set, id, dashboard) }
        val hasContent = dashboard.phonePages.isNotEmpty() || dashboard.carPages.isNotEmpty() || !dashboard.name.isNullOrBlank()
        if (!hasContent) return set
        return create(set, dashboard.name?.takeIf { it.isNotBlank() } ?: defaultName, nowMs = nowMs, dashboard = dashboard, carId = carId).first
    }

    /**
     * Changes the graphs of profile [profileId] only (its pages are left as they are, so the
     * dashboard editor and the Graph tab never overwrite each other).
     */
    fun updateGraphs(set: ProfileSet, profileId: String, transform: (List<Graph>) -> List<Graph>): ProfileSet {
        if (set.profile(profileId) == null) return set
        return set.copy(profiles = set.profiles.map { if (it.id == profileId) it.copy(graphs = transform(it.graphs)) else it })
    }

    /** Profiles to offer in Android Auto: those of the active profile's car, in list order (none without an active profile). */
    fun sameCar(set: ProfileSet): List<Profile> {
        val car = set.active?.carId ?: return emptyList()
        return set.profiles.filter { it.carId == car }
    }
}
