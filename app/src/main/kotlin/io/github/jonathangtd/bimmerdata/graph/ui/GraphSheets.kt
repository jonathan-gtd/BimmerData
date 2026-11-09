/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.CockpitSheet
import io.github.jonathangtd.bimmerdata.dashboard.ui.ConfirmDialog
import io.github.jonathangtd.bimmerdata.dashboard.ui.MinTouch
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.SectionTitle
import io.github.jonathangtd.bimmerdata.dashboard.ui.SheetHeader
import io.github.jonathangtd.bimmerdata.dashboard.ui.TrendWindowSection
import io.github.jonathangtd.bimmerdata.dashboard.ui.fieldColors
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.graph.Graph
import io.github.jonathangtd.bimmerdata.graph.GraphAxes
import io.github.jonathangtd.bimmerdata.graph.GraphChoice
import io.github.jonathangtd.bimmerdata.graph.GraphPalette
import io.github.jonathangtd.bimmerdata.graph.GraphSeriesSpec
import io.github.jonathangtd.bimmerdata.graph.Graphs
import io.github.jonathangtd.bimmerdata.ui.Cockpit

/** Name, values (color, remove), axes, time window and deletion of one graph. */
@Composable
internal fun GraphEditorSheet(
    graph: Graph, specs: List<GraphSeriesSpec>, choices: List<GraphChoice>,
    onDismiss: () -> Unit, onRename: (String) -> Unit, onChooseValues: () -> Unit,
    onColor: (measurementId: String, color: Int) -> Unit, onRemove: (String) -> Unit,
    onWindow: (Int?) -> Unit, onAxes: (GraphAxes) -> Unit, onDelete: () -> Unit,
) {
    var name by remember(graph.id) { mutableStateOf(graph.name) }
    var colorOpen by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    fun close() {
        if (name.trim() != graph.name && name.isNotBlank()) onRename(name)
        onDismiss()
    }
    CockpitSheet(::close) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            SheetHeader(stringResource(R.string.graph_edit), trailing = {
                RoundIconButton(R.drawable.ic_check, stringResource(R.string.done), onClick = ::close, tint = Cockpit.Accent, background = Color.Transparent)
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(Graphs.MAX_NAME_LENGTH) }, singleLine = true,
                    label = { Text(stringResource(R.string.graph_name)) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                SectionTitle(stringResource(R.string.graph_values), Modifier.padding(top = 12.dp))
                if (specs.isEmpty()) Text(stringResource(R.string.graph_values_none), color = Cockpit.Muted, fontSize = 14.sp)
                for (s in specs) {
                    val series = graph.series.firstOrNull { it.measurementId == s.id } ?: continue
                    val place = choices.firstOrNull { it.measurementId == s.id }?.place
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                        val colorLabel = stringResource(R.string.graph_color, s.label)
                        Box(
                            Modifier.size(MinTouch).clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = colorLabel) { colorOpen = if (colorOpen == s.id) null else s.id }
                                .semantics { contentDescription = colorLabel },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(s.color)))
                        }
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(s.label, color = Cockpit.Text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(s.unit.takeIf { it.isNotEmpty() }, place ?: stringResource(R.string.graph_not_on_card)).joinToString("  ·  "),
                                color = if (place == null) Cockpit.Warning else Cockpit.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        RoundIconButton(
                            R.drawable.ic_close, stringResource(R.string.graph_remove_value, s.label), onClick = { onRemove(s.id) },
                            background = Color.Transparent, tint = Cockpit.Muted,
                        )
                    }
                    if (colorOpen == s.id) {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in 0 until GraphPalette.size) {
                                val sel = series.color == c
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape)
                                        .border(2.dp, if (sel) Cockpit.Text else Color.Transparent, CircleShape)
                                        .padding(5.dp).clip(CircleShape).background(Color(GraphPalette.color(c)))
                                        .clickable(role = Role.RadioButton) { onColor(s.id, c); colorOpen = null }
                                        .semantics { selected = sel },
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Cockpit.Border)
                }
                PillButton(
                    stringResource(R.string.graph_choose_values), onClick = onChooseValues, icon = R.drawable.ic_add, color = Cockpit.Accent,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                Text(stringResource(R.string.graph_legend_hint), color = Cockpit.Muted, fontSize = 12.sp)
                SectionTitle(stringResource(R.string.graph_axes_mode), Modifier.padding(top = 12.dp))
                AxesToggle(graph.axes, onAxes)
                Spacer(Modifier.height(12.dp))
                TrendWindowSection(
                    graph.windowSeconds, onChange = { v -> if (v == null || TrendWindow.isValid(v)) onWindow(v) },
                    hint = stringResource(R.string.graph_window_hint),
                )
                PillButton(
                    stringResource(R.string.graph_delete), onClick = { deleting = true }, icon = R.drawable.ic_delete, color = Cockpit.Danger,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
        }
    }
    if (deleting) {
        ConfirmDialog(
            text = stringResource(R.string.graph_delete_confirm, graph.name), confirm = stringResource(R.string.delete),
            onConfirm = { deleting = false; onDelete() }, onDismiss = { deleting = false },
        )
    }
}

