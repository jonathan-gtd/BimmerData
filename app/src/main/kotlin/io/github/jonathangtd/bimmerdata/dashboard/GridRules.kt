/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeType

/**
 * Pure placement rules of the card grid. Operations return the new page, or null when the change
 * is not possible (out of the grid or overlapping another card), so the UI can just snap back.
 */
object GridRules {
    fun overlaps(a: Card, b: Card): Boolean =
        a.col < b.colEnd && b.col < a.colEnd && a.row < b.rowEnd && b.row < a.rowEnd

    fun inBounds(page: Page, c: Card): Boolean =
        c.col >= 0 && c.row >= 0 && c.colSpan >= 1 && c.rowSpan >= 1 && c.colEnd <= page.columns && c.rowEnd <= page.rows

    /** True if [card] fits on [page] ignoring the card with the same id. */
    fun canPlace(page: Page, card: Card): Boolean =
        inBounds(page, card) && page.cards.none { it.id != card.id && overlaps(it, card) }

    fun isValid(page: Page): Boolean =
        page.cards.all { inBounds(page, it) } &&
            page.cards.indices.none { i -> (i + 1 until page.cards.size).any { j -> overlaps(page.cards[i], page.cards[j]) } }

    /**
     * Moves card [id] so its top-left cell is ([col], [row]) (clamped into the grid). Dropping it on
     * a single other card swaps the two when both then fit.
     */
    fun move(page: Page, id: String, col: Int, row: Int): Page? {
        val card = page.card(id) ?: return null
        val moved = card.copy(
            col = col.coerceIn(0, (page.columns - card.colSpan).coerceAtLeast(0)),
            row = row.coerceIn(0, (page.rows - card.rowSpan).coerceAtLeast(0)),
        )
        if (moved.col == card.col && moved.row == card.row) return page
        if (!inBounds(page, moved)) return null
        val hits = page.cards.filter { it.id != id && overlaps(it, moved) }
        if (hits.isEmpty()) return page.replace(moved)
        if (hits.size == 1) {
            val other = hits[0]
            val swapped = other.copy(col = card.col, row = card.row)
            val result = page.copy(cards = page.cards.map {
                when (it.id) {
                    id -> moved
                    other.id -> swapped
                    else -> it
                }
            })
            if (isValid(result)) return result
        }
        return null
    }

    /** True if dropping [id] at ([col], [row]) would be accepted by [move]. */
    fun canMove(page: Page, id: String, col: Int, row: Int): Boolean = move(page, id, col, row) != null

    /** Changes the span of card [id] (at least 1×1, clamped to the grid edge); null if it would overlap. */
    fun resize(page: Page, id: String, colSpan: Int, rowSpan: Int): Page? {
        val card = page.card(id) ?: return null
        val resized = card.copy(
            colSpan = colSpan.coerceIn(1, (page.columns - card.col).coerceAtLeast(1)),
            rowSpan = rowSpan.coerceIn(1, (page.rows - card.row).coerceAtLeast(1)),
        )
        if (resized == card) return page
        return if (canPlace(page, resized)) page.replace(resized) else null
    }

    /** First free top-left cell (row by row) where a [colSpan] × [rowSpan] card fits. */
    fun firstFreeSlot(page: Page, colSpan: Int, rowSpan: Int): Pair<Int, Int>? {
        for (row in 0..page.rows - rowSpan) {
            for (col in 0..page.columns - colSpan) {
                val probe = Card(id = "\u0000probe", type = GaugeType.Status, measurementIds = emptyList(), col = col, row = row, colSpan = colSpan, rowSpan = rowSpan)
                if (canPlace(page, probe)) return col to row
            }
        }
        return null
    }

    /** True if no card covers cell ([col], [row]). */
    fun isFree(page: Page, col: Int, row: Int): Boolean =
        page.cards.none { col >= it.col && col < it.colEnd && row >= it.row && row < it.rowEnd }

    /**
     * Adds [card] at its own position if free, else at the first free slot of its size, else as a
     * 1×1 card. Null when the page is full (or a car page already has its maximum of cards).
     */
    fun add(page: Page, card: Card, limits: GridLimits): Page? {
        if (page.cards.size >= limits.maxCards) return null
        if (canPlace(page, card)) return page.copy(cards = page.cards + card)
        // Keep the requested cell if a smaller version of the card fits there.
        if (card.col in 0 until page.columns && card.row in 0 until page.rows && isFree(page, card.col, card.row)) {
            for (cs in card.colSpan downTo 1) for (rs in card.rowSpan downTo 1) {
                val shrunk = card.copy(colSpan = cs, rowSpan = rs)
                if (canPlace(page, shrunk)) return page.copy(cards = page.cards + shrunk)
            }
        }
        firstFreeSlot(page, card.colSpan, card.rowSpan)?.let { (c, r) ->
            return page.copy(cards = page.cards + card.copy(col = c, row = r))
        }
        firstFreeSlot(page, 1, 1)?.let { (c, r) ->
            return page.copy(cards = page.cards + card.copy(col = c, row = r, colSpan = 1, rowSpan = 1))
        }
        return null
    }

    /**
     * Changes the grid size. Cards keep their place when they still fit; the others are shrunk to
     * the new edge or moved to a free slot. Null if some card cannot be placed any more.
     */
    fun setGrid(page: Page, columns: Int, rows: Int, limits: GridLimits): Page? {
        val cols = columns.coerceIn(1, limits.maxColumns)
        val rws = rows.coerceIn(1, limits.maxRows)
        var result = page.copy(columns = cols, rows = rws, cards = emptyList())
        for (card in page.cards.sortedWith(compareBy({ it.row }, { it.col }))) {
            val clampedSpan = card.copy(colSpan = card.colSpan.coerceAtMost(cols), rowSpan = card.rowSpan.coerceAtMost(rws))
            val clamped = clampedSpan.copy(
                col = clampedSpan.col.coerceAtMost(cols - clampedSpan.colSpan),
                row = clampedSpan.row.coerceAtMost(rws - clampedSpan.rowSpan),
            )
            result = when {
                canPlace(result, clamped) -> result.copy(cards = result.cards + clamped)
                else -> {
                    val slot = firstFreeSlot(result, clamped.colSpan, clamped.rowSpan)
                        ?.let { (c, r) -> clamped.copy(col = c, row = r) }
                        ?: firstFreeSlot(result, 1, 1)?.let { (c, r) -> clamped.copy(col = c, row = r, colSpan = 1, rowSpan = 1) }
                        ?: return null
                    result.copy(cards = result.cards + slot)
                }
            }
        }
        // Keep the original card order (it is the reading order).
        val order = page.cards.map { it.id }
        return result.copy(cards = result.cards.sortedBy { order.indexOf(it.id) })
    }
}
