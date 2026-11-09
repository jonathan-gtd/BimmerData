/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph

import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.newId
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import kotlinx.serialization.Serializable

/** How the curves of a graph share the vertical space. */
@Serializable
enum class GraphAxes {
    /** All curves on one plot, one vertical axis per unit (left, then right). */
    Overlay,

    /** One band per value, each with its own scale, on a common time axis. */
    Bands,
}

/** One value of a graph: a measurement shown on a card, with its color (index in [GraphPalette]). */
@Serializable
data class GraphSeries(
    /** [io.github.jonathangtd.bimmerdata.live.Measurement.id], e.g. "<sgbd>/<id>". */
    val measurementId: String,
    val color: Int = 0,
    /** Hidden from the plot (tap on its legend entry); still listed in the legend. */
    val hidden: Boolean = false,
)

/** A named graph of a profile: several values of its cards on one time axis. */
@Serializable
data class Graph(
    val id: String = newId(),
    val name: String,
    val series: List<GraphSeries> = emptyList(),
    /** Visible time span in seconds ([TrendWindow]); null = the trend card default. */
    val windowSeconds: Int? = null,
    val axes: GraphAxes = GraphAxes.Overlay,
) {
    fun measurementIds(): List<String> = series.map { it.measurementId }

    /** Measurements drawn (not hidden). */
    fun visibleIds(): List<String> = series.filterNot { it.hidden }.map { it.measurementId }
}

/**
 * Categorical colors of the curves, in a fixed order validated for color vision deficiencies on a
 * dark surface (adjacent pairs). A value keeps its color when others are added or removed.
 */
object GraphPalette {
    val colors: IntArray = intArrayOf(
        0xFF3987E5.toInt(), // blue
        0xFFD95926.toInt(), // orange
        0xFF199E70.toInt(), // aqua
        0xFFC98500.toInt(), // yellow
        0xFFD55181.toInt(), // magenta
        0xFF008300.toInt(), // green
        0xFF9085E9.toInt(), // violet
        0xFFE66767.toInt(), // red
    )

    val size: Int get() = colors.size

    fun color(index: Int): Int = colors[index.mod(colors.size)]
}

/** Pure operations on the graphs of a profile (the store applies them under its lock). */
object Graphs {
    const val MAX_GRAPHS = 20

    /** One color each, never a generated one. */
    val MAX_SERIES: Int get() = GraphPalette.size
    const val MAX_NAME_LENGTH = 24

    fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)

    /** Every measurement shown on a card of [dashboard] (phone pages first, then Android Auto), no duplicates. */
    fun cardMeasurements(dashboard: Dashboard): List<String> = dashboard.measurementIds().toList()

    /** Appends a graph [id] named [name] with [ids] as values; unchanged at [MAX_GRAPHS] or if [id] exists. */
    fun add(list: List<Graph>, name: String, ids: List<String> = emptyList(), id: String = newId()): List<Graph> {
        if (list.size >= MAX_GRAPHS || list.any { it.id == id }) return list
        return list + Graph(id = id, name = cleanName(name).ifEmpty { "?" }, series = withSeries(emptyList(), ids))
    }

    fun rename(list: List<Graph>, id: String, name: String): List<Graph> {
        val clean = cleanName(name)
        if (clean.isEmpty()) return list
        return update(list, id) { it.copy(name = clean) }
    }

    fun delete(list: List<Graph>, id: String): List<Graph> = list.filterNot { it.id == id }

    fun move(list: List<Graph>, from: Int, to: Int): List<Graph> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The values of graph [id] become [ids] (in that order, at most [MAX_SERIES]). Values already
     * there keep their color and visibility; new ones take the first color no other value uses.
     */
    fun setSeries(list: List<Graph>, id: String, ids: List<String>): List<Graph> =
        update(list, id) { g -> g.copy(series = withSeries(g.series, ids)) }

    fun setColor(list: List<Graph>, id: String, measurementId: String, color: Int): List<Graph> =
        update(list, id) { g ->
            g.copy(series = g.series.map { if (it.measurementId == measurementId) it.copy(color = color.mod(GraphPalette.size)) else it })
        }

    fun toggleHidden(list: List<Graph>, id: String, measurementId: String): List<Graph> =
        update(list, id) { g -> g.copy(series = g.series.map { if (it.measurementId == measurementId) it.copy(hidden = !it.hidden) else it }) }

    /** Window in seconds, clamped to the trend card limits; null = default. */
    fun setWindow(list: List<Graph>, id: String, seconds: Int?): List<Graph> =
        update(list, id) { it.copy(windowSeconds = seconds?.coerceIn(TrendWindow.MIN_SECONDS, TrendWindow.MAX_SECONDS)) }

    fun setAxes(list: List<Graph>, id: String, axes: GraphAxes): List<Graph> = update(list, id) { it.copy(axes = axes) }

    /** Graphs read from a file: unique ids, clean names, valid colors and windows, limits applied. */
    fun sanitize(list: List<Graph>): List<Graph> {
        val seen = HashSet<String>()
        return list.filter { it.id.isNotBlank() && seen.add(it.id) }.take(MAX_GRAPHS).map { g ->
            val ids = HashSet<String>()
            g.copy(
                name = cleanName(g.name).ifEmpty { "?" },
                series = g.series.filter { it.measurementId.isNotBlank() && ids.add(it.measurementId) }.take(MAX_SERIES)
                    .map { it.copy(color = it.color.mod(GraphPalette.size)) },
                windowSeconds = g.windowSeconds?.takeIf { TrendWindow.isValid(it) },
            )
        }
    }

    /** First palette index not in [used] (the least used one when all are taken). */
    fun freeColor(used: List<Int>): Int {
        for (i in 0 until GraphPalette.size) if (i !in used) return i
        return (0 until GraphPalette.size).minBy { c -> used.count { it == c } }
    }

    private fun withSeries(old: List<GraphSeries>, ids: List<String>): List<GraphSeries> {
        val byId = old.associateBy { it.measurementId }
        val wanted = ids.filter { it.isNotBlank() }.distinct().take(MAX_SERIES)
        val kept = wanted.mapNotNull { byId[it] }
        val used = kept.map { it.color }.toMutableList()
        return wanted.map { id ->
            byId[id] ?: GraphSeries(id, freeColor(used)).also { used += it.color }
        }
    }

    private inline fun update(list: List<Graph>, id: String, transform: (Graph) -> Graph): List<Graph> =
        list.map { if (it.id == id) transform(it) else it }
}
