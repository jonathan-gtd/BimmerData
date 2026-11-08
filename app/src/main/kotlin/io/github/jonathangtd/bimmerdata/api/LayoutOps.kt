/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.GridLimits
import io.github.jonathangtd.bimmerdata.dashboard.GridRules
import io.github.jonathangtd.bimmerdata.dashboard.LayoutPreset
import io.github.jonathangtd.bimmerdata.dashboard.LayoutPresets
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.dashboard.Target
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.encodeToJsonElement

/** One problem of a layout, with a JSON-path-like location such as `phonePages[0].cards[2]`. */
@Serializable
data class LayoutIssue(val path: String, val code: String, val message: String)

/**
 * Layout edits for the API on top of the dashboard's own rules ([GridRules], [GridLimits],
 * [Card.measurementRange]). Unlike the editor, which snaps back silently, every refusal is an
 * [ApiException] that says exactly why, so a script or an AI can fix its request.
 */
object LayoutOps {
    fun targetName(t: Target) = if (t == Target.Phone) "phone" else "car"
    private fun pagesKey(t: Target) = if (t == Target.Phone) "phonePages" else "carPages"

    fun parseTarget(s: String): Target = when (s.lowercase()) {
        "phone" -> Target.Phone
        "car", "android_auto", "androidauto" -> Target.Car
        else -> throw badRequest("Unknown target '$s': use 'phone' or 'car' (Android Auto)")
    }

    fun describe(c: Card) = "card '${c.id}' (col ${c.col}, row ${c.row}, ${c.colSpan}×${c.rowSpan})"

    // ---------------------------------------------------------------- validation

    /** Every rule a stored layout must satisfy; empty list = valid. */
    fun validate(d: Dashboard): List<LayoutIssue> {
        val issues = ArrayList<LayoutIssue>()
        if (d.formatVersion > Dashboard.FORMAT_VERSION) {
            issues += LayoutIssue("formatVersion", "unsupported_format", "formatVersion ${d.formatVersion} is newer than this app (max ${Dashboard.FORMAT_VERSION})")
        }
        val ids = HashMap<String, String>()
        for (t in Target.entries) {
            val key = pagesKey(t)
            val limits = GridLimits.of(t)
            val pages = d.pages(t)
            pages.forEachIndexed { pi, page ->
                val pp = "$key[$pi]"
                if (page.id.isBlank()) issues += LayoutIssue("$pp.id", "blank_id", "Page id must not be blank")
                else ids.put(page.id, pp)?.let { issues += LayoutIssue("$pp.id", "duplicate_id", "Id '${page.id}' is also used by $it") }
                if (page.name.isBlank()) issues += LayoutIssue("$pp.name", "blank_name", "Page name must not be blank")
                if (page.columns !in 1..limits.maxColumns) issues += LayoutIssue("$pp.columns", "invalid_grid", "columns must be 1..${limits.maxColumns} on ${targetName(t)} pages (got ${page.columns})")
                if (page.rows !in 1..limits.maxRows) issues += LayoutIssue("$pp.rows", "invalid_grid", "rows must be 1..${limits.maxRows} on ${targetName(t)} pages (got ${page.rows})")
                if (page.cards.size > limits.maxCards) issues += LayoutIssue("$pp.cards", "too_many_cards", "At most ${limits.maxCards} cards on a ${targetName(t)} page (got ${page.cards.size})")
                page.cards.forEachIndexed { ci, card ->
                    val cp = "$pp.cards[$ci]"
                    if (card.id.isBlank()) issues += LayoutIssue("$cp.id", "blank_id", "Card id must not be blank")
                    else ids.put(card.id, cp)?.let { issues += LayoutIssue("$cp.id", "duplicate_id", "Id '${card.id}' is also used by $it") }
                    issues += cardIssues(card, cp)
                    if (page.columns in 1..limits.maxColumns && page.rows in 1..limits.maxRows && !GridRules.inBounds(page, card)) {
                        issues += LayoutIssue(cp, "out_of_grid", "${describe(card)} does not fit the ${page.columns}×${page.rows} grid (needs col+colSpan ≤ ${page.columns} and row+rowSpan ≤ ${page.rows})")
                    }
                    for (cj in 0 until ci) {
                        val other = page.cards[cj]
                        if (GridRules.overlaps(card, other)) {
                            issues += LayoutIssue(cp, "card_overlap", "${describe(card)} overlaps ${describe(other)} at $pp.cards[$cj]")
                        }
                    }
                }
            }
        }
        return issues
    }

