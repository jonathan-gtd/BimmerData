/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

/** "Reset" of the layout: back to the empty first-launch state, one side or both, name kept. */
object DashboardReset {
    /** [t] gets no page; the other target and the dashboard name are unchanged. */
    fun target(d: Dashboard, t: Target): Dashboard = d.withPages(t, emptyList())

    /** Both targets empty, the dashboard name kept. */
    fun all(d: Dashboard): Dashboard = d.copy(phonePages = emptyList(), carPages = emptyList())
}
