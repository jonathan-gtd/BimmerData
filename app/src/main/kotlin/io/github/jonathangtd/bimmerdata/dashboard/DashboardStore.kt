/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import android.content.Context
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.coroutines.flow.StateFlow

/**
 * The layout of the **active profile** ([ProfileStore]): what the dashboard screens, Android Auto
 * and the REST API layout routes read and write. The [Dashboard.name] they see is the profile's
 * name; writing a layout with a new name renames the profile.
 *
 * With no profile (first launch), [load] gives an empty layout; a write of a layout that has pages
 * or a name (REST API) creates a profile for it, an empty one changes nothing.
 *
 * Every access goes through the profile store's process-wide lock; writers that change the layout
 * based on its current content use [update] (read, change and write under the lock).
 */
class DashboardStore(private val profiles: ProfileStore) {
    constructor(context: Context) : this(ProfileStore.get(context))

    /** Layout of the active profile; empty when there is no profile. */
    fun load(): Dashboard = profiles.load().activeDashboard()

    /** Writes [dashboard] into the active profile; returns false (and logs) if it could not be written. */
    fun save(dashboard: Dashboard): Boolean = update { dashboard } != null

    /**
     * Reads the active layout, applies [transform] and writes the result, with no other write in
     * between (e.g. the API adding a card while the editor saves). Returns the written layout, or
     * null if it could not be written.
     */
    fun update(transform: (Dashboard) -> Dashboard): Dashboard? = profiles.update { set ->
        Profiles.writeActiveDashboard(set, transform(set.activeDashboard()), profiles.defaultProfileName(), profiles.now())
    }?.activeDashboard()

    companion object {
        /** File of the layout before profiles existed (migrated by [ProfileStore]). */
        const val FILE_NAME = ProfileStore.LEGACY_FILE_NAME

        /**
         * Layout of the active profile after the last read or write in this process, by any writer
         * (phone editor, REST API, profile switch...). Android Auto follows it.
         */
        val saved: StateFlow<Dashboard?> get() = ProfileStore.activeDashboard

        @Suppress("UNUSED_PARAMETER")
        fun defaultDashboard(context: Context): Dashboard = DefaultDashboard.create()
    }
}
