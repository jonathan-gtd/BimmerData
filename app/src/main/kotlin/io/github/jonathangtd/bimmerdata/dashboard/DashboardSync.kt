/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

/**
 * How the phone dashboard follows layouts written by someone else ([DashboardStore.saved]: the
 * REST API, another screen), against its own copy. [lastPersisted] is the layout the phone last
 * loaded or wrote; a saved layout equal to it, or to what is shown, is the phone's own write.
 *
 * - Not editing: an external layout replaces the shown one at once (page indexes clamped, undo
 *   dropped).
 * - Editing: the user's draft stays on screen and the external layout waits in
 *   [DashboardUi.external]; the screen tells the user (snackbar with "Load it"):
 *   - "Load it" ([accept]): the external layout is shown, the draft goes to undo;
 *   - the user keeps editing: the next own write replaces the external layout (last writer wins,
 *     but not silently) and the pending one is dropped ([onOwnSave]);
 *   - the user leaves the editor without an own change since ([onEditingDone]): the external
 *     layout is adopted, so it is never overwritten by an untouched draft.
 */
object DashboardSync {
    fun onSaved(ui: DashboardUi, saved: Dashboard, lastPersisted: Dashboard?): DashboardUi = when {
        saved == lastPersisted || saved == ui.dashboard -> ui
        ui.dashboard == null -> ui // still loading: the load reads the file anyway
        ui.editing -> ui.copy(external = saved)
        else -> adopt(ui, saved)
    }

    /** The user chose the external layout while editing; the draft can be restored with undo. */
    fun accept(ui: DashboardUi): DashboardUi {
        val ext = ui.external ?: return ui
        return adopt(ui, ext).copy(undo = ui.dashboard)
    }

    fun onEditingDone(ui: DashboardUi, lastPersisted: Dashboard?): DashboardUi {
        val ext = ui.external ?: return ui
        return if (ui.dashboard == lastPersisted) adopt(ui, ext) else ui.copy(external = null)
    }

    fun onOwnSave(ui: DashboardUi): DashboardUi = if (ui.external != null) ui.copy(external = null) else ui

    private fun adopt(ui: DashboardUi, d: Dashboard): DashboardUi = ui.copy(
        dashboard = d,
        external = null,
        undo = null,
        phonePage = ui.phonePage.coerceIn(0, (d.phonePages.size - 1).coerceAtLeast(0)),
        carPage = ui.carPage.coerceIn(0, (d.carPages.size - 1).coerceAtLeast(0)),
    )
}
