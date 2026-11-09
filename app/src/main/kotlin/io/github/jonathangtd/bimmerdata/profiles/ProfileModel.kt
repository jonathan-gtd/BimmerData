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
 * The car a profile is for. [series] and [model] are free text ("E90", "330i"); [vehicleId] links
 * the profile to a car of the Faults tab ([io.github.jonathangtd.bimmerdata.vehicle.VehicleStore]
 * id: the short VIN when it is known), so the app can pick the profile when it reads that VIN.
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
 * A user profile: a car, its main ECUs and its own dashboard (phone and Android Auto pages).
 * Examples: "E90 330i · Lambda" (msv70), "E90 330i · Knock" (msv70), "120d E87" (dde...).
 */
@Serializable
data class Profile(
    val id: String = newId(),
    val name: String,
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
    fun normalizeEcus(list: List<String>): List<String> = list
        .map { it.trim().lowercase(Locale.ROOT).removeSuffix(".prg").removeSuffix(".grp") }
        .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
        .distinct()
        .take(MAX_ECUS)

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

    /** Adds a profile and makes it active. */
    fun create(
        set: ProfileSet, name: String, vehicle: ProfileVehicle = ProfileVehicle(), ecus: List<String> = emptyList(),
        nowMs: Long, dashboard: Dashboard = Dashboard(), graphs: List<Graph> = emptyList(),
    ): Pair<ProfileSet, Profile> {
        require(set.profiles.size < MAX_PROFILES) { "At most $MAX_PROFILES profiles" }
        val p = Profile(
            name = uniqueName(set, name), vehicle = cleanVehicle(vehicle), ecus = normalizeEcus(ecus),
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

    /** Removes [id]; if it was active, the most recently used one becomes active (or none). */
    fun delete(set: ProfileSet, id: String): ProfileSet {
        if (set.profile(id) == null) return set
        val left = set.profiles.filterNot { it.id == id }
        val active = if (set.activeId == id) left.maxByOrNull { it.lastUsedMs }?.id else set.activeId
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
     * Writes [dashboard] into the active profile. Without a profile, one is created for a layout
     * that has pages or a name (REST API writes), named after it or [defaultName]; an empty layout
     * creates nothing.
     */
    fun writeActiveDashboard(set: ProfileSet, dashboard: Dashboard, defaultName: String, nowMs: Long): ProfileSet {
        set.activeId?.let { id -> if (set.profile(id) != null) return writeDashboard(set, id, dashboard) }
        val hasContent = dashboard.phonePages.isNotEmpty() || dashboard.carPages.isNotEmpty() || !dashboard.name.isNullOrBlank()
        if (!hasContent) return set
        return create(set, dashboard.name?.takeIf { it.isNotBlank() } ?: defaultName, nowMs = nowMs, dashboard = dashboard).first
    }

    /**
     * Changes the graphs of profile [profileId] only (its pages are left as they are, so the
     * dashboard editor and the Graph tab never overwrite each other).
     */
    fun updateGraphs(set: ProfileSet, profileId: String, transform: (List<Graph>) -> List<Graph>): ProfileSet {
        if (set.profile(profileId) == null) return set
        return set.copy(profiles = set.profiles.map { if (it.id == profileId) it.copy(graphs = transform(it.graphs)) else it })
    }

    /** Profiles linked to the car [vehicleId], most recently used first. */
    fun forVehicle(set: ProfileSet, vehicleId: String): List<Profile> =
        set.profiles.filter { it.vehicle.vehicleId.equals(vehicleId, ignoreCase = true) }.sortedByDescending { it.lastUsedMs }

    /**
     * Which profile to use for the car [vehicleId] that was just identified: null when no profile
     * is linked to it, or when the active one already is (nothing to do); else the one used last.
     */
    fun autoSelect(set: ProfileSet, vehicleId: String): Profile? {
        val candidates = forVehicle(set, vehicleId)
        if (candidates.isEmpty() || candidates.any { it.id == set.activeId }) return null
        return candidates.first()
    }

    /** Profiles to offer in Android Auto: those of the active profile's car, else all of them. */
    fun sameCar(set: ProfileSet): List<Profile> {
        val car = set.active?.vehicle?.vehicleId ?: return set.profiles
        val same = forVehicle(set, car).sortedBy { set.profiles.indexOf(it) }
        return same.ifEmpty { set.profiles }
    }
}
