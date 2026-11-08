/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import kotlinx.serialization.Serializable
import java.util.UUID

/** Which screen a page set is for. */
@Serializable
enum class Target { Phone, Car }

/** Grid size limits per target. Android Auto pages stay simple: few, big cards. */
data class GridLimits(val maxColumns: Int, val maxRows: Int, val maxCards: Int) {
    companion object {
        val Phone = GridLimits(maxColumns = 4, maxRows = 8, maxCards = 32)
        val Car = GridLimits(maxColumns = 4, maxRows = 2, maxCards = 4)
        fun of(target: Target) = if (target == Target.Phone) Phone else Car
    }
}

/** The whole user layout: separate page sets for the phone and for Android Auto. */
@Serializable
data class Dashboard(
    val formatVersion: Int = FORMAT_VERSION,
    val phonePages: List<Page> = emptyList(),
    val carPages: List<Page> = emptyList(),
    /** Name shown in the header (e.g. the car: "330i"); null = the app name. */
    val name: String? = null,
) {
    fun pages(target: Target): List<Page> = if (target == Target.Phone) phonePages else carPages

    fun withPages(target: Target, pages: List<Page>): Dashboard =
        if (target == Target.Phone) copy(phonePages = pages) else copy(carPages = pages)

    fun updatePage(target: Target, pageId: String, transform: (Page) -> Page): Dashboard =
        withPages(target, pages(target).map { if (it.id == pageId) transform(it) else it })

    /** Every measurement id used by any card, phone and car. */
    fun measurementIds(): Set<String> =
        (phonePages + carPages).flatMapTo(LinkedHashSet()) { p -> p.cards.flatMap { it.measurementIds } }

    /** [name] if set, else [fallback]. */
    fun title(fallback: String): String = name?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_NAME_LENGTH = 40
    }
}

/** One screen of cards on a columns × rows grid that fills the available area. */
@Serializable
data class Page(
    val id: String = newId(),
    val name: String,
    val columns: Int,
    val rows: Int,
    val cards: List<Card> = emptyList(),
) {
    fun card(id: String): Card? = cards.firstOrNull { it.id == id }
    fun replace(card: Card): Page = copy(cards = cards.map { if (it.id == card.id) card else it })
    fun measurementIds(): List<String> = cards.flatMap { it.measurementIds }.distinct()
}

/** A card placed on the grid: what it shows and where. Range / thresholds null = automatic. */
@Serializable
data class Card(
    val id: String = newId(),
    val type: GaugeType,
    /** [io.github.jonathangtd.bimmerdata.live.Measurement.id]s, e.g. "msv70/0x4402". */
    val measurementIds: List<String>,
    val title: String? = null,
    /** Short per-measurement labels for multi-row cards ("B1", "B2"); null = derived. */
    val channelLabels: List<String>? = null,
    val min: Double? = null,
    val max: Double? = null,
    val warnBelow: Double? = null,
    val warnAbove: Double? = null,
    val decimals: Int? = null,
    /** Trend cards: seconds of history shown ([io.github.jonathangtd.bimmerdata.gauges.TrendWindow]); null = 45 s. */
    val windowSeconds: Int? = null,
    val col: Int = 0,
    val row: Int = 0,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
) {
    val colEnd get() = col + colSpan
    val rowEnd get() = row + rowSpan

    companion object {
        /** How many measurements each card type takes. */
        fun measurementRange(type: GaugeType): IntRange = when (type) {
            GaugeType.BipolarBar -> 1..4
            GaugeType.MultiValue -> 2..3
            else -> 1..1
        }
    }
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)