    /** Rules of a single card that do not depend on its page. */
    fun cardIssues(card: Card, path: String = "card"): List<LayoutIssue> {
        val issues = ArrayList<LayoutIssue>()
        val range = Card.measurementRange(card.type)
        if (card.measurementIds.size !in range) {
            val want = if (range.first == range.last) "exactly ${range.first}" else "${range.first} to ${range.last}"
            issues += LayoutIssue("$path.measurementIds", "measurement_count", "A ${card.type} card takes $want measurement id(s), got ${card.measurementIds.size}")
        }
        card.measurementIds.forEachIndexed { i, id ->
            if (!id.contains('/') || id.startsWith('/') || id.endsWith('/')) {
                issues += LayoutIssue("$path.measurementIds[$i]", "invalid_measurement_id", "'$id' is not a measurement id ('<sgbd>/<key>', e.g. 'msv70/0x4402'); see GET /api/v1/ecus/{sgbd}/measurements")
            }
        }
        if (card.colSpan < 1 || card.rowSpan < 1) issues += LayoutIssue(path, "invalid_span", "colSpan and rowSpan must be ≥ 1")
        if (card.col < 0 || card.row < 0) issues += LayoutIssue(path, "out_of_grid", "col and row must be ≥ 0")
        card.windowSeconds?.let {
            if (!TrendWindow.isValid(it)) issues += LayoutIssue("$path.windowSeconds", "invalid_window", "windowSeconds must be ${TrendWindow.MIN_SECONDS}..${TrendWindow.MAX_SECONDS} (got $it)")
        }
        card.decimals?.let { if (it !in 0..4) issues += LayoutIssue("$path.decimals", "invalid_decimals", "decimals must be 0..4 (got $it)") }
        if (card.min != null && card.max != null && card.max <= card.min) issues += LayoutIssue("$path.max", "invalid_range", "max (${card.max}) must be greater than min (${card.min})")
        card.channelLabels?.let { if (it.size > card.measurementIds.size) issues += LayoutIssue("$path.channelLabels", "invalid_labels", "More channel labels (${it.size}) than measurements (${card.measurementIds.size})") }
        return issues
    }

    fun requireValid(d: Dashboard) {
        val issues = validate(d)
        if (issues.isNotEmpty()) throw invalid("The dashboard is not valid (${issues.size} problem(s))", issues)
    }

    private fun invalid(message: String, issues: List<LayoutIssue>) =
        ApiException(422, "invalid_layout", message, ApiJson.encodeToJsonElement(issues) as JsonArray)

    // ---------------------------------------------------------------- lookup

    /** Page by id, or by 0-based index when no page has that id. */
    fun findPage(d: Dashboard, t: Target, ref: String): Pair<Int, Page> {
        val pages = d.pages(t)
        pages.indexOfFirst { it.id == ref }.takeIf { it >= 0 }?.let { return it to pages[it] }
        ref.toIntOrNull()?.let { i -> if (i in pages.indices) return i to pages[i] }
        throw notFound("page_not_found", "No ${targetName(t)} page with id or index '$ref' (pages: ${pages.mapIndexed { i, p -> "$i='${p.id}' ${p.name}" }.joinToString()})")
    }

    fun findCard(page: Page, cardId: String): Card =
        page.card(cardId) ?: throw notFound("card_not_found", "No card '$cardId' on page '${page.id}' (cards: ${page.cards.joinToString { it.id }})")

