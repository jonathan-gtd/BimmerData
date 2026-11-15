/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.events.ui

import android.content.Intent
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.fieldColors
import io.github.jonathangtd.bimmerdata.events.DriveEvent
import io.github.jonathangtd.bimmerdata.events.EventRuntime
import io.github.jonathangtd.bimmerdata.events.EventSnapshot
import io.github.jonathangtd.bimmerdata.events.EventSnapshots
import io.github.jonathangtd.bimmerdata.events.EventSource
import io.github.jonathangtd.bimmerdata.events.Events
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeTheme
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import io.github.jonathangtd.bimmerdata.gauges.currentLocale
import io.github.jonathangtd.bimmerdata.graph.GraphAxes
import io.github.jonathangtd.bimmerdata.graph.GraphFrame
import io.github.jonathangtd.bimmerdata.graph.GraphPalette
import io.github.jonathangtd.bimmerdata.graph.GraphRenderer
import io.github.jonathangtd.bimmerdata.graph.GraphSeriesSpec
import io.github.jonathangtd.bimmerdata.graph.GraphTexts
import io.github.jonathangtd.bimmerdata.graph.GraphView
import io.github.jonathangtd.bimmerdata.graph.ui.AxesToggle
import io.github.jonathangtd.bimmerdata.live.CsvFiles
import io.github.jonathangtd.bimmerdata.live.RecordingStorage
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The driver events (« Événement » button on the phone, Android Auto, the notification or the
 * REST API): newest first; one opens on the values read around it (a graph of the snapshot,
 * the event at the dashed line), its note, its place, CSV export and delete. Opened from the
 * Graph tab.
 */
@Composable
fun EventsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { EventRuntime.store(context) }
    val events by store.state.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openId != null) { openId = null }
    val open = openId?.let { id -> events.firstOrNull { it.id == id } }
    Box(Modifier.fillMaxSize().background(Cockpit.Background)) {
        if (open != null) EventDetail(open, onBack = { openId = null })
        else EventList(events, onBack = onBack, onOpen = { openId = it })
    }
}

private fun zone(): ZoneId = ZoneId.systemDefault()

private fun dateTime(ms: Long, locale: Locale): String =
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm:ss", locale).withZone(zone()).format(Instant.ofEpochMilli(ms))

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(R.drawable.ic_chevron_left, stringResource(R.string.back), onClick = onBack, background = Color.Transparent)
        Text(
            title, color = Cockpit.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = BarlowCondensed,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun EventList(events: List<DriveEvent>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val locale = currentLocale()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Header(stringResource(R.string.events_title), onBack)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(stringResource(R.string.events_intro), color = Cockpit.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (events.isEmpty()) item {
                Text(stringResource(R.string.events_empty), color = Cockpit.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 24.dp))
            }
            items(events, key = { it.id }) { e ->
                Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpen(e.id) }) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(dateTime(e.timeMs, locale), color = Cockpit.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        val where = listOfNotNull(e.profileName, e.carLabel, e.speedKmh?.let { stringResource(R.string.event_speed, it.toInt()) }).joinToString(" · ")
                        if (where.isNotEmpty()) Text(where, color = Cockpit.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            e.note.ifBlank { stringResource(R.string.event_no_note) },
                            color = if (e.note.isBlank()) Cockpit.Muted else Cockpit.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventDetail(event: DriveEvent, onBack: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val locale = currentLocale()
    val store = remember { EventRuntime.store(context) }
    val scope = rememberCoroutineScope()
    var snapshot by remember(event.id) { mutableStateOf<EventSnapshot?>(null) }
    LaunchedEffect(event.id, event.snapshotToMs, event.snapshotComplete) {
        snapshot = withContext(Dispatchers.IO) { store.snapshot(event.id) }
    }
    var editNote by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Header(dateTime(event.timeMs, locale), onBack)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                val source = stringResource(
                    when (event.source) {
                        EventSource.Phone -> R.string.event_source_phone
                        EventSource.Car -> R.string.event_source_car
                        EventSource.Notification -> R.string.event_source_notification
                        EventSource.Api -> R.string.event_source_api
                    },
                )
                val line = listOfNotNull(event.profileName, event.carLabel, source).joinToString(" · ")
                Text(line, color = Cockpit.Muted, fontSize = 14.sp)
                if (event.latitude != null && event.longitude != null) {
                    val place = String.format(Locale.ROOT, "%.5f, %.5f", event.latitude, event.longitude) +
                        (event.speedKmh?.let { " · " + res.getString(R.string.event_speed, it.toInt()) } ?: "")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(place, color = Cockpit.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val uri = "geo:${event.latitude},${event.longitude}?q=${event.latitude},${event.longitude}".toUri()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }) { Text(stringResource(R.string.event_map), color = Cockpit.Accent) }
                    }
                }
            }
            item {
                Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { editNote = true }) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.event_note), color = Cockpit.Muted, fontSize = 12.sp)
                        Text(
                            event.note.ifBlank { stringResource(R.string.event_add_note) },
                            color = if (event.note.isBlank()) Cockpit.Accent else Cockpit.Text, fontSize = 15.sp,
                        )
                    }
                }
            }
            item {
                val s = snapshot
                when {
                    s == null || s.isEmpty -> Text(
                        stringResource(if (event.snapshotComplete) R.string.event_no_values else R.string.event_capturing),
                        color = Cockpit.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp),
                    )
                    else -> {
                        if (!event.snapshotComplete) Text(stringResource(R.string.event_capturing), color = Cockpit.Warning, fontSize = 13.sp)
                        SnapshotGraph(s)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        stringResource(R.string.event_export_csv), icon = R.drawable.ic_share, enabled = snapshot?.isEmpty == false,
                        onClick = {
                            val s = snapshot ?: return@PillButton
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    var published: android.net.Uri? = null
                                    runCatching {
                                        RecordingStorage.create(context, EventSnapshots.csvName(event)) { published = it }.use { out ->
                                            out.write(EventSnapshots.csv(event, s).toByteArray(Charsets.UTF_8))
                                        }
                                    }
                                    published
                                }
                                if (uri != null) runCatching { context.startActivity(CsvFiles.shareIntent(uri, res.getString(R.string.event_export_csv))) }
                            }
                        },
                    )
                    PillButton(stringResource(R.string.delete), icon = R.drawable.ic_delete, color = Cockpit.Danger, onClick = { confirmDelete = true })
                }
            }
        }
    }
    if (editNote) EventNoteDialog(event.id, onDismiss = { editNote = false })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.event_delete_title)) },
        text = { Text(stringResource(R.string.event_delete_text)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    withContext(Dispatchers.IO) { store.delete(event.id) }
                    onBack()
                }
            }) { Text(stringResource(R.string.delete), color = Cockpit.Danger) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        containerColor = Cockpit.Card,
    )
}