/**
 * Picks the values of a graph among those of the profile's cards, grouped by page, at most
 * [Graphs.MAX_SERIES]. Values of the graph no longer on any card are listed first, so they can be
 * removed.
 */
@Composable
internal fun ValuesSheet(
    graph: Graph, choices: List<GraphChoice>, label: (String) -> String, unit: (String) -> String,
    onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit,
) {
    val selected = remember(graph.id) { mutableStateListOf<String>().apply { addAll(graph.measurementIds()) } }
    val orphan = graph.measurementIds().filter { id -> choices.none { it.measurementId == id } }
    val notOnCard = stringResource(R.string.graph_not_on_card)
    val rows: List<Pair<String, String>> = orphan.map { it to notOnCard } + choices.map { it.measurementId to it.place }
    CockpitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            SheetHeader(stringResource(R.string.graph_choose_values))
            Text(
                stringResource(R.string.graph_values_hint, Graphs.MAX_SERIES) + "  " + stringResource(R.string.selected_count, selected.size, Graphs.MAX_SERIES),
                color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                itemsIndexed(rows, key = { _, r -> r.first }) { i, (id, place) ->
                    if (i == 0 || rows[i - 1].second != place) SectionTitle(place, Modifier.padding(top = 8.dp))
                    val isSel = id in selected
                    val full = !isSel && selected.size >= Graphs.MAX_SERIES
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) Cockpit.Accent.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable(enabled = !full, role = Role.Checkbox) { if (isSel) selected.remove(id) else selected.add(id) }
                            .semantics { this.selected = isSel }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label(id), color = if (full) Cockpit.Muted else Cockpit.Text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(unit(id).takeIf { it.isNotBlank() && it != "-" }, id.substringBefore('/').uppercase()).joinToString("  ·  "),
                                color = Cockpit.Muted, fontSize = 12.sp, maxLines = 1,
                            )
                        }
                        if (isSel) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            PillButton(
                stringResource(R.string.save) + " (${selected.size})", onClick = { onConfirm(selected.toList()) },
                color = Cockpit.OnAccent, background = Cockpit.Accent, modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

/** The graphs of the profile: rename, reorder, delete, add (like the pages of the dashboard). */
@Composable
internal fun GraphsSheet(
    graphs: List<Graph>, onDismiss: () -> Unit, onRename: (String, String) -> Unit, onMove: (Int, Int) -> Unit,
    onDelete: (String) -> Unit, onAdd: () -> Unit, canAdd: Boolean,
) {
    var renaming by remember { mutableStateOf<Graph?>(null) }
    var deleting by remember { mutableStateOf<Graph?>(null) }
    CockpitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f)) {
            SheetHeader(stringResource(R.string.graphs))
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                itemsIndexed(graphs, key = { _, g -> g.id }) { i, g ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).heightIn(min = MinTouch).clip(RoundedCornerShape(10.dp)).clickable { renaming = g }.padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(g.name, color = Cockpit.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                for (s in g.series) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(GraphPalette.color(s.color))))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    TrendWindow.duration(TrendWindow.seconds(g.windowSeconds)), color = Cockpit.Muted, fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                        }
                        RoundIconButton(R.drawable.ic_chevron_up, stringResource(R.string.move_up), onClick = { onMove(i, i - 1) }, enabled = i > 0, background = Color.Transparent)
                        RoundIconButton(R.drawable.ic_chevron_down, stringResource(R.string.move_down), onClick = { onMove(i, i + 1) }, enabled = i < graphs.lastIndex, background = Color.Transparent)
                        RoundIconButton(R.drawable.ic_delete, stringResource(R.string.delete), onClick = { deleting = g }, tint = Cockpit.Danger, background = Color.Transparent)
                    }
                    HorizontalDivider(color = Cockpit.Border)
                }
            }
            PillButton(
                stringResource(R.string.graph_add), onClick = onAdd, icon = R.drawable.ic_add, color = Cockpit.Accent, enabled = canAdd,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
    renaming?.let { g ->
        var name by remember(g.id) { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, containerColor = Cockpit.Card,
            title = { Text(stringResource(R.string.graph_name), color = Cockpit.Text) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(Graphs.MAX_NAME_LENGTH) }, singleLine = true, colors = fieldColors())
            },
            confirmButton = {
                TextButton(onClick = { onRename(g.id, name); renaming = null }, modifier = Modifier.heightIn(min = MinTouch)) {
                    Text(stringResource(R.string.save), color = Cockpit.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.cancel), color = Cockpit.Muted) }
            },
        )
    }
    deleting?.let { g ->
        ConfirmDialog(
            text = stringResource(R.string.graph_delete_confirm, g.name), confirm = stringResource(R.string.delete),
            onConfirm = { onDelete(g.id); deleting = null }, onDismiss = { deleting = null },
        )
    }
}
