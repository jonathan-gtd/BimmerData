/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.RecordingState
import kotlinx.coroutines.Dispatchers
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

data class DashboardUi(
    val dashboard: Dashboard? = null,
    val editing: Boolean = false,
    /** Which page set the editor shows. The normal (non-edit) view always shows phone pages. */
    val target: Target = Target.Phone,
    val phonePage: Int = 0,
    val carPage: Int = 0,
    /** Last removed card, for "Undo". */
    val undo: Dashboard? = null,
) {
    fun pageIndex(t: Target) = if (t == Target.Phone) phonePage else carPage
    fun currentPage(t: Target): Page? = dashboard?.pages(t)?.let { pages -> pages.getOrNull(pageIndex(t).coerceIn(0, (pages.size - 1).coerceAtLeast(0))) }
}

@OptIn(FlowPreview::class)
class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val deps = dashboardDependencies(app)
    private val store = DashboardStore(app)

    private val _ui = MutableStateFlow(DashboardUi())
    val ui: StateFlow<DashboardUi> = _ui.asStateFlow()

    /** Read by the card renderers at draw time. */
    val feed = LiveFeed()

    val status: StateFlow<AcquisitionStatus> = deps.liveDataSource.status
    val recording: StateFlow<RecordingState> = deps.recorder.state
    val connection: StateFlow<io.github.jonathangtd.bimmerdata.live.LiveConnection> = deps.connection

    fun connect(mode: io.github.jonathangtd.bimmerdata.live.LiveMode) = deps.connect(mode)
    fun disconnect() = deps.disconnect()

    private val _index = MutableStateFlow<Map<String, Measurement>>(emptyMap())

    /** Catalog metadata of the measurements used on cards (label, unit, range), by id. */
    val measurementIndex: StateFlow<Map<String, Measurement>> = _index.asStateFlow()
    private val loadedSgbds = HashSet<String>()

    init {
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { store.load() }
            _ui.update { it.copy(dashboard = d) }
        }
        viewModelScope.launch {
            deps.liveDataSource.samples.collect { feed.update(it) }
        }
        // Persist (debounced) after every change.
        viewModelScope.launch {
            _ui.map { it.dashboard }.filterNotNull().distinctUntilChanged().drop(1).debounce(400).collect { d ->
                withContext(Dispatchers.IO) { store.save(d) }
            }
        }
        // Load catalog metadata for every SGBD referenced by the layout.
        viewModelScope.launch {
            _ui.map { it.dashboard?.measurementIds().orEmpty() }.distinctUntilChanged().collect { ids ->
                ids.map { sgbdOf(it) }.distinct().forEach { loadSgbd(it) }
            }
        }
        // Ask the acquisition engine for what is on screen.
        viewModelScope.launch {
            combine(_ui, _index) { ui, index ->
                val pages = listOfNotNull(
                    ui.currentPage(Target.Phone),
                    if (ui.editing && ui.target == Target.Car) ui.currentPage(Target.Car) else null,
                )
                pages.flatMap { it.measurementIds() }.distinct().mapNotNull { index[it] }
            }.distinctUntilChanged().collect { deps.liveDataSource.setMeasurements(it) }
        }
    }

    private fun sgbdOf(id: String) = id.substringBefore('/').lowercase()

    private suspend fun loadSgbd(sgbd: String) {
        if (sgbd.isEmpty() || !loadedSgbds.add(sgbd)) return
        val list = withContext(Dispatchers.IO) { runCatching { deps.catalog.measurements(sgbd) }.getOrDefault(emptyList()) }
        _index.update { it + list.associateBy { m -> m.id } }
    }

    // ---- catalog for the card picker ----

    suspend fun sgbds(): List<String> = withContext(Dispatchers.IO) {
        runCatching { deps.catalog.sgbdsWithMeasurements() }.getOrDefault(emptyList())
    }

    suspend fun measurements(sgbd: String): List<Measurement> {
        val list = withContext(Dispatchers.IO) { runCatching { deps.catalog.measurements(sgbd) }.getOrDefault(emptyList()) }
        loadedSgbds.add(sgbd.lowercase())
        _index.update { it + list.associateBy { m -> m.id } }
        return list
    }

    // ---- modes and navigation ----

    fun setEditing(on: Boolean) = _ui.update { it.copy(editing = on, target = if (on) it.target else Target.Phone, undo = null) }
    fun setTarget(t: Target) = _ui.update { it.copy(target = t) }
    fun selectPage(t: Target, index: Int) = _ui.update { if (t == Target.Phone) it.copy(phonePage = index) else it.copy(carPage = index) }

    // ---- layout edits; each returns false when refused so the UI can say why ----

    private inline fun editPage(t: Target, pageId: String, crossinline op: (Page) -> Page?): Boolean {
        var ok = false
        _ui.update { ui ->
            val d = ui.dashboard ?: return@update ui
            val page = d.pages(t).firstOrNull { it.id == pageId } ?: return@update ui
            val result = op(page) ?: return@update ui
            ok = true
            ui.copy(dashboard = d.updatePage(t, pageId) { result }, undo = null)
        }
        return ok
    }

    fun moveCard(t: Target, pageId: String, cardId: String, col: Int, row: Int) = editPage(t, pageId) { GridRules.move(it, cardId, col, row) }
    fun resizeCard(t: Target, pageId: String, cardId: String, colSpan: Int, rowSpan: Int) =
        editPage(t, pageId) { GridRules.resize(it, cardId, colSpan, rowSpan) }

    fun addCard(t: Target, pageId: String, card: Card) = editPage(t, pageId) { GridRules.add(it, card, GridLimits.of(t)) }

    /** Replaces a card's settings (and span, if it still fits). */
    fun updateCard(t: Target, pageId: String, card: Card) = editPage(t, pageId) { page ->
        if (GridRules.canPlace(page, card)) page.replace(card) else null
    }

    fun deleteCard(t: Target, pageId: String, cardId: String) {
        _ui.update { ui ->
            val d = ui.dashboard ?: return@update ui
            ui.copy(dashboard = d.updatePage(t, pageId) { p -> p.copy(cards = p.cards.filterNot { it.id == cardId }) }, undo = d)
        }
    }

    fun undo() = _ui.update { ui -> ui.undo?.let { ui.copy(dashboard = it, undo = null) } ?: ui }

    fun applyPreset(t: Target, pageId: String, preset: LayoutPreset) = editPage(t, pageId) { GridRules.applyPreset(it, preset) }
    fun setGrid(t: Target, pageId: String, columns: Int, rows: Int) = editPage(t, pageId) { GridRules.setGrid(it, columns, rows, GridLimits.of(t)) }

    fun addPage(t: Target, name: String) {
        _ui.update { ui ->
            val d = ui.dashboard ?: return@update ui
            val page = if (t == Target.Phone) Page(name = name, columns = 2, rows = 6) else Page(name = name, columns = 4, rows = 1)
            val pages = d.pages(t) + page
            val withPage = ui.copy(dashboard = d.withPages(t, pages))
            if (t == Target.Phone) withPage.copy(phonePage = pages.lastIndex) else withPage.copy(carPage = pages.lastIndex)
        }
    }

    fun renamePage(t: Target, pageId: String, name: String) = editPage(t, pageId) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun deletePage(t: Target, pageId: String) {
        _ui.update { ui ->
            val d = ui.dashboard ?: return@update ui
            val pages = d.pages(t)
            if (pages.size <= 1) return@update ui
            val remaining = pages.filterNot { it.id == pageId }
            val updated = ui.copy(dashboard = d.withPages(t, remaining), undo = d)
            if (t == Target.Phone) updated.copy(phonePage = ui.phonePage.coerceAtMost(remaining.lastIndex))
            else updated.copy(carPage = ui.carPage.coerceAtMost(remaining.lastIndex))
        }
    }

    fun movePage(t: Target, from: Int, to: Int) {
        _ui.update { ui ->
            val d = ui.dashboard ?: return@update ui
            val pages = d.pages(t).toMutableList()
            if (from !in pages.indices || to !in pages.indices) return@update ui
            pages.add(to, pages.removeAt(from))
            ui.copy(dashboard = d.withPages(t, pages))
        }
    }

    // ---- import / export ----

    fun exportJson(): String? = _ui.value.dashboard?.let { DashboardJson.encode(it) }

    /** Replaces the whole layout; returns null on success or the error message. */
    fun importJson(text: String): String? = try {
        val d = DashboardJson.decode(text)
        _ui.update { it.copy(dashboard = d, phonePage = 0, carPage = 0, undo = it.dashboard) }
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "Invalid file"
    }

    fun resetToDefault() {
        val d = DashboardStore.defaultDashboard(getApplication())
        _ui.update { it.copy(dashboard = d, phonePage = 0, carPage = 0, undo = it.dashboard) }
    }

    // ---- recording ----

    fun toggleRecording() {
        if (deps.recorder.state.value.recording) deps.recorder.stop() else deps.recorder.start()
    }

    fun mark() = deps.recorder.mark()
}
