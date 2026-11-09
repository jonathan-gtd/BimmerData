/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.dashboardDependencies
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementClient
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A value the graph can show: a measurement of a card of the active profile, and where that card is. */
data class GraphChoice(
    val measurementId: String,
    /** "Phone · Engine" / "Android Auto · Page 1". */
    val place: String,
    /** First card showing it (its title, decimals and range are reused). */
    val card: Card,
)

data class GraphUi(
    val loaded: Boolean = false,
    /** Active profile; null = no profile yet. */
    val profileId: String? = null,
    val profileName: String = "",
    val graphs: List<Graph> = emptyList(),
    val selectedId: String? = null,
    /** Values of the profile's cards, phone pages first. */
    val choices: List<GraphChoice> = emptyList(),
) {
    val selectedIndex: Int get() = graphs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val selected: Graph? get() = graphs.getOrNull(selectedIndex)
    val hasCards: Boolean get() = choices.isNotEmpty()
}

/**
 * The Graph tab: graphs of the active profile, stored in the profile ([Profiles.updateGraphs], so
 * the dashboard editor never overwrites them), and the values they can show (those of the
 * profile's cards). While the tab is on screen it asks the engine for the values of the graph
 * shown ([MeasurementClient.Graph]); off screen it asks for nothing and the curves come from
 * [SharedHistory].
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GraphViewModel(app: Application) : AndroidViewModel(app) {
    private val deps = dashboardDependencies(app)
    private val store = ProfileStore.get(app)

    /** Writes in the order they were asked for. */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    private val _ui = MutableStateFlow(GraphUi())
    val ui: StateFlow<GraphUi> = _ui.asStateFlow()

    private val _profiles = MutableStateFlow(ProfileSet())
    val profiles: StateFlow<ProfileSet> = _profiles.asStateFlow()

    private val _index = MutableStateFlow<Map<String, Measurement>>(emptyMap())

    /** Catalog metadata (translated label, unit, range) of the values, by id. */
    val index: StateFlow<Map<String, Measurement>> = _index.asStateFlow()
    private val loadedSgbds = HashSet<String>()

    val status: StateFlow<AcquisitionStatus> = deps.liveDataSource.status
    val connection: StateFlow<LiveConnection> = deps.connection
    fun connect(mode: LiveMode) = deps.connect(mode)
    fun disconnect() = deps.disconnect()

    private val foreground = MutableStateFlow(false)

    init {
        SharedHistory.start(deps.liveDataSource)
        viewModelScope.launch {
            apply(withContext(Dispatchers.IO) { store.load() })
            ProfileStore.state.filterNotNull().collect { apply(it) }
        }
        // catalog metadata of every value offered or shown
        viewModelScope.launch {
            _ui.map { ui -> (ui.choices.map { it.measurementId } + ui.graphs.flatMap { it.measurementIds() }).map { sgbdOf(it) }.toSet() }
                .distinctUntilChanged().collect { sgbds -> sgbds.forEach { loadSgbd(it) } }
        }
        viewModelScope.launch {
            io.github.jonathangtd.bimmerdata.live.i18n.Translations.shared.version.drop(1).debounce(300).collect {
                val again = loadedSgbds.toList()
                loadedSgbds.clear()
                again.forEach { loadSgbd(it) }
            }
        }
        // On screen: read the visible values of the graph shown (they are values of the cards).
        viewModelScope.launch {
            combine(_ui, _index, foreground) { ui, index, fg ->
                if (!fg) {
                    null
                } else {
                    // the graph's values, or the whole profile when that costs no extra read cycle
                    val visible = ui.selected?.visibleIds().orEmpty().mapNotNull { index[it] }
                    val profile = ui.choices.map { it.measurementId }.mapNotNull { index[it] }
                    io.github.jonathangtd.bimmerdata.live.ReadScope.select(profile, visible)
                }
            }.distinctUntilChanged().collect { list ->
                if (list == null) deps.clearMeasurements(MeasurementClient.Graph)
                else deps.requestMeasurements(MeasurementClient.Graph, list, foreground = true)
            }
        }
    }

    override fun onCleared() {
        deps.clearMeasurements(MeasurementClient.Graph)
    }

    /** True while the Graph tab is on the phone screen (lifecycle started). */
    fun setForeground(on: Boolean) {
        foreground.value = on
    }

    private fun apply(set: ProfileSet) {
        _profiles.value = set
        val p = set.active
        _ui.update { ui ->
            val graphs = p?.graphs.orEmpty()
            val sameProfile = ui.profileId == p?.id
            val selected = ui.selectedId?.takeIf { sameProfile && graphs.any { g -> g.id == it } } ?: graphs.firstOrNull()?.id
            ui.copy(
                loaded = true, profileId = p?.id, profileName = p?.name.orEmpty(), graphs = graphs, selectedId = selected,
                choices = p?.let { choicesOf(it.dashboard) }.orEmpty(),
            )
        }
    }

    private fun choicesOf(d: Dashboard): List<GraphChoice> {
        val app = getApplication<Application>()
        val seen = HashSet<String>()
        val out = ArrayList<GraphChoice>()
        for ((pages, place) in listOf(d.phonePages to R.string.graph_place_phone, d.carPages to R.string.graph_place_car)) {
            for (page in pages) for (card in page.cards) for (id in card.measurementIds) {
                if (seen.add(id)) out += GraphChoice(id, app.getString(place, page.name), card)
            }
        }
        return out
    }

    private fun sgbdOf(id: String) = id.substringBefore('/').lowercase()

    private suspend fun loadSgbd(sgbd: String) {
        if (sgbd.isEmpty() || !loadedSgbds.add(sgbd)) return
        val list = withContext(Dispatchers.IO) { runCatching { deps.catalog.measurements(sgbd) }.getOrDefault(emptyList()) }
        _index.update { it + list.associateBy { m -> m.id } }
    }

    // ---- what the renderer draws ----

    /** Label shown for a value: the card's own title for a one-value card, else the catalog label. */
    fun label(id: String, ui: GraphUi = _ui.value, index: Map<String, Measurement> = _index.value): String {
        val card = ui.choices.firstOrNull { it.measurementId == id }?.card
        val title = card?.title?.takeIf { it.isNotBlank() && card.measurementIds.size == 1 }
        return title ?: index[id]?.label ?: id.substringAfter('/')
    }

    fun specs(graph: Graph, ui: GraphUi, index: Map<String, Measurement>, format: GaugeFormat): List<GraphSeriesSpec> = graph.series.map { s ->
        val m = index[s.measurementId]
        val card = ui.choices.firstOrNull { it.measurementId == s.measurementId }?.card
        val rawUnit = m?.unit.orEmpty()
        val label = label(s.measurementId, ui, index)
        GraphSeriesSpec(
            id = s.measurementId,
            label = label,
            unit = format.displayUnit(rawUnit, m?.label ?: label),
            decimals = card?.decimals ?: GaugeFormat.defaultDecimals(rawUnit, m?.label ?: label),
            color = GraphPalette.color(s.color),
            minSpan = GraphRenderer.minSpan(card?.min ?: m?.min, card?.max ?: m?.max, rawUnit),
            hidden = s.hidden,
        )
    }

    private val panelStates = HashMap<String, io.github.jonathangtd.bimmerdata.graph.ui.GraphPanelState>()

    /** Time view and cursor of graph [g] on screen, kept across rotations (portrait / landscape layouts). */
    fun panelState(g: Graph): io.github.jonathangtd.bimmerdata.graph.ui.GraphPanelState =
        panelStates.getOrPut(g.id) { io.github.jonathangtd.bimmerdata.graph.ui.GraphPanelState(GraphView.of(g.windowSeconds)) }

    // ---- edits (written into the active profile, in order) ----

    private fun edit(transform: (List<Graph>) -> List<Graph>) {
        val id = _ui.value.profileId ?: return
        _ui.update { it.copy(graphs = transform(it.graphs)) } // shown at once; the store echo follows
        viewModelScope.launch(writer) { store.update { Profiles.updateGraphs(it, id, transform) } }
    }

    fun select(id: String) = _ui.update { it.copy(selectedId = id) }

    /** New graph with [ids], shown at once. */
    fun addGraph(name: String, ids: List<String>) {
        val id = io.github.jonathangtd.bimmerdata.dashboard.newId()
        edit { Graphs.add(it, name, ids, id) }
        if (_ui.value.graphs.any { it.id == id }) select(id)
    }

    fun rename(id: String, name: String) = edit { Graphs.rename(it, id, name) }
    fun delete(id: String) = edit { Graphs.delete(it, id) }
    fun move(from: Int, to: Int) = edit { Graphs.move(it, from, to) }
    fun setSeries(id: String, ids: List<String>) = edit { Graphs.setSeries(it, id, ids) }
    fun setColor(id: String, measurementId: String, color: Int) = edit { Graphs.setColor(it, id, measurementId, color) }
    fun toggleHidden(id: String, measurementId: String) = edit { Graphs.toggleHidden(it, id, measurementId) }
    fun setWindow(id: String, seconds: Int?) = edit { Graphs.setWindow(it, id, seconds) }
    fun setAxes(id: String, axes: GraphAxes) = edit { Graphs.setAxes(it, id, axes) }

    suspend fun activateProfile(id: String) {
        withContext(Dispatchers.IO) { store.activate(id) }
    }
}
