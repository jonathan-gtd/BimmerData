/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph.ui

import android.graphics.RectF
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.MinTouch
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.ProfileSwitchSheet
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.SegmentedTabs
import io.github.jonathangtd.bimmerdata.dashboard.ui.Track
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeTheme
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.gauges.currentLocale
import io.github.jonathangtd.bimmerdata.gauges.rememberFrameClock
import io.github.jonathangtd.bimmerdata.graph.Graph
import io.github.jonathangtd.bimmerdata.graph.GraphAxes
import io.github.jonathangtd.bimmerdata.graph.GraphFrame
import io.github.jonathangtd.bimmerdata.graph.GraphMath
import io.github.jonathangtd.bimmerdata.graph.GraphRenderer
import io.github.jonathangtd.bimmerdata.graph.GraphSeriesSpec
import io.github.jonathangtd.bimmerdata.graph.GraphTexts
import io.github.jonathangtd.bimmerdata.graph.GraphView
import io.github.jonathangtd.bimmerdata.graph.GraphViewModel
import io.github.jonathangtd.bimmerdata.graph.Graphs
import io.github.jonathangtd.bimmerdata.graph.SharedHistory
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.ui.ConnectionPillContent
import io.github.jonathangtd.bimmerdata.ui.LocalAppNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface GraphSheet {
    data object Profiles : GraphSheet
    data class Edit(val graphId: String) : GraphSheet
    data class Values(val graphId: String) : GraphSheet
    data object Manage : GraphSheet
}

/**
 * The Graph tab: graphs of the active profile, each showing several values of its cards on one
 * time axis, live, with pause, zoom (pinch, or the window button) and scrolling back in time.
 */
