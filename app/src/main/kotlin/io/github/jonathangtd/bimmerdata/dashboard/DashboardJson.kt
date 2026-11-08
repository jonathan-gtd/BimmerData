/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** JSON form of a [Dashboard], used for storage and for sharing layouts between users. */
object DashboardJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(dashboard: Dashboard): String = json.encodeToString(Dashboard.serializer(), dashboard)

    /** Parses and repairs [text]; throws [IllegalArgumentException] if it is not a dashboard. */
    fun decode(text: String): Dashboard {
        val parsed = try {
            json.decodeFromString(Dashboard.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Not a BimmerData dashboard: ${e.message}", e)
        }
        require(parsed.formatVersion <= Dashboard.FORMAT_VERSION) { "Dashboard format ${parsed.formatVersion} is newer than this app" }
        require(parsed.phonePages.isNotEmpty() || parsed.carPages.isNotEmpty()) { "The dashboard has no pages" }
        return sanitize(parsed)
    }

    /**
     * Makes an imported layout safe to show: grid sizes within limits, unique ids, cards inside
     * the grid and not overlapping (cards that cannot be placed are dropped), at least one page.
     */
    fun sanitize(d: Dashboard): Dashboard {
        val seen = HashSet<String>()
        fun uniqueId(id: String) = if (id.isNotBlank() && seen.add(id)) id else newId().also { seen.add(it) }
        fun fixPages(pages: List<Page>, target: Target, fallbackName: String): List<Page> {
            val limits = GridLimits.of(target)
            val fixed = pages.map { page ->
                var p = Page(
                    id = uniqueId(page.id), name = page.name.ifBlank { fallbackName },
                    columns = page.columns.coerceIn(1, limits.maxColumns), rows = page.rows.coerceIn(1, limits.maxRows),
                )
                for (card in page.cards) {
                    if (card.measurementIds.isEmpty()) continue
                    val c = card.copy(
                        id = uniqueId(card.id),
                        colSpan = card.colSpan.coerceIn(1, p.columns), rowSpan = card.rowSpan.coerceIn(1, p.rows),
                        decimals = card.decimals?.coerceIn(0, 4),
                    ).let { it.copy(col = it.col.coerceIn(0, p.columns - it.colSpan), row = it.row.coerceIn(0, p.rows - it.rowSpan)) }
                    p = GridRules.add(p, c, limits) ?: p
                }
                p
            }
            return fixed.ifEmpty { listOf(Page(name = fallbackName, columns = if (target == Target.Phone) 2 else 4, rows = if (target == Target.Phone) 6 else 1)) }
        }
        return Dashboard(
            formatVersion = Dashboard.FORMAT_VERSION,
            phonePages = fixPages(d.phonePages, Target.Phone, "1"),
            carPages = fixPages(d.carPages, Target.Car, "1"),
            name = d.name?.trim()?.take(Dashboard.MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() },
        )
    }
}
