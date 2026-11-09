/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

/**
 * Android Auto shows the same cards as the phone: [carFromPhone] rebuilds the Android Auto pages
 * from the phone pages (the owner's rule for his profiles, offered to everyone as an action).
 *
 * Each phone page becomes one Android Auto page per [GridLimits.Car] `maxCards` cards, in the
 * phone's reading order (row, then column), one card per cell on a single row: "Engine" with 8
 * cards gives "Engine 1/2" and "Engine 2/2". Cards keep everything but their id and place.
 */
object DashboardMirror {
    fun carFromPhone(d: Dashboard): Dashboard {
        val used = HashSet<String>()
        d.phonePages.forEach { p -> used += p.id; p.cards.forEach { used += it.id } }
        fun fresh(): String {
            var id = newId()
            while (!used.add(id)) id = newId()
            return id
        }
        val pages = d.phonePages.flatMap { page ->
            val chunks = page.cards.sortedWith(compareBy({ it.row }, { it.col })).chunked(GridLimits.Car.maxCards)
            chunks.mapIndexed { i, chunk ->
                Page(
                    id = fresh(),
                    name = if (chunks.size == 1) page.name else "${page.name} ${i + 1}/${chunks.size}",
                    columns = chunk.size,
                    rows = 1,
                    cards = chunk.mapIndexed { c, card -> card.copy(id = fresh(), col = c, row = 0, colSpan = 1, rowSpan = 1) },
                )
            }
        }
        return d.copy(carPages = pages)
    }

    /** True when Android Auto has exactly the phone's cards (type and values), whatever the order. */
    fun matches(d: Dashboard): Boolean = cards(d.phonePages) == cards(d.carPages)

    private fun cards(pages: List<Page>): Map<Pair<io.github.jonathangtd.bimmerdata.gauges.GaugeType, List<String>>, Int> =
        pages.flatMap { it.cards }.map { it.type to it.measurementIds }.groupingBy { it }.eachCount()
}
