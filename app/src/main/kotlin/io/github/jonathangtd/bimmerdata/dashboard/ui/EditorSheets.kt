/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.CardSpecs
import io.github.jonathangtd.bimmerdata.dashboard.DashboardViewModel
import io.github.jonathangtd.bimmerdata.dashboard.GridLimits
import io.github.jonathangtd.bimmerdata.dashboard.GridRules
import io.github.jonathangtd.bimmerdata.dashboard.LayoutPreset
import io.github.jonathangtd.bimmerdata.dashboard.LayoutPresets
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.dashboard.SgbdChoices
import io.github.jonathangtd.bimmerdata.dashboard.Target
import io.github.jonathangtd.bimmerdata.dashboard.car.CarPageRenderer
import io.github.jonathangtd.bimmerdata.dashboard.car.CarScreen
import io.github.jonathangtd.bimmerdata.gauges.GaugeCard
import io.github.jonathangtd.bimmerdata.gauges.GaugeChannel
import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.GaugeRenderer
import io.github.jonathangtd.bimmerdata.gauges.GaugeSpec
import io.github.jonathangtd.bimmerdata.gauges.GaugeState
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import io.github.jonathangtd.bimmerdata.gauges.currentLocale
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.live.SgbdInfo
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlin.math.sin

// ------------------------------------------------------------------ shared bits

@Composable
internal fun typeName(type: GaugeType): String = stringResource(
    when (type) {
        GaugeType.Dial -> R.string.type_dial
        GaugeType.BipolarBar -> R.string.type_bipolar
        GaugeType.BigNumber -> R.string.type_big
        GaugeType.Trend -> R.string.type_trend
        GaugeType.MultiValue -> R.string.type_multi
        GaugeType.Status -> R.string.type_status
    },
)

@Composable
private fun typeDescription(type: GaugeType): String = stringResource(
    when (type) {
        GaugeType.Dial -> R.string.type_dial_desc
        GaugeType.BipolarBar -> R.string.type_bipolar_desc
        GaugeType.BigNumber -> R.string.type_big_desc
        GaugeType.Trend -> R.string.type_trend_desc
        GaugeType.MultiValue -> R.string.type_multi_desc
        GaugeType.Status -> R.string.type_status_desc
    },
)

@Composable
private fun presetName(preset: LayoutPreset): String = stringResource(
    when (preset.id) {
        "phone_mockup" -> R.string.preset_phone_mockup
        "grid_2x2" -> R.string.preset_grid_2x2
        "big_left" -> R.string.preset_big_left
        "big_top" -> R.string.preset_big_top
        "grid_2x4" -> R.string.preset_grid_2x4
        "grid_3x4" -> R.string.preset_grid_3x4
        "stack_3" -> R.string.preset_stack_3
        "car_four" -> R.string.preset_car_four
        "three_columns" -> R.string.preset_three_columns
        else -> R.string.preset_hero
    },
)

/** Default size and position of a new card. */
internal fun newCard(type: GaugeType, ids: List<String>, target: Target, page: Page, col: Int?, row: Int?): Card {
    val (cs, rs) = if (target == Target.Car) {
        1 to page.rows
    } else {
        when (type) {
            GaugeType.Dial, GaugeType.BigNumber -> 1 to 2
            GaugeType.BipolarBar -> 2 to 2
            else -> 1 to 1
        }
    }
    return Card(
        type = type, measurementIds = ids, col = col ?: 0, row = row ?: 0,
        colSpan = cs.coerceAtMost(page.columns), rowSpan = rs.coerceAtMost(page.rows),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CockpitSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = Cockpit.Card, contentColor = Cockpit.Text,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Box(Modifier.navigationBarsPadding().imePadding()) { content() }
    }
}

@Composable
private fun SheetHeader(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            RoundIconButton(R.drawable.ic_chevron_left, stringResource(R.string.back), onClick = onBack, background = Color.Transparent)
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title, color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp,
            modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@Composable
internal fun ConfirmDialog(text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Cockpit.Card,
        text = { Text(text, color = Cockpit.Text, fontSize = 16.sp) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MinTouch)) { Text(confirm, color = Cockpit.Warning) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.cancel), color = Cockpit.Accent) } },
    )
}

