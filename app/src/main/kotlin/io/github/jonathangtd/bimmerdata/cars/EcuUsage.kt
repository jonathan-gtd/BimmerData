/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.cars

import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.profiles.Profile
import java.util.Locale

/** How one profile uses one ECU (SGBD): in its main ECUs, on [cards] (phone and Android Auto), in [graphSeries] graph values. */
data class EcuUse(val profile: Profile, val inEcus: Boolean, val cards: Int, val graphSeries: Int)

/**
 * Which profiles use an ECU, and taking it out of them. An ECU leaving a car's list (a detection
 * did not find it, or the user removed it) is never taken out of the profiles silently: the app asks
 * with these counts, and only on "Remove" does [remove] run.
 */
object EcuUsage {
    /** [measurementId] ("<sgbd>/<id>") is a value of [sgbd]; phone sensors ("gps/…") belong to no ECU of the car. */
    fun belongsTo(measurementId: String, sgbd: String): Boolean =
        measurementId.contains('/') && measurementId.substringBefore('/').equals(sgbd, ignoreCase = true)

    private fun Card.uses(sgbd: String) = measurementIds.any { belongsTo(it, sgbd) }

    /** Cards of [p] (phone and Android Auto pages) showing a value of [sgbd]. */
    fun cardCount(p: Profile, sgbd: String): Int =
        (p.dashboard.phonePages + p.dashboard.carPages).sumOf { page -> page.cards.count { it.uses(sgbd) } }

    /** How [p] uses [sgbd]; null when it does not. */
    fun use(p: Profile, sgbd: String): EcuUse? {
        val inEcus = p.ecus.any { it.equals(sgbd, ignoreCase = true) }
        val cards = cardCount(p, sgbd)
        val series = p.graphs.sumOf { g -> g.series.count { belongsTo(it.measurementId, sgbd) } }
        return if (inEcus || cards > 0 || series > 0) EcuUse(p, inEcus, cards, series) else null
    }

    /** The profiles of [profiles] using [sgbd], in list order. */
    fun uses(profiles: List<Profile>, sgbd: String): List<EcuUse> = profiles.mapNotNull { use(it, sgbd) }

    /** Every SGBD [profiles] use (main ECUs, card and graph values), lower case. */
    fun usedSgbds(profiles: List<Profile>): Set<String> = profiles.flatMapTo(HashSet()) { p ->
        p.ecus + (p.dashboard.measurementIds() + p.graphs.flatMap { it.measurementIds() })
            .filter { it.contains('/') }.map { it.substringBefore('/') }
    }.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    /**
     * [p] without [sgbd]: out of its main ECUs, its cards showing one of its values deleted from the
     * phone and the Android Auto pages alike (so both keep showing the same cards), its values out
     * of the graphs.
     */
    fun remove(p: Profile, sgbd: String): Profile {
        fun Page.without() = copy(cards = cards.filterNot { it.uses(sgbd) })
        return p.copy(
            ecus = p.ecus.filterNot { it.equals(sgbd, ignoreCase = true) },
            dashboard = p.dashboard.copy(phonePages = p.dashboard.phonePages.map { it.without() }, carPages = p.dashboard.carPages.map { it.without() }),
            graphs = p.graphs.map { g -> g.copy(series = g.series.filterNot { belongsTo(it.measurementId, sgbd) }) },
        )
    }
}