    fun findPreset(t: Target, id: String): LayoutPreset =
        LayoutPresets.of(t).firstOrNull { it.id == id }
            ?: throw notFound("preset_not_found", "No ${targetName(t)} preset '$id' (available: ${LayoutPresets.of(t).joinToString { it.id }})")

    private fun usedIds(d: Dashboard): Set<String> =
        (d.phonePages + d.carPages).flatMapTo(HashSet()) { p -> p.cards.map { it.id } + p.id }

    // ---------------------------------------------------------------- pages

    fun addPage(d: Dashboard, t: Target, name: String, columns: Int?, rows: Int?, presetId: String?, position: Int?): Pair<Dashboard, Page> {
        if (name.isBlank()) throw badRequest("name must not be blank")
        val limits = GridLimits.of(t)
        val preset = presetId?.let { findPreset(t, it) }
        val cols = preset?.columns ?: columns ?: if (t == Target.Phone) 2 else 4
        val rws = preset?.rows ?: rows ?: if (t == Target.Phone) 6 else 1
        if (cols !in 1..limits.maxColumns || rws !in 1..limits.maxRows) {
            throw ApiException(422, "invalid_grid", "A ${targetName(t)} page has 1..${limits.maxColumns} columns and 1..${limits.maxRows} rows (got $cols×$rws)")
        }
        var page = Page(name = name.trim(), columns = cols, rows = rws)
        while (page.id in usedIds(d)) page = page.copy(id = io.github.jonathangtd.bimmerdata.dashboard.newId())
        val pages = d.pages(t).toMutableList()
        val at = (position ?: pages.size)
        if (at !in 0..pages.size) throw badRequest("position must be 0..${pages.size}")
        pages.add(at, page)
        return d.withPages(t, pages) to page
    }

    fun updatePage(d: Dashboard, t: Target, ref: String, name: String?, columns: Int?, rows: Int?, position: Int?): Pair<Dashboard, Page> {
        val (index, page) = findPage(d, t, ref)
        var p = page
        if (name != null) {
            if (name.isBlank()) throw badRequest("name must not be blank")
            p = p.copy(name = name.trim())
        }
        if (columns != null || rows != null) {
            val limits = GridLimits.of(t)
            val cols = columns ?: p.columns
            val rws = rows ?: p.rows
            if (cols !in 1..limits.maxColumns || rws !in 1..limits.maxRows) {
                throw ApiException(422, "invalid_grid", "A ${targetName(t)} page has 1..${limits.maxColumns} columns and 1..${limits.maxRows} rows (got $cols×$rws)")
            }
            p = GridRules.setGrid(p, cols, rws, limits)
                ?: throw ApiException(409, "grid_too_small", "The ${p.cards.size} cards of page '${p.id}' do not fit a $cols×$rws grid; delete or shrink cards first")
        }
        val pages = d.pages(t).toMutableList()
        pages[index] = p
        if (position != null) {
            if (position !in pages.indices) throw badRequest("position must be 0..${pages.lastIndex}")
            pages.add(position, pages.removeAt(index))
        }
        return d.withPages(t, pages) to p
    }

    fun deletePage(d: Dashboard, t: Target, ref: String): Dashboard {
        val (_, page) = findPage(d, t, ref)
        return d.withPages(t, d.pages(t).filterNot { it.id == page.id })
    }

    fun applyPreset(d: Dashboard, t: Target, ref: String, presetId: String): Triple<Dashboard, Page, Int> {
        val (_, page) = findPage(d, t, ref)
        val preset = findPreset(t, presetId)
        val dropped = GridRules.droppedByPreset(page, preset)
        val result = GridRules.applyPreset(page, preset)
        return Triple(d.updatePage(t, page.id) { result }, result, dropped)
    }

    // ---------------------------------------------------------------- cards