/** One-line text dialog (dashboard name); an empty text is allowed (= [placeholder] is shown instead). */
@Composable
internal fun TextInputDialog(
    title: String, initial: String, placeholder: String, maxLength: Int, hint: String?,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Cockpit.Card,
        title = { Text(title, color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(maxLength) }, singleLine = true,
                    placeholder = { Text(placeholder, color = Cockpit.Muted) },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                )
                if (hint != null) Text(hint, color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.save), color = Cockpit.Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.cancel), color = Cockpit.Muted) }
        },
    )
}

// ------------------------------------------------------------------ demo data for type previews

private class DemoFeed : GaugeFeed {
    override val nowMs: Long = System.currentTimeMillis()
    private val values = mapOf("d/oil" to 104.0, "d/b1" to 2.3, "d/b2" to -1.6, "d/rpm" to 2350.0, "d/l1" to 1.0, "d/l2" to 1.01, "d/cool" to 92.0)
    private val rpmHistory = ValueHistory().apply {
        for (i in 0..90) add(nowMs - 45_000 + i * 500L, 2350 + 380 * sin(i / 6.0) + 130 * sin(i / 2.2))
    }

    override fun sample(id: String): Sample? = when (id) {
        "d/status" -> Sample(null, "OKAY", nowMs)
        else -> values[id]?.let { Sample(it, null, nowMs) }
    }

    override fun history(id: String): ValueHistory? = if (id == "d/rpm") rpmHistory else null
}

private fun demoSpec(type: GaugeType, title: String, rpmUnit: String): GaugeSpec = when (type) {
    GaugeType.Dial -> GaugeSpec(type, title, null, 40.0, 140.0, 80.0, null, listOf(GaugeChannel("d/oil", "", "°C", 0)))
    GaugeType.BipolarBar -> GaugeSpec(type, title, null, -25.0, 25.0, -10.0, 10.0, listOf(GaugeChannel("d/b1", "B1", "%", 1), GaugeChannel("d/b2", "B2", "%", 1)))
    GaugeType.BigNumber -> GaugeSpec(type, title, null, 40.0, 120.0, null, null, listOf(GaugeChannel("d/cool", "", "°C", 0)))
    GaugeType.Trend -> GaugeSpec(type, title, null, 0.0, 7000.0, null, null, listOf(GaugeChannel("d/rpm", "", rpmUnit, 0)))
    GaugeType.MultiValue -> GaugeSpec(type, title, null, 0.8, 1.2, null, null, listOf(GaugeChannel("d/l1", "B1", "λ", 2), GaugeChannel("d/l2", "B2", "λ", 2)))
    GaugeType.Status -> GaugeSpec(type, title, null, 0.0, 1.0, null, null, listOf(GaugeChannel("d/status", "", "", 0)))
}

// ------------------------------------------------------------------ measurement picker

/**
 * Add-card flow: type → SGBD → measurement(s). With [fixedType] it only (re)picks measurements.
 */
