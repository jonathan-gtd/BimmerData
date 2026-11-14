/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

/**
 * Android Auto shows the same cards as the phone ([DashboardRules], enforced on every save):
 * [carFromPhone] lays the Android Auto pages out again from the phone pages.
 *
 * Each phone page becomes one Android Auto page per [GridLimits.Car] `maxCards` (8) cards, in the
 * phone's reading order (row, then column), one card per cell, using both rows of the 4 x 2 grid
 * from 4 cards on ([gridFor]): "Engine" with 10 cards gives "Engine 1/2" (8 cards) and
 * "Engine 2/2" (2 cards). Cards keep everything but their id and place, and are linked to their
 * phone card ([Card.phoneCardId]).
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
                val (columns, rows) = gridFor(chunk.size)
                Page(
                    id = fresh(),
                    name = if (chunks.size == 1) page.name else "${page.name} ${i + 1}/${chunks.size}",
                    columns = columns,
                    rows = rows,
                    cards = chunk.mapIndexed { c, card -> card.copy(id = fresh(), phoneCardId = card.id, col = c % columns, row = c / columns, colSpan = 1, rowSpan = 1) },
                )
            }
        }
        return d.copy(carPages = pages)
    }

    /** columns x rows for [n] cards: 1-3 on one row, 4 as 2 x 2, 5-6 as 3 x 2, 7-8 as 4 x 2. */
    fun gridFor(n: Int): Pair<Int, Int> = when {
        n <= 3 -> n.coerceAtLeast(1) to 1
        n == 4 -> 2 to 2
        n <= 6 -> 3 to 2
        else -> 4 to 2
    }

    /** True when Android Auto has exactly the phone's cards (type and values), whatever the order. */
    fun matches(d: Dashboard): Boolean = cards(d.phonePages) == cards(d.carPages)

    private fun cards(pages: List<Page>): Map<Pair<io.github.jonathangtd.bimmerdata.gauges.GaugeType, List<String>>, Int> =
        pages.flatMap { it.cards }.map { it.type to it.measurementIds }.groupingBy { it }.eachCount()
}