/** At most this many curves shown at first (the others one tap away in the legend). */
private const val FIRST_SHOWN = 4

/**
 * The snapshot drawn like the Graph tab ([GraphRenderer]): the whole window, the event at the
 * dashed line; a tap puts the cursor (values in the legend), the legend shows / hides a value.
 */
@Composable
private fun SnapshotGraph(s: EventSnapshot) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val locale = currentLocale()
    val format = remember(locale) { GaugeFormat(locale) }
    val noData = stringResource(R.string.graph_no_data)
    val res = LocalResources.current
    val renderer = remember(density, format, noData, res) {
        GraphRenderer(GaugeTheme.phone(context, density), format, GraphTexts(noData) { n -> res.getQuantityString(R.plurals.graph_more_axes, n, n) })
    }
    val histories = remember(s) {
        s.series.associate { ser ->
            ser.id to ValueHistory(capacity = (ser.t.size + 16).coerceAtLeast(64), retainMs = (s.toMs - s.fromMs) + 60_000L).also { h ->
                for (k in ser.t.indices) h.add(s.eventTimeMs + ser.t[k], ser.v[k])
            }
        }
    }
    var hidden by remember(s.eventTimeMs) { mutableStateOf(s.series.drop(FIRST_SHOWN).map { it.id }.toSet()) }
    var axes by remember { mutableStateOf(GraphAxes.Bands) }
    var cursor by remember(s.eventTimeMs) { mutableStateOf<Long?>(null) }
    val specs = s.series.mapIndexed { i, ser ->
        GraphSeriesSpec(
            id = ser.id, label = ser.label, unit = format.displayUnit(ser.unit, ser.label),
            decimals = GaugeFormat.defaultDecimals(ser.unit, ser.label), color = GraphPalette.color(i),
            minSpan = GraphRenderer.minSpan(null, null, ser.unit), hidden = ser.id in hidden,
        )
    }
    val view = GraphView(spanMs = (s.toMs - s.fromMs).coerceAtLeast(1_000L), endMs = s.toMs)
    val now = s.toMs + 1
    val rect = remember { RectF() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .semantics { contentDescription = res.getString(R.string.event_graph_desc) }
                .pointerInput(s.eventTimeMs) {
                    detectTapGestures { p -> cursor = if (cursor == null) renderer.timeAt(p.x, view, now) else null }
                },
        ) {
            rect.set(0f, 0f, size.width, size.height)
            val frame = GraphFrame(specs, axes, view, now, cursor, listOf(s.eventTimeMs))
            drawIntoCanvas { renderer.draw(it.nativeCanvas, rect, frame) { id -> histories[id] } }
        }
        AxesToggle(axes) { axes = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val near = remember { FloatArray(2) }
            specs.forEach { spec ->
                val value = cursor?.let { t ->
                    val h = histories[spec.id]
                    if (h != null && h.valueNear(t, 2_000L, near)) format.format(near[0].toDouble(), spec.decimals) + (if (spec.unit.isBlank()) "" else " ${spec.unit}") else null
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Cockpit.Card)
                        .clickable { hidden = if (spec.id in hidden) hidden - spec.id else hidden + spec.id }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(if (spec.hidden) Cockpit.Border else Color(spec.color), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (value != null) "${spec.label}: $value" else spec.label,
                        color = if (spec.hidden) Cockpit.Muted else Cockpit.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(stringResource(R.string.event_graph_hint), color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** Edits the note of event [id]. */
@Composable
fun EventNoteDialog(id: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { EventRuntime.store(context) }
    val scope = rememberCoroutineScope()
    var text by remember(id) { mutableStateOf(store.get(id)?.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_note)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(Events.MAX_NOTE) },
                placeholder = { Text(stringResource(R.string.event_note_hint)) }, colors = fieldColors(), singleLine = false, maxLines = 4,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val note = text
                scope.launch {
                    withContext(Dispatchers.IO) { store.setNote(id, note) }
                    onDismiss()
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = Cockpit.Card,
    )
}