@Composable
internal fun MeasurementPickerSheet(
    vm: DashboardViewModel,
    fixedType: GaugeType?,
    initialIds: List<String>,
    renderer: GaugeRenderer,
    onDismiss: () -> Unit,
    onConfirm: (GaugeType, List<String>) -> Unit,
) {
    var type by remember { mutableStateOf(fixedType) }
    var sgbd by remember { mutableStateOf(initialIds.firstOrNull()?.substringBefore('/')) }
    val choices by produceState<SgbdChoices?>(null) { value = vm.sgbdChoices() }
    val selected = remember { mutableStateListOf<String>().apply { addAll(initialIds) } }
    var autoSkipped by remember { mutableStateOf(false) }
    LaunchedEffect(choices, type) {
        val list = choices?.all
        if (type != null && sgbd == null && list != null && list.size == 1 && !autoSkipped) {
            sgbd = list[0].name
            autoSkipped = true
        }
    }

    CockpitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            val t = type
            val s = sgbd
            when {
                t == null -> {
                    SheetHeader(stringResource(R.string.choose_type))
                    TypeStep(renderer) { type = it }
                }
                s == null -> {
                    SheetHeader(stringResource(R.string.choose_sgbd), onBack = if (fixedType == null) ({ type = null }) else null)
                    SgbdStep(choices) { sgbd = it }
                }
                else -> {
                    SheetHeader(
                        s.uppercase(),
                        onBack = {
                            if (autoSkipped || (choices?.all?.size ?: 0) <= 1) {
                                if (fixedType == null) type = null
                                sgbd = null
                                autoSkipped = true
                            } else {
                                sgbd = null
                            }
                        },
                    )
                    MeasurementStep(vm, s, t, selected, onConfirm = { onConfirm(t, selected.toList()) })
                }
            }
        }
    }
}

