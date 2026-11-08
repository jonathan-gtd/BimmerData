/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeType

/**
 * First-launch layout: completely empty, no page on the phone nor in Android Auto (the app works
 * with any SGBD of any car; the first "Add a card" creates the first page with the default grid).
 */
object DefaultDashboard {
    /** Grid of a new phone page (portrait) and of a new Android Auto page (landscape, 4 big cards). */
    fun phonePage(name: String) = Page(name = name, columns = 2, rows = 6)

    fun carPage(name: String) = Page(name = name, columns = 4, rows = 1)

    fun create(): Dashboard = Dashboard()

    /** A new page of [target] with its default grid. */
    fun page(target: Target, name: String): Page = if (target == Target.Phone) phonePage(name) else carPage(name)
}