@Composable
fun GraphScreen(vm: GraphViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selectedCar by vm.selectedCar.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appSettings by remember { AppSettings.get(context) }.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<GraphSheet?>(null) }
    val newName = stringResource(R.string.graph_new_name, ui.graphs.size + 1)

    LifecycleStartEffect(vm) {
        vm.setForeground(true)
        onStopOrDispose { vm.setForeground(false) }
    }

    fun newGraph() {
        if (ui.graphs.size >= Graphs.MAX_GRAPHS) return
        vm.addGraph(newName, emptyList())
        vm.ui.value.selected?.let { sheet = GraphSheet.Values(it.id) }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val shown = ui.selected
    if (landscape && shown != null && shown.series.isNotEmpty()) {
        // Landscape (only this tab rotates): the graph alone, chart over the whole height.
        val locale = currentLocale()
        val format = remember(locale) { GaugeFormat(locale) }
        val specs = remember(shown, ui.choices, index, format) { vm.specs(shown, ui, index, format) }
        Box(Modifier.fillMaxSize().background(Cockpit.Background).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
            GraphPanel(
                graph = shown, specs = specs, format = format,
                onToggle = { vm.toggleHidden(shown.id, it) }, onWindow = { vm.setWindow(shown.id, it) }, onAxes = { vm.setAxes(shown.id, it) },
                state = vm.panelState(shown), landscape = true, modifier = Modifier.fillMaxSize(),
            )
        }
    } else Column(
        Modifier
            .fillMaxSize()
            .background(Cockpit.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val switchable = ui.profileId != null
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = switchable, role = Role.Button, onClickLabel = stringResource(R.string.ptab_switch_title)) { sheet = GraphSheet.Profiles },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ui.profileName.ifEmpty { stringResource(R.string.nav_graph) }, color = Cockpit.Text, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (switchable) {
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(22.dp))
                }
            }
            val g = ui.selected
            if (g != null) {
                RoundIconButton(R.drawable.ic_pages, stringResource(R.string.graphs), onClick = { sheet = GraphSheet.Manage })
                RoundIconButton(R.drawable.ic_edit, stringResource(R.string.graph_edit), onClick = { sheet = GraphSheet.Edit(g.id) })
            }
        }
        Spacer(Modifier.height(10.dp))
        ConnectionPillContent(connection, status, appSettings.lastMode, vm::connect, vm::disconnect, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))

        val selected = ui.selected
        when {
            !ui.loaded -> Spacer(Modifier.weight(1f))
            ui.profileId == null -> GraphEmpty(
                stringResource(R.string.graph_empty_title), stringResource(R.string.graph_no_profile_hint),
                stringResource(R.string.graph_open_profiles), navigator.openProfiles, Modifier.weight(1f),
            )
            !ui.hasCards && ui.graphs.isEmpty() -> GraphEmpty(
                stringResource(R.string.graph_no_cards_title), stringResource(R.string.graph_no_cards_hint),
                stringResource(R.string.graph_open_dashboard), navigator.openDashboard, Modifier.weight(1f),
            )
            selected == null -> GraphEmpty(
                stringResource(R.string.graph_empty_title), stringResource(R.string.graph_empty_hint),
                stringResource(R.string.graph_add), ::newGraph, Modifier.weight(1f),
            )
            else -> {
                SegmentedTabs(
                    labels = ui.graphs.map { it.name }, selected = ui.selectedIndex, onSelect = { i -> ui.graphs.getOrNull(i)?.let { vm.select(it.id) } },
                    trailing = {
                        RoundIconButton(
                            R.drawable.ic_add, stringResource(R.string.graph_add), onClick = ::newGraph,
                            background = Color.Transparent, tint = Cockpit.Accent, size = 40.dp, enabled = ui.graphs.size < Graphs.MAX_GRAPHS,
                        )
                    },
                )
                Spacer(Modifier.height(12.dp))
                if (selected.series.isEmpty()) {
                    GraphEmpty(
                        selected.name, stringResource(R.string.graph_values_hint, Graphs.MAX_SERIES),
                        stringResource(R.string.graph_choose_values), { sheet = GraphSheet.Values(selected.id) }, Modifier.weight(1f),
                    )
                } else {
                    val locale = currentLocale()
                    val format = remember(locale) { GaugeFormat(locale) }
                    val specs = remember(selected, ui.choices, index, format) { vm.specs(selected, ui, index, format) }
                    GraphPanel(
                        graph = selected, specs = specs, format = format,
                        onToggle = { vm.toggleHidden(selected.id, it) },
                        onWindow = { vm.setWindow(selected.id, it) },
                        onAxes = { vm.setAxes(selected.id, it) },
                        state = vm.panelState(selected),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    when (val s = sheet) {
        null -> Unit
        GraphSheet.Profiles -> ProfileSwitchSheet(
            set = profiles, car = selectedCar?.label, onDismiss = { sheet = null },
            onChangeCar = {
                sheet = null
                navigator.openCars()
            },
            onActivate = { id ->
                sheet = null
                scope.launch { vm.activateProfile(id) }
            },
            onNew = {
                sheet = null
                navigator.openProfiles()
            },
            onManage = {
                sheet = null
                navigator.openProfiles()
            },
        )
        is GraphSheet.Edit -> {
            val g = ui.graphs.firstOrNull { it.id == s.graphId }
            if (g == null) {
                sheet = null
            } else {
                val locale = currentLocale()
                    val format = remember(locale) { GaugeFormat(locale) }
                GraphEditorSheet(
                    graph = g, specs = vm.specs(g, ui, index, format), choices = ui.choices,
                    onDismiss = { sheet = null },
                    onRename = { vm.rename(g.id, it) },
                    onChooseValues = { sheet = GraphSheet.Values(g.id) },
                    onColor = { id, c -> vm.setColor(g.id, id, c) },
                    onRemove = { id -> vm.setSeries(g.id, g.measurementIds() - id) },
                    onWindow = { vm.setWindow(g.id, it) },
                    onAxes = { vm.setAxes(g.id, it) },
                    onDelete = {
                        vm.delete(g.id)
                        sheet = null
                    },
                )
            }
        }
        is GraphSheet.Values -> {
            val g = ui.graphs.firstOrNull { it.id == s.graphId }
            if (g == null) {
                sheet = null
            } else {
                ValuesSheet(
                    graph = g, choices = ui.choices, label = { vm.label(it, ui, index) }, unit = { index[it]?.unit.orEmpty() },
                    onDismiss = { sheet = null },
                    onConfirm = { ids ->
                        vm.setSeries(g.id, ids)
                        sheet = null
                    },
                )
            }
        }
        GraphSheet.Manage -> GraphsSheet(
            graphs = ui.graphs, onDismiss = { sheet = null },
            onRename = vm::rename, onMove = vm::move, onDelete = vm::delete,
            onAdd = {
                sheet = null
                newGraph()
            },
            canAdd = ui.graphs.size < Graphs.MAX_GRAPHS,
        )
    }
}

/** Big centered message with one action (no profile, no card, no graph, no value). */
@Composable
private fun GraphEmpty(title: String, hint: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).border(1.5.dp, Cockpit.Accent.copy(alpha = 0.35f), CircleShape).padding(10.dp)
                    .clip(CircleShape).background(Cockpit.Accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_graph), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(title, color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(hint, color = Cockpit.Muted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            PillButton(action, onClick = onAction, icon = R.drawable.ic_add, color = Cockpit.OnAccent, background = Cockpit.Accent)
        }
    }
}

/**
 * What the user did to a graph on screen: time view (window, pause, scroll) and cursor. Kept by the
 * ViewModel per graph, so rotating the phone (portrait / landscape layouts) keeps a paused view.
 */
class GraphPanelState(initial: GraphView) {
    var view by mutableStateOf(initial)
    var cursorMs by mutableStateOf<Long?>(null)
}

/**
 * The chart with its gestures, the legend and the controls (live / pause, window, axes). Portrait:
 * stacked; [landscape]: the chart takes the height, legend and controls in a side column.
 */
@Composable
internal fun GraphPanel(
    graph: Graph, specs: List<GraphSeriesSpec>, format: GaugeFormat,
    onToggle: (String) -> Unit, onWindow: (Int) -> Unit, onAxes: (GraphAxes) -> Unit,
    modifier: Modifier = Modifier,
    state: GraphPanelState = remember(graph.id) { GraphPanelState(GraphView.of(graph.windowSeconds)) },
    landscape: Boolean = false,
    /** Wall clock (the time of the readings); replaced by tests. */
    clock: () -> Long = System::currentTimeMillis,
) {
    // the stored window changed (editor, another screen): adopt it
    LaunchedEffect(graph.windowSeconds) {
        if (state.view.seconds != TrendWindow.seconds(graph.windowSeconds)) state.view = state.view.copy(spanMs = TrendWindow.ms(graph.windowSeconds))
    }
    if (landscape) {
        Row(modifier.fillMaxSize()) {
            GraphCanvas(graph, specs, format, state, onWindow, clock, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(232.dp).fillMaxHeight()) {
                Text(
                    graph.name, color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Legend(specs, state.cursorMs, format, onToggle, columns = 1)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayButton(state, clock, Modifier.weight(1f))
                    WindowButton(state, onWindow)
                }
                Spacer(Modifier.height(8.dp))
                AxesToggle(graph.axes, onAxes)
            }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            GraphCanvas(graph, specs, format, state, onWindow, clock, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(10.dp))
            Legend(specs, state.cursorMs, format, onToggle)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(state, clock)
                WindowButton(state, onWindow)
                Spacer(Modifier.weight(1f))
                AxesToggle(graph.axes, onAxes)
            }
            Text(
                stringResource(if (state.view.live) R.string.graph_live_hint else R.string.graph_paused_hint),
                color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PlayButton(state: GraphPanelState, clock: () -> Long, modifier: Modifier = Modifier) {
    if (state.view.live) {
        PillButton(stringResource(R.string.graph_pause), onClick = { state.view = state.view.pause(clock()) }, icon = R.drawable.ic_pause, modifier = modifier)
    } else {
        PillButton(
            stringResource(R.string.graph_live), onClick = { state.view = state.view.resume(); state.cursorMs = null }, icon = R.drawable.ic_play,
            color = Cockpit.OnAccent, background = Cockpit.Accent, modifier = modifier,
        )
    }
}

@Composable
private fun WindowButton(state: GraphPanelState, onWindow: (Int) -> Unit) {
    PillButton(
        TrendWindow.duration(state.view.seconds),
        onClick = {
            val next = TrendWindow.next(state.view.seconds)
            state.view = state.view.copy(spanMs = next * 1000L)
            onWindow(next)
        },
    )
}

/** The plot: drawn every frame from [SharedHistory]; pinch, scroll, long-press cursor, double tap. */
@Composable
private fun GraphCanvas(
    graph: Graph, specs: List<GraphSeriesSpec>, format: GaugeFormat, state: GraphPanelState,
    onWindow: (Int) -> Unit, clock: () -> Long, modifier: Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val noData = stringResource(R.string.graph_no_data)
    val res = LocalResources.current
    val renderer = remember(density, format, noData, res) {
        GraphRenderer(GaugeTheme.phone(context, density), format, GraphTexts(noData) { n -> res.getQuantityString(R.plurals.graph_more_axes, n, n) })
    }
    val frameClock = rememberFrameClock()
    val lastTap = remember { mutableLongStateOf(0L) }
    val ids = specs.map { it.id }
    val rect = remember { RectF() }
    val description = stringResource(R.string.graph_cursor_desc, specs.joinToString(", ") { it.label })
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(graph.id, state) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = 0 // 0 undecided, 1 scroll, 2 pinch, 3 cursor
                    var moved = Offset.Zero
                    val startSeconds = state.view.seconds
                    val longPressAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                    var lastUptime = down.uptimeMillis
                    while (true) {
                        val event = if (mode == 0) {
                            withTimeoutOrNull((longPressAt - lastUptime).coerceAtLeast(1L)) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        event?.changes?.firstOrNull()?.let { lastUptime = it.uptimeMillis }
                        val now = clock()
                        if (event == null) {
                            // long press: pause and show the values under the finger
                            mode = 3
                            state.view = state.view.pause(now)
                            state.cursorMs = renderer.timeAt(down.position.x, state.view, now)
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (mode == 0) {
                                // tap: hide the cursor; double tap: next window
                                if (now - lastTap.longValue < viewConfiguration.doubleTapTimeoutMillis) {
                                    val next = TrendWindow.next(state.view.seconds)
                                    state.view = state.view.copy(spanMs = next * 1000L)
                                    onWindow(next)
                                    lastTap.longValue = 0L
                                } else {
                                    state.cursorMs = null
                                    lastTap.longValue = now
                                }
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            mode = 2
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            state.view = state.view.zoom(zoom, renderer.fractionAt(centroid.x), now)
                            if (!state.view.live) state.view = state.view.pan(event.calculatePan().x / renderer.plotWidth, now, SharedHistory.oldestMs(ids))
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = pressed.first()
                        val delta = change.positionChange()
                        if (mode == 0) {
                            moved += delta
                            if (moved.getDistance() > slop) mode = 1
                        }
                        when (mode) {
                            1 -> state.view = state.view.pan(delta.x / renderer.plotWidth, now, SharedHistory.oldestMs(ids))
                            3 -> state.cursorMs = renderer.timeAt(change.position.x, state.view, now)
                        }
                        if (mode != 0) event.changes.forEach { it.consume() }
                    }
                    if (state.view.seconds != startSeconds) onWindow(state.view.seconds)
                }
            },
    ) {
        frameClock.value
        rect.set(0f, 0f, size.width, size.height)
        val frame = GraphFrame(specs, graph.axes, state.view, clock(), state.cursorMs)
        drawIntoCanvas { renderer.draw(it.nativeCanvas, rect, frame, SharedHistory::history) }
    }
}

@Composable
internal fun AxesToggle(axes: GraphAxes, onAxes: (GraphAxes) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Cockpit.Card).padding(3.dp)) {
        for ((mode, label) in listOf(GraphAxes.Overlay to R.string.graph_overlay, GraphAxes.Bands to R.string.graph_bands)) {
            val sel = axes == mode
            Box(
                Modifier
                    .heightIn(min = 42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Track else Color.Transparent)
                    .clickable(role = Role.Tab) { onAxes(mode) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label), color = if (sel) Cockpit.Text else Cockpit.Muted, fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                )
            }
        }
    }
}

/** One entry per value: color, name, value now (or under the cursor); tap hides / shows it. */
@Composable
private fun Legend(specs: List<GraphSeriesSpec>, cursorMs: Long?, format: GaugeFormat, onToggle: (String) -> Unit, columns: Int = 2) {
    // live values refresh a few times a second (the curves are redrawn every frame)
    val tick by produceState(0L) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    val hiddenText = stringResource(R.string.graph_hidden)
    // Compact: [columns] entries per line, one short line each (dot, name, value).
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Cockpit.Card).padding(horizontal = 4.dp, vertical = 2.dp)) {
        for (line in specs.chunked(columns)) {
            Row(Modifier.fillMaxWidth()) {
                for (s in line) {
                    val value = remember(tick, cursorMs, s, format) { legendValue(s, cursorMs, format) }
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Switch, onClickLabel = s.label) { onToggle(s.id) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(if (s.hidden) Color.Transparent else Color(s.color))
                                .border(1.5.dp, Color(s.color), CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            s.label, color = Cockpit.Muted, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (s.hidden) hiddenText else value, color = if (s.hidden) Cockpit.Muted else Cockpit.Text,
                            fontSize = 14.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        )
                    }
                }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun legendValue(s: GraphSeriesSpec, cursorMs: Long?, format: GaugeFormat): String {
    val h = SharedHistory.history(s.id) ?: return GaugeFormat.NO_DATA
    val text = if (cursorMs != null) {
        val out = FloatArray(2)
        if (!h.valueNear(cursorMs, GraphMath.GAP_MS, out)) return GaugeFormat.NO_DATA
        if (out[0] == out[1]) format.format(out[0].toDouble(), s.decimals)
        else format.format(out[0].toDouble(), s.decimals) + "…" + format.format(out[1].toDouble(), s.decimals)
    } else {
        if (h.isEmpty) return GaugeFormat.NO_DATA
        format.format(h.valueAt(h.size - 1).toDouble(), s.decimals)
    }
    return format.withUnit(text, s.unit)
}