    /**
     * Adds [card] to page [ref]. With [explicitPosition] the card must fit exactly at its col/row
     * and span; otherwise it goes to the first free slot of its span.
     */
    fun addCard(d: Dashboard, t: Target, ref: String, card: Card, explicitPosition: Boolean): Pair<Dashboard, Card> {
        val (_, page) = findPage(d, t, ref)
        val limits = GridLimits.of(t)
        var c = card
        if (c.id.isBlank()) c = c.copy(id = io.github.jonathangtd.bimmerdata.dashboard.newId())
        if (c.id in usedIds(d)) throw ApiException(409, "duplicate_id", "Id '${c.id}' is already used in the dashboard; omit id to get a new one")
        val issues = cardIssues(c)
        if (issues.isNotEmpty()) throw invalidCard(issues)
        if (page.cards.size >= limits.maxCards) throw ApiException(409, "page_full", "A ${targetName(t)} page holds at most ${limits.maxCards} cards; page '${page.id}' is full")
        if (c.colSpan > page.columns || c.rowSpan > page.rows) {
            throw ApiException(422, "out_of_grid", "A ${c.colSpan}×${c.rowSpan} card does not fit the ${page.columns}×${page.rows} grid of page '${page.id}'")
        }
        if (explicitPosition) {
            if (!GridRules.inBounds(page, c)) throw ApiException(422, "out_of_grid", "${describe(c)} is outside the ${page.columns}×${page.rows} grid of page '${page.id}'")
            page.cards.firstOrNull { GridRules.overlaps(it, c) }?.let {
                throw ApiException(409, "card_overlap", "${describe(c)} overlaps ${describe(it)}; free cells: ${freeCells(page)}")
            }
        } else {
            val (col, row) = GridRules.firstFreeSlot(page, c.colSpan, c.rowSpan)
                ?: throw ApiException(409, "no_free_slot", "No free ${c.colSpan}×${c.rowSpan} slot on page '${page.id}'; free cells: ${freeCells(page)}")
            c = c.copy(col = col, row = row)
        }
        return d.updatePage(t, page.id) { it.copy(cards = it.cards + c) } to c
    }

    /** Replaces card [cardId] by [updated] (same id); it must still fit where it is placed. */
    fun updateCard(d: Dashboard, t: Target, ref: String, cardId: String, updated: Card): Pair<Dashboard, Card> {
        val (_, page) = findPage(d, t, ref)
        findCard(page, cardId)
        if (updated.id != cardId) throw badRequest("A card id cannot be changed (was '$cardId', got '${updated.id}')")
        val issues = cardIssues(updated)
        if (issues.isNotEmpty()) throw invalidCard(issues)
        if (!GridRules.inBounds(page, updated)) throw ApiException(422, "out_of_grid", "${describe(updated)} is outside the ${page.columns}×${page.rows} grid of page '${page.id}'")
        page.cards.firstOrNull { it.id != cardId && GridRules.overlaps(it, updated) }?.let {
            throw ApiException(409, "card_overlap", "${describe(updated)} overlaps ${describe(it)}; free cells: ${freeCells(page.copy(cards = page.cards.filterNot { c -> c.id == cardId }))}")
        }
        return d.updatePage(t, page.id) { it.replace(updated) } to updated
    }

    fun deleteCard(d: Dashboard, t: Target, ref: String, cardId: String): Dashboard {
        val (_, page) = findPage(d, t, ref)
        findCard(page, cardId)
        return d.updatePage(t, page.id) { p -> p.copy(cards = p.cards.filterNot { it.id == cardId }) }
    }

    /** "(0,2) (1,2)" — free cells as (col,row). */
    fun freeCells(page: Page): String {
        val cells = ArrayList<String>()
        for (r in 0 until page.rows) for (c in 0 until page.columns) if (GridRules.isFree(page, c, r)) cells += "($c,$r)"
        return if (cells.isEmpty()) "none" else cells.joinToString(" ")
    }

    private fun invalidCard(issues: List<LayoutIssue>) =
        ApiException(422, "invalid_card", issues.joinToString("; ") { it.message }, ApiJson.encodeToJsonElement(issues) as JsonArray)
}