@Composable
private fun TypeStep(renderer: GaugeRenderer, onPick: (GaugeType) -> Unit) {
    val feed = remember { DemoFeed() }
    val clock = remember { mutableStateOf(0L) }
    val rpmUnit = renderer.format.displayUnit("rpm")
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(GaugeType.entries) { type ->
            val name = typeName(type)
            val spec = remember(type, name) { demoSpec(type, name, rpmUnit) }
            val state = remember(type) { GaugeState() }
            Column(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Cockpit.Background)
                    .clickable { onPick(type) }
                    .padding(8.dp),
            ) {
                GaugeCard(spec, state, renderer, feed, clock, Modifier.fillMaxWidth().height(if (type == GaugeType.Dial || type == GaugeType.BigNumber) 150.dp else 104.dp))
                Spacer(Modifier.height(8.dp))
                Text(name, color = Cockpit.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp))
                Text(typeDescription(type), color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

/** ECU list: search by SGBD name or ECU description, ECUs detected in the car first. */
@Composable
internal fun SgbdStep(choices: SgbdChoices?, onPick: (String) -> Unit) {
    if (choices == null) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(stringResource(R.string.sgbd_loading), color = Cockpit.Muted, fontSize = 15.sp)
        }
        return
    }
    if (choices.isEmpty) {
        Text(stringResource(R.string.no_sgbd), color = Cockpit.Muted, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(24.dp))
        return
    }
    var query by remember { mutableStateOf("") }
    val detected = remember(choices, query) { SgbdChoices.filter(choices.detected, query) }
    val all = remember(choices, query) { SgbdChoices.filter(choices.all, query) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, label = { Text(stringResource(R.string.sgbd_search)) },
            modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        if (detected.isEmpty() && all.isEmpty()) Text(stringResource(R.string.no_sgbd_match), color = Cockpit.Muted, fontSize = 15.sp)
        LazyColumn(Modifier.weight(1f)) {
            if (detected.isNotEmpty()) {
                item(key = "h_detected") { SectionLabel(stringResource(R.string.sgbd_detected)) }
                items(detected, key = { "d_" + it.name }) { SgbdRow(it, onPick) }
                item(key = "h_all") { SectionLabel(pluralStringResource(R.plurals.sgbd_all, all.size, all.size)) }
            } else {
                item(key = "h_count") { SectionLabel(pluralStringResource(R.plurals.sgbd_all, all.size, all.size)) }
            }
            items(all, key = { it.name }) { SgbdRow(it, onPick) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), color = Cockpit.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun SgbdRow(info: SgbdInfo, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).clickable { onPick(info.name) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(info.name.uppercase(), color = Cockpit.Text, fontSize = 17.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold)
            if (info.description.isNotBlank()) {
                Text(info.description, color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (info.measurementCount > 0) {
            Text(
                pluralStringResource(R.plurals.values_count, info.measurementCount, info.measurementCount),
                color = Cockpit.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp),
            )
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Cockpit.Muted)
    }
    HorizontalDivider(color = Cockpit.Border)
}

@Composable
private fun MeasurementStep(
    vm: DashboardViewModel, sgbd: String, type: GaugeType, selected: MutableList<String>, onConfirm: () -> Unit,
) {
    val all by produceState<List<Measurement>?>(null, sgbd) { value = vm.measurements(sgbd) }
    var query by remember { mutableStateOf("") }
    val range = Card.measurementRange(type)
    val single = range.last == 1
    val filtered = remember(all, query) {
        val q = query.trim()
        all.orEmpty().filter { m ->
            q.isEmpty() || m.label.contains(q, ignoreCase = true) || m.id.contains(q, ignoreCase = true) ||
                m.description.contains(q, ignoreCase = true) || m.unit.equals(q, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, label = { Text(stringResource(R.string.search)) },
            modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
        )
        Text(
            if (single) pluralStringResource(R.plurals.measurements_count, filtered.size, filtered.size) else stringResource(R.string.selected_count, selected.size, range.last),
            color = Cockpit.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp),
        )
        if (all != null && filtered.isEmpty()) Text(stringResource(R.string.no_match), color = Cockpit.Muted, fontSize = 15.sp)
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { m ->
                val isSel = m.id in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSel) Cockpit.Accent.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable {
                            if (single) {
                                selected.clear()
                                selected.add(m.id)
                                onConfirm()
                            } else if (isSel) {
                                selected.remove(m.id)
                            } else if (selected.size < range.last) {
                                selected.add(m.id)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label, color = Cockpit.Text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            sourceLine(m),
                            color = Cockpit.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isSel) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (!single) {
            PillButton(
                stringResource(R.string.add) + if (selected.isNotEmpty()) " (${selected.size})" else "",
                onClick = onConfirm, enabled = selected.size in range, color = Cockpit.OnAccent, background = Cockpit.Accent,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
    }
}

/** "°C  ·  0x4402  ·  STAT_OELTEMPERATUR_WERT" or "°C  ·  STATUS_GETRIEBETEMPERATUR  ·  STAT_…_WERT". */
private fun sourceLine(m: Measurement): String {
    val unit = m.unit.takeIf { it.isNotBlank() && it != "-" }
    return when (val src = m.source) {
        is MeasurementSource.MeasurementTable -> listOfNotNull(unit, m.id.substringAfter('/'), src.resultName)
        is MeasurementSource.JobResult -> listOfNotNull(unit, src.job + if (src.args.isNotEmpty()) " " + src.args.substringAfter(';') else "", src.resultName)
        is MeasurementSource.Sensor -> listOfNotNull(unit, src.sensor.uppercase(java.util.Locale.ROOT), src.field)
    }.joinToString("  ·  ")
}

// ------------------------------------------------------------------ card editor

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardEditorSheet(
    card: Card,
    page: Page,
    target: Target,
    vm: DashboardViewModel,
    renderer: GaugeRenderer,
    index: Map<String, Measurement>,
    frame: State<Long>,
    onDismiss: () -> Unit,
    onSave: (Card) -> Unit,
    onDelete: () -> Unit,
) {
    val locale = currentLocale()
    var type by remember(card.id) { mutableStateOf(card.type) }
    var ids by remember(card.id) { mutableStateOf(card.measurementIds) }
    var title by remember(card.id) { mutableStateOf(card.title.orEmpty()) }
    val labels = remember(card.id) { mutableStateListOf<String>().apply { addAll(card.channelLabels.orEmpty()) } }
    var min by remember(card.id) { mutableStateOf(editNumber(card.min, locale)) }
    var max by remember(card.id) { mutableStateOf(editNumber(card.max, locale)) }
    var warnBelow by remember(card.id) { mutableStateOf(editNumber(card.warnBelow, locale)) }
    var warnAbove by remember(card.id) { mutableStateOf(editNumber(card.warnAbove, locale)) }
    var decimals by remember(card.id) { mutableIntStateOf(card.decimals ?: -1) }
    var windowSeconds by remember(card.id) { mutableStateOf(card.windowSeconds) }
    var colSpan by remember(card.id) { mutableIntStateOf(card.colSpan) }
    var rowSpan by remember(card.id) { mutableIntStateOf(card.rowSpan) }
    var picking by remember { mutableStateOf(false) }

    val draft = card.copy(
        type = type, measurementIds = ids, title = title.trim().ifEmpty { null },
        channelLabels = labels.toList().takeIf { l -> l.any { it.isNotBlank() } }?.let { l -> List(ids.size) { l.getOrNull(it).orEmpty() } },
        min = parseNumber(min), max = parseNumber(max), warnBelow = parseNumber(warnBelow), warnAbove = parseNumber(warnAbove),
        decimals = decimals.takeIf { it >= 0 }, colSpan = colSpan, rowSpan = rowSpan,
        windowSeconds = windowSeconds.takeIf { type == GaugeType.Trend },
    )
    val auto = remember(card.id, ids, type, index) { CardSpecs.build(card.copy(type = type, measurementIds = ids, title = null, min = null, max = null, channelLabels = null, decimals = null), index, renderer.format) }
    val spec = remember(draft, index) { CardSpecs.build(draft, index, renderer.format) }
    val state = remember(card.id) { GaugeState() }
    val fits = GridRules.canPlace(page, draft)
    val minError = draft.min != null && draft.max != null && draft.max <= draft.min

    CockpitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            SheetHeader(stringResource(R.string.edit_card)) {
                RoundIconButton(R.drawable.ic_delete, stringResource(R.string.delete), onClick = onDelete, tint = Cockpit.Danger, background = Color.Transparent)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                // Live preview with the card's proportions.
                val aspect = cardAspect(page, target, colSpan, rowSpan)
                Box(
                    Modifier.fillMaxWidth().height(186.dp).clip(RoundedCornerShape(20.dp)).background(Cockpit.Background).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val w = minOf(maxWidth, maxHeight * aspect)
                        GaugeCard(spec, state, renderer, vm.feed, frame, Modifier.width(w).aspectRatio(aspect))
                    }
                }

                SectionTitle(stringResource(R.string.card_type))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GaugeType.entries.forEach { t ->
                        val ok = ids.size in Card.measurementRange(t)
                        val sel = t == type
                        PillButton(
                            typeName(t), onClick = { type = t }, enabled = ok || sel,
                            color = if (sel) Cockpit.OnAccent else Cockpit.Text, background = if (sel) Cockpit.Accent else Track, bold = sel,
                        )
                    }
                }

                SectionTitle(stringResource(R.string.measurements))
                ids.forEachIndexed { i, id ->
                    val m = index[id]
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m?.label ?: id, color = Cockpit.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(m?.unit?.takeIf { it.isNotBlank() && it != "-" }, id).joinToString("  ·  "), color = Cockpit.Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        if (ids.size > 1) {
                            OutlinedTextField(
                                value = labels.getOrNull(i).orEmpty(),
                                onValueChange = { v -> while (labels.size <= i) labels.add(""); labels[i] = v.take(12) },
                                singleLine = true, label = { Text(stringResource(R.string.short_label), fontSize = 11.sp) },
                                placeholder = { Text(auto.channels.getOrNull(i)?.label.orEmpty(), color = Cockpit.Muted) },
                                modifier = Modifier.width(120.dp), colors = fieldColors(),
                            )
                        }
                    }
                }
                PillButton(stringResource(R.string.change), onClick = { picking = true }, icon = R.drawable.ic_edit, color = Cockpit.Accent)

                SectionTitle(stringResource(R.string.title))
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.title)) },
                    placeholder = { Text(stringResource(R.string.automatic, auto.title), color = Cockpit.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                )

                if (type != GaugeType.Status) {
                    SectionTitle(stringResource(R.string.scale))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(stringResource(R.string.min), min, { min = it }, editNumber(auto.min, locale), Modifier.weight(1f), isError = minError)
                        NumberField(stringResource(R.string.max), max, { max = it }, editNumber(auto.max, locale), Modifier.weight(1f), isError = minError)
                    }
                    SectionTitle(stringResource(R.string.thresholds))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(stringResource(R.string.warn_below), warnBelow, { warnBelow = it }, "—", Modifier.weight(1f))
                        NumberField(stringResource(R.string.warn_above), warnAbove, { warnAbove = it }, "—", Modifier.weight(1f))
                    }
                    Stepper(
                        stringResource(R.string.decimals), if (decimals < 0) stringResource(R.string.auto) else decimals.toString(),
                        onDecrease = { decimals-- }, onIncrease = { decimals++ }, canDecrease = decimals >= 0, canIncrease = decimals < 3,
                        decreaseLabel = stringResource(R.string.decrease), increaseLabel = stringResource(R.string.increase),
                    )
                }

                if (type == GaugeType.Trend) {
                    TrendWindowSection(windowSeconds) { windowSeconds = it }
                }

                SectionTitle(stringResource(R.string.size))
                Stepper(
                    stringResource(R.string.width), colSpan.toString(), onDecrease = { colSpan-- }, onIncrease = { colSpan++ },
                    canDecrease = colSpan > 1, canIncrease = card.col + colSpan < page.columns,
                    decreaseLabel = stringResource(R.string.decrease), increaseLabel = stringResource(R.string.increase),
                )
                Stepper(
                    stringResource(R.string.height), rowSpan.toString(), onDecrease = { rowSpan-- }, onIncrease = { rowSpan++ },
                    canDecrease = rowSpan > 1, canIncrease = card.row + rowSpan < page.rows,
                    decreaseLabel = stringResource(R.string.decrease), increaseLabel = stringResource(R.string.increase),
                )
                if (!fits) Text(stringResource(R.string.size_refused), color = Cockpit.Warning, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                PillButton(
                    stringResource(R.string.save), onClick = { onSave(draft) },
                    enabled = fits && !minError && draft.windowSeconds?.let { TrendWindow.isValid(it) } != false,
                    color = Cockpit.OnAccent, background = Cockpit.Accent, modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (picking) {
        MeasurementPickerSheet(
            vm = vm, fixedType = type, initialIds = ids, renderer = renderer, onDismiss = { picking = false },
            onConfirm = { _, newIds ->
                ids = newIds
                labels.clear()
                picking = false
            },
        )
    }
}

/** Width / height ratio of a card of the given span on [page] (approximate screen proportions). */
private fun cardAspect(page: Page, target: Target, colSpan: Int, rowSpan: Int): Float {
    val (w, h, gap) = if (target == Target.Car) Triple(1136f, 358f, 18f) else Triple(379f, 600f, 12f)
    val cw = (w - gap * (page.columns - 1)) / page.columns
    val ch = (h - gap * (page.rows - 1)) / page.rows
    return ((colSpan * cw + (colSpan - 1) * gap) / (rowSpan * ch + (rowSpan - 1) * gap)).coerceIn(0.4f, 4f)
}

// ------------------------------------------------------------------ layout (grid size + presets)

@Composable
internal fun LayoutSheet(page: Page, target: Target, onDismiss: () -> Unit, onGrid: (Int, Int) -> Boolean, onPreset: (LayoutPreset) -> Unit) {
    val limits = GridLimits.of(target)
    var confirm by remember { mutableStateOf<LayoutPreset?>(null) }
    CockpitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            SheetHeader(stringResource(R.string.layout))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                SectionTitle(stringResource(R.string.grid))
                Stepper(
                    stringResource(R.string.columns), page.columns.toString(),
                    onDecrease = { onGrid(page.columns - 1, page.rows) }, onIncrease = { onGrid(page.columns + 1, page.rows) },
                    canDecrease = page.columns > 1, canIncrease = page.columns < limits.maxColumns,
                    decreaseLabel = stringResource(R.string.decrease), increaseLabel = stringResource(R.string.increase),
                )
                Stepper(
                    stringResource(R.string.rows), page.rows.toString(),
                    onDecrease = { onGrid(page.columns, page.rows - 1) }, onIncrease = { onGrid(page.columns, page.rows + 1) },
                    canDecrease = page.rows > 1, canIncrease = page.rows < limits.maxRows,
                    decreaseLabel = stringResource(R.string.decrease), increaseLabel = stringResource(R.string.increase),
                )
                SectionTitle(stringResource(R.string.layout))
                LayoutPresets.of(target).forEach { preset ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { if (GridRules.droppedByPreset(page, preset) > 0) confirm = preset else onPreset(preset) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PresetDiagram(preset, Modifier.width(if (target == Target.Car) 96.dp else 52.dp).height(if (target == Target.Car) 40.dp else 64.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(presetName(preset), color = Cockpit.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    confirm?.let { preset ->
        ConfirmDialog(
            text = pluralStringResource(R.plurals.preset_drops, GridRules.droppedByPreset(page, preset), GridRules.droppedByPreset(page, preset)),
            confirm = stringResource(R.string.apply),
            onConfirm = {
                confirm = null
                onPreset(preset)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun PresetDiagram(preset: LayoutPreset, modifier: Modifier) {
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val cw = (size.width - gap * (preset.columns - 1)) / preset.columns
        val ch = (size.height - gap * (preset.rows - 1)) / preset.rows
        preset.slots.forEachIndexed { i, s ->
            drawRoundRect(
                color = if (i == 0) Cockpit.Accent.copy(alpha = 0.55f) else Cockpit.Border,
                topLeft = Offset(s.col * (cw + gap), s.row * (ch + gap)),
                size = Size(s.colSpan * cw + (s.colSpan - 1) * gap, s.rowSpan * ch + (s.rowSpan - 1) * gap),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

// ------------------------------------------------------------------ pages

@Composable
internal fun PagesSheet(
    pages: List<Page>,
    onDismiss: () -> Unit,
    onRename: (String, String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Page?>(null) }
    var deleting by remember { mutableStateOf<Page?>(null) }
    CockpitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f)) {
            SheetHeader(stringResource(R.string.pages))
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                itemsIndexed(pages, key = { _, p -> p.id }) { i, page ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).heightIn(min = MinTouch).clip(RoundedCornerShape(10.dp)).clickable { renaming = page }.padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(page.name, color = Cockpit.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.grid_size, page.columns, page.rows) + "  ·  " + page.cards.size,
                                color = Cockpit.Muted, fontSize = 12.sp,
                            )
                        }
                        RoundIconButton(R.drawable.ic_chevron_up, stringResource(R.string.move_up), onClick = { onMove(i, i - 1) }, enabled = i > 0, background = Color.Transparent)
                        RoundIconButton(R.drawable.ic_chevron_down, stringResource(R.string.move_down), onClick = { onMove(i, i + 1) }, enabled = i < pages.lastIndex, background = Color.Transparent)
                        RoundIconButton(R.drawable.ic_delete, stringResource(R.string.delete), onClick = { deleting = page }, enabled = pages.size > 1, tint = Cockpit.Danger, background = Color.Transparent)
                    }
                    HorizontalDivider(color = Cockpit.Border)
                }
            }
            PillButton(
                stringResource(R.string.add_page), onClick = onAdd, icon = R.drawable.ic_add, color = Cockpit.Accent,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
    renaming?.let { page ->
        var name by remember(page.id) { mutableStateOf(page.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, containerColor = Cockpit.Card,
            title = { Text(stringResource(R.string.rename), color = Cockpit.Text) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text(stringResource(R.string.page_name)) },
                    colors = fieldColors(),
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(page.id, name); renaming = null }, modifier = Modifier.heightIn(min = MinTouch)) {
                    Text(stringResource(R.string.save), color = Cockpit.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.cancel), color = Cockpit.Muted) }
            },
        )
    }
    deleting?.let { page ->
        ConfirmDialog(
            text = stringResource(R.string.delete_page_confirm, page.name), confirm = stringResource(R.string.delete),
            onConfirm = { onDelete(page.id); deleting = null }, onDismiss = { deleting = null },
        )
    }
}

// ------------------------------------------------------------------ full screen car preview

/** The car page rotated to landscape across the whole phone screen, with a stand-in AA rail. Tap to page. */
@Composable
internal fun CarFullscreenPreview(
    pages: List<Page>, initialIndex: Int, vm: DashboardViewModel, index: Map<String, Measurement>,
    status: AcquisitionStatus, rec: RecordingState, frame: State<Long>, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val locale = currentLocale()
    val renderer = remember(locale) { CarPageRenderer(context, locale) }
    val states = remember { HashMap<String, GaugeState>() }
    var current by remember { mutableIntStateOf(initialIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .pointerInput(pages.size) {
                        detectTapGestures { p -> if (pages.isNotEmpty()) current = if (p.y < size.height / 2) (current - 1 + pages.size) % pages.size else (current + 1) % pages.size }
                    },
            ) {
                val page = pages.getOrNull(current) ?: return@Canvas
                frame.value
                // Landscape box of 1280:480 fitted to the rotated screen.
                val w = minOf(size.height, size.width * 1280f / 480f)
                val h = w * 480f / 1280f
                translate(left = (size.width - h) / 2f, top = (size.height - w) / 2f) {
                    rotate(90f, pivot = Offset(h / 2f, h / 2f)) {
                        drawIntoCanvas {
                            CarScreen.drawWithRail(
                                it.nativeCanvas, w, h, renderer, context, page, current, pages.size, index, vm.feed, status, rec, frame.value, states,
                            )
                        }
                    }
                }
            }
            RoundIconButton(
                R.drawable.ic_close, stringResource(R.string.close), onClick = onDismiss,
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp), background = Color(0xCC1C1F24),
            )
        }
    }
}

/**
 * Time window of a trend card: quick choices plus a free value in seconds. null = the default
 * window ([TrendWindow.DEFAULT_SECONDS]), shown as the "Other" value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrendWindowSection(windowSeconds: Int?, onChange: (Int?) -> Unit) {
    val current = TrendWindow.seconds(windowSeconds)
    val quick = windowSeconds != null && windowSeconds in TrendWindow.QUICK
    var custom by remember { mutableStateOf(!quick) }
    var text by remember { mutableStateOf(if (quick) "" else current.toString()) }
    SectionTitle(stringResource(R.string.trend_window))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TrendWindow.QUICK.forEach { sec ->
            val sel = !custom && windowSeconds == sec
            PillButton(
                TrendWindow.duration(sec), onClick = { custom = false; onChange(sec) },
                color = if (sel) Cockpit.OnAccent else Cockpit.Text, background = if (sel) Cockpit.Accent else Track, bold = sel,
            )
        }
        PillButton(
            stringResource(R.string.trend_window_other), onClick = { custom = true; text = current.toString(); onChange(current) },
            color = if (custom) Cockpit.OnAccent else Cockpit.Text, background = if (custom) Cockpit.Accent else Track, bold = custom,
        )
    }
    if (custom) {
        val parsed = text.toIntOrNull()
        val error = parsed == null || !TrendWindow.isValid(parsed)
        Spacer(Modifier.height(10.dp))
        NumberField(
            stringResource(R.string.trend_window_seconds), text,
            { v -> text = v.filter { it.isDigit() }.take(4); onChange(text.toIntOrNull() ?: -1) },
            TrendWindow.DEFAULT_SECONDS.toString(), Modifier.fillMaxWidth(), isError = error,
        )
        Text(
            if (error || parsed == null) stringResource(R.string.trend_window_range, TrendWindow.MIN_SECONDS, TrendWindow.MAX_SECONDS / 60)
            else TrendWindow.duration(parsed),
            color = if (error) Cockpit.Warning else Cockpit.Muted, fontSize = 13.sp,
        )
    }
    Text(stringResource(R.string.trend_window_hint), color = Cockpit.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}
