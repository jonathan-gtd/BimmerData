/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

/**
 * **Android Auto shows exactly the phone's cards**, enforced on every write (editor, REST API,
 * profile files, backups...): each Android Auto card is linked to one phone card
 * ([Card.phoneCardId]) and shows what that card shows; Android Auto only owns where its cards are
 * (page, `col`, `row`, `colSpan`, `rowSpan`) and its pages (names, grids, order).
 *
 * [syncCar] brings any layout to that state: Android Auto cards with no phone card are removed,
 * the content of the others is copied from their phone card, and the phone cards missing on
 * Android Auto are placed there (first free cell of an Android Auto page showing cards of the same
 * phone page, growing its grid up to 4 × 2 if needed, else on a new page named after the phone
 * page). It changes nothing in a layout that already follows the rule, so it can run on every
 * save.
 *
 * Linking an Android Auto card to its phone card, in this order: [Card.phoneCardId] (or the link
 * the card had before the edit, [known]); then, for layouts written before the link existed, the
 * first phone card not linked yet with the same content, then with the same type, values and
 * title, then with the same type and values.
 */
object DashboardRules {

    /** What a card shows, whatever its place and id (the part Android Auto takes from the phone). */
    fun content(c: Card): Card = c.copy(id = "", phoneCardId = null, col = 0, row = 0, colSpan = 1, rowSpan = 1)

    /**
     * For each card of [car] (same order), the phone card of [phone] it is linked to, or null.
     * [known]: car card id -> phone card id of an earlier version of the layout.
     */
    fun links(phone: List<Card>, car: List<Card>, known: Map<String, String> = emptyMap()): List<Card?> {
        val byId = phone.associateBy { it.id }
        val claimed = HashSet<String>()
        val out = arrayOfNulls<Card>(car.size)
        fun claim(i: Int, p: Card?) {
            if (p != null && claimed.add(p.id)) out[i] = p
        }
        car.forEachIndexed { i, c -> claim(i, (c.phoneCardId ?: known[c.id])?.let(byId::get)) }
        val passes: List<(Card, Card) -> Boolean> = listOf(
            { c, p -> content(c) == content(p) },
            { c, p -> c.type == p.type && c.measurementIds == p.measurementIds && c.title == p.title },
            { c, p -> c.type == p.type && c.measurementIds == p.measurementIds },
        )
        for (same in passes) {
            car.forEachIndexed { i, c ->
                if (out[i] != null || c.phoneCardId != null && c.phoneCardId in byId) return@forEachIndexed
                claim(i, phone.firstOrNull { it.id !in claimed && same(c, it) })
            }
        }
        return out.toList()
    }

    /** The phone cards in reading order: page after page, then row and column. */
    private fun phoneCards(d: Dashboard): List<Pair<Page, Card>> =
        d.phonePages.flatMap { p -> p.cards.sortedWith(compareBy({ it.row }, { it.col })).map { p to it } }

    /** Car card id -> phone card id, as [links] resolves them in [d]. */
    fun linkMap(d: Dashboard): Map<String, String> {
        val car = d.carPages.flatMap { it.cards }
        val l = links(phoneCards(d).map { it.second }, car)
        return car.indices.mapNotNull { i -> l[i]?.let { car[i].id to it.id } }.toMap()
    }

    /** True when [d] follows the rule (a layout written before the link field may lack it). */
    fun follows(d: Dashboard): Boolean {
        val phone = phoneCards(d).map { it.second }
        val car = d.carPages.flatMap { it.cards }
        if (phone.size != car.size) return false
        val l = links(phone, car)
        return car.indices.all { i -> l[i]?.let { content(it) == content(car[i]) } == true }
    }

    /** [d] brought to the rule (see the class). */
    fun syncCar(d: Dashboard): Dashboard {
        val phone = phoneCards(d)
        val carFlat = d.carPages.flatMap { it.cards }
        val l = links(phone.map { it.second }, carFlat)
        val used = HashSet<String>()
        d.phonePages.forEach { p -> used += p.id; p.cards.forEach { used += it.id } }
        d.carPages.forEach { p -> used += p.id; p.cards.forEach { used += it.id } }
        fun fresh(): String {
            var id = newId()
            while (!used.add(id)) id = newId()
            return id
        }
        // linked cards refreshed from their phone card, unlinked ones dropped
        var k = 0
        val pages = d.carPages.map { page ->
            page.copy(cards = page.cards.mapNotNull { c ->
                val p = l[k++] ?: return@mapNotNull null
                p.copy(id = c.id, phoneCardId = p.id, col = c.col, row = c.row, colSpan = c.colSpan, rowSpan = c.rowSpan)
            })
        }.toMutableList()
        val onCar = l.filterNotNull().mapTo(HashSet()) { it.id }
        val missing = phone.filter { it.second.id !in onCar }
        if (missing.isEmpty()) return if (pages == d.carPages) d else d.copy(carPages = pages)

        val pageOf = phone.associate { it.second.id to it.first.id }
        val leftovers = LinkedHashMap<Page, MutableList<Card>>()
        for ((phonePage, card) in missing) {
            // Android Auto pages already showing cards of this phone page
            val candidates = pages.indices.filter { i -> pages[i].cards.any { c -> c.phoneCardId?.let(pageOf::get) == phonePage.id } }
            var placed = false
            for (i in candidates) {
                val at = place(pages[i]) ?: continue
                val (grown, col, row) = at
                pages[i] = grown.copy(cards = grown.cards + card.copy(id = fresh(), phoneCardId = card.id, col = col, row = row, colSpan = 1, rowSpan = 1))
                placed = true
                break
            }
            if (!placed) leftovers.getOrPut(phonePage) { ArrayList() } += card
        }
        val names = pages.mapTo(HashSet()) { it.name }
        for ((phonePage, cards) in leftovers) {
            for (chunk in cards.chunked(GridLimits.Car.maxCards)) {
                val (columns, rows) = DashboardMirror.gridFor(chunk.size)
                var name = phonePage.name
                var n = 2
                while (name in names) name = "${phonePage.name} ${n++}"
                names += name
                pages += Page(
                    id = fresh(), name = name, columns = columns, rows = rows,
                    cards = chunk.mapIndexed { c, card -> card.copy(id = fresh(), phoneCardId = card.id, col = c % columns, row = c / columns, colSpan = 1, rowSpan = 1) },
                )
            }
        }
        return d.copy(carPages = pages)
    }

