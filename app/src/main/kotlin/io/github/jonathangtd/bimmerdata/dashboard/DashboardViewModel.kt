/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.EdiabasMeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementClient
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.SgbdInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
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
    /** A layout written elsewhere (REST API...) while the user was editing; see [DashboardSync]. */
    val external: Dashboard? = null,
) {
    fun pageIndex(t: Target) = if (t == Target.Phone) phonePage else carPage
    fun currentPage(t: Target): Page? = dashboard?.pages(t)?.let { pages -> pages.getOrNull(pageIndex(t).coerceIn(0, (pages.size - 1).coerceAtLeast(0))) }
}

/** What the card picker lists: SGBDs detected in the car first, then every SGBD of the ECU folder. */
data class SgbdChoices(val detected: List<SgbdInfo>, val all: List<SgbdInfo>) {
    val isEmpty: Boolean get() = all.isEmpty()

    companion object {
        /** [detectedNames] that exist in [all], in detection order. */
        fun of(all: List<SgbdInfo>, detectedNames: List<String>): SgbdChoices {
            val byName = all.associateBy { it.name }
            val detected = detectedNames.map { EdiabasMeasurementCatalog.normalize(it) }.distinct().mapNotNull { byName[it] }
            return SgbdChoices(detected, all)
        }

        /**
         * [list] filtered by [query]: every word must appear in the name or the description,
         * ignoring case and accents ("kombi e90", "getriebe", "n52"). Name matches first.
         */
        fun filter(list: List<SgbdInfo>, query: String): List<SgbdInfo> {
            val words = fold(query).split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return list
            return list.mapNotNull { info ->
                val name = fold(info.name)
                val text = name + " " + fold(info.description)
                if (words.all { it in text }) info to (if (name.startsWith(words[0])) 0 else if (words[0] in name) 1 else 2) else null
            }.sortedBy { it.second }.map { it.first }
        }

        private fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace("ß", "ss").replace("_", " ")
    }
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
    private val foreground = MutableStateFlow(false)

    /** The layout this screen last loaded or wrote (see [DashboardSync]). */
    @Volatile private var lastPersisted: Dashboard? = null

