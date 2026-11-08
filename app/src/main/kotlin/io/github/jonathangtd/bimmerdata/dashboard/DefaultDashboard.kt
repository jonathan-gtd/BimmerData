/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeType

/**
 * First-launch layout: one empty page on the phone and one in Android Auto, nothing bound to a
 * particular ECU (the app works with any SGBD of any car; the user adds cards from the picker).
 */
object DefaultDashboard {
    /** Grid of a new phone page (portrait) and of a new Android Auto page (landscape, 4 big cards). */
    fun phonePage(name: String) = Page(name = name, columns = 2, rows = 6)

    fun carPage(name: String) = Page(name = name, columns = 4, rows = 1)

    /** [pageName] names both first pages ("Page 1"). */
    fun create(pageName: String = "Page 1"): Dashboard =
        Dashboard(phonePages = listOf(phonePage(pageName)), carPages = listOf(carPage(pageName)))
}