    /**
     * A free 1 x 1 cell of Android Auto page [page], growing its grid towards 4 x 2 when it is
     * full (cards keep their cells); null when the page holds its maximum.
     */
    private fun place(page: Page): Triple<Page, Int, Int>? {
        val lim = GridLimits.Car
        if (page.cards.size >= lim.maxCards) return null
        GridRules.firstFreeSlot(page, 1, 1)?.let { (c, r) -> return Triple(page, c, r) }
        val (wantCols, wantRows) = DashboardMirror.gridFor(page.cards.size + 1)
        val grown = page.copy(
            columns = maxOf(page.columns, wantCols).coerceAtMost(lim.maxColumns),
            rows = maxOf(page.rows, wantRows).coerceAtMost(lim.maxRows),
        )
        if (grown == page) return null
        return GridRules.firstFreeSlot(grown, 1, 1)?.let { (c, r) -> Triple(grown, c, r) }
    }

    /** Android Auto changes of a write that break the rule (empty = allowed). */
    data class CarViolations(
        /** Android Auto cards with no phone card (added on the Android Auto side). */
        val extra: List<Card> = emptyList(),
        /** Phone cards that Android Auto showed and that the write takes off Android Auto. */
        val missing: List<Card> = emptyList(),
        /** Android Auto cards whose content (type, values, title, thresholds...) differs from their phone card. */
        val changed: List<Card> = emptyList(),
    ) {
        val isEmpty get() = extra.isEmpty() && missing.isEmpty() && changed.isEmpty()

        fun describe(): String = listOfNotNull(
            extra.takeIf { it.isNotEmpty() }?.let { l -> "not on the phone: " + l.joinToString { label(it) } },
            missing.takeIf { it.isNotEmpty() }?.let { l -> "taken off Android Auto: " + l.joinToString { label(it) } },
            changed.takeIf { it.isNotEmpty() }?.let { l -> "different from the phone card: " + l.joinToString { label(it) } },
        ).joinToString("; ")

        private fun label(c: Card) = "'${c.id}' ${c.type} ${c.measurementIds.joinToString("+")}" + (c.title?.let { " \"$it\"" } ?: "")
    }

    /**
     * What a write from [before] (the stored layout) to [after] changes on the Android Auto side
     * against the rule. Allowed: moving and resizing Android Auto cards, moving them to another
     * Android Auto page, pages; phone cards not on Android Auto yet (placed by [syncCar]); Android
     * Auto cards of phone cards removed or changed by the same write (they follow).
     */
    fun carViolations(before: Dashboard, after: Dashboard): CarViolations {
        val beforeLinks = linkMap(before)
        val beforeCar = before.carPages.flatMap { it.cards }.associateBy { it.id }
        val phone = phoneCards(after).map { it.second }
        val phoneIds = phone.mapTo(HashSet()) { it.id }
        val car = after.carPages.flatMap { it.cards }
        val l = links(phone, car, beforeLinks)
        val extra = ArrayList<Card>()
        val changed = ArrayList<Card>()
        car.forEachIndexed { i, c ->
            val p = l[i]
            val old = beforeCar[c.id]
            if (p == null) {
                // a card already there whose phone card this write removes (or never had one) follows the phone
                if (old == null || beforeLinks[c.id]?.let { it in phoneIds } == true) extra += c
            } else if (content(c) != content(p) && (old == null || content(c) != content(old))) {
                changed += c
            }
        }
        val linkedNow = l.filterNotNull().mapTo(HashSet()) { it.id }
        val missing = phone.filter { it.id in beforeLinks.values && it.id !in linkedNow }
        return CarViolations(extra, missing, changed)
    }

    /** True when only the place of Android Auto card [before] changes in [after]. */
    fun placeOnly(before: Card, after: Card): Boolean =
        content(before) == content(after) && before.phoneCardId == after.phoneCardId
}