    init {
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { store.load() }
            lastPersisted = d
            _ui.update { it.copy(dashboard = d) }
        }
        // Follow layouts written elsewhere (REST API, ...), see DashboardSync.
        viewModelScope.launch {
            DashboardStore.saved.filterNotNull().collect { saved ->
                _ui.update { ui ->
                    val next = DashboardSync.onSaved(ui, saved, lastPersisted)
                    if (next.dashboard === saved) lastPersisted = saved
                    next
                }
            }
        }
        viewModelScope.launch {
            deps.liveDataSource.samples.collect { feed.update(it) }
        }
        // Persist (debounced) after every own change; adopted external layouts are already on disk.
        viewModelScope.launch {
            _ui.map { it.dashboard }.filterNotNull().distinctUntilChanged().debounce(400).filter { it != lastPersisted }.collect { d ->
                lastPersisted = d // before the write: its echo in DashboardStore.saved is then recognized as ours
                withContext(Dispatchers.IO) { store.save(d) }
                // our write is the newest on disk: a pending external layout has been replaced
                if (DashboardStore.saved.value == d) _ui.update { DashboardSync.onOwnSave(it) }
            }
        }
        // Load catalog metadata for every SGBD referenced by the layout.
        viewModelScope.launch {
            _ui.map { it.dashboard?.measurementIds().orEmpty() }.distinctUntilChanged().collect { ids ->
                ids.map { sgbdOf(it) }.distinct().forEach { loadSgbd(it) }
            }
        }
        // Translations changed (language, downloaded file): labels again from the (translating) catalog.
        viewModelScope.launch {
            io.github.jonathangtd.bimmerdata.live.i18n.Translations.shared.version.drop(1).debounce(300).collect {
                val again = loadedSgbds.toList()
                loadedSgbds.clear()
                again.forEach { loadSgbd(it) }
            }
        }
        // Ask the acquisition engine for what is on screen (shared with Android Auto, see MeasurementRequests).
        viewModelScope.launch {
            combine(_ui, _index, foreground) { ui, index, fg ->
                val pages = listOfNotNull(
                    ui.currentPage(Target.Phone),
                    if (ui.editing && ui.target == Target.Car) ui.currentPage(Target.Car) else null,
                )
                pages.flatMap { it.measurementIds() }.distinct().mapNotNull { index[it] } to fg
            }.distinctUntilChanged().collect { (list, fg) -> deps.requestMeasurements(MeasurementClient.Phone, list, fg) }
        }
    }

    /** True while the dashboard is on the phone screen (lifecycle started). */
    fun setForeground(on: Boolean) {
        foreground.value = on
    }

    private fun sgbdOf(id: String) = id.substringBefore('/').lowercase()

    private suspend fun loadSgbd(sgbd: String) {
        if (sgbd.isEmpty() || !loadedSgbds.add(sgbd)) return
        val list = withContext(Dispatchers.IO) { runCatching { deps.catalog.measurements(sgbd) }.getOrDefault(emptyList()) }
        _index.update { it + list.associateBy { m -> m.id } }
    }

    // ---- catalog for the card picker ----

    /** SGBDs for the picker; the first call may scan the ECU folder (about a second, then cached on disk). */
    suspend fun sgbdChoices(): SgbdChoices = withContext(Dispatchers.IO) {
        val all = runCatching { deps.catalog.sgbdInfos() }.getOrDefault(emptyList())
        val detected = runCatching { DashboardHooks.detectedEcus.sgbds() }.getOrDefault(emptyList())
        SgbdChoices.of(all, detected)
    }

    suspend fun measurements(sgbd: String): List<Measurement> {
        val list = withContext(Dispatchers.IO) { runCatching { deps.catalog.measurements(sgbd) }.getOrDefault(emptyList()) }
        loadedSgbds.add(sgbd.lowercase())
        _index.update { it + list.associateBy { m -> m.id } }
        return list
    }

    // ---- modes and navigation ----

    fun setEditing(on: Boolean) = _ui.update { ui ->
        val next = ui.copy(editing = on, target = if (on) ui.target else Target.Phone, undo = null)
        if (on) next else DashboardSync.onEditingDone(next, lastPersisted).also { if (it.dashboard !== next.dashboard) lastPersisted = it.dashboard }
    }

    /** Shows the layout written elsewhere during the edit (the draft goes to undo). */
    fun acceptExternal() = _ui.update { ui ->
        DashboardSync.accept(ui).also { if (it.dashboard !== ui.dashboard) lastPersisted = it.dashboard }
    }
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

    /** Back to the first-launch layout (one empty page each); the dashboard name is kept. */
    fun resetToDefault() {
        val d = DashboardStore.defaultDashboard(getApplication())
        _ui.update { it.copy(dashboard = d.copy(name = it.dashboard?.name), phonePage = 0, carPage = 0, undo = it.dashboard) }
    }

    /** Replaces the pages with the MSV70 (N52 engine) example layout; the name is kept, undo restores. */
    fun applyMsv70Example() {
        val d = DashboardStore.msv70Example(getApplication())
        _ui.update { it.copy(dashboard = d.copy(name = it.dashboard?.name), phonePage = 0, carPage = 0, undo = it.dashboard) }
    }

    /** Header name (blank = the app name). */
    fun renameDashboard(name: String) = _ui.update { ui ->
        val d = ui.dashboard ?: return@update ui
        ui.copy(dashboard = d.copy(name = name.trim().take(Dashboard.MAX_NAME_LENGTH).takeIf { it.isNotEmpty() }))
    }

    // ---- recording ----

    fun toggleRecording() {
        if (deps.recorder.state.value.recording) deps.recorder.stop() else deps.recorder.start()
    }

    fun mark() = deps.recorder.mark()
}
