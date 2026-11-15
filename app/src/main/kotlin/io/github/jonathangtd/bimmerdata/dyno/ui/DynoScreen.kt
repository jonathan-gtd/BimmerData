/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dyno.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.cars.CarBook
import io.github.jonathangtd.bimmerdata.dashboard.ui.MinTouch
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.TextInputDialog
import io.github.jonathangtd.bimmerdata.dyno.DynoCsv
import io.github.jonathangtd.bimmerdata.dyno.DynoDefaults
import io.github.jonathangtd.bimmerdata.dyno.DynoInputs
import io.github.jonathangtd.bimmerdata.dyno.DynoLiveState
import io.github.jonathangtd.bimmerdata.dyno.DynoNotReady
import io.github.jonathangtd.bimmerdata.dyno.DynoPhase
import io.github.jonathangtd.bimmerdata.dyno.DynoRun
import io.github.jonathangtd.bimmerdata.dyno.DynoRuns
import io.github.jonathangtd.bimmerdata.dyno.DynoRuntime
import io.github.jonathangtd.bimmerdata.faults.ConfirmDialog
import io.github.jonathangtd.bimmerdata.gauges.currentLocale
import io.github.jonathangtd.bimmerdata.live.CsvFiles
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.ProfileMeasurements
import io.github.jonathangtd.bimmerdata.live.dyno.DynoProblem
import io.github.jonathangtd.bimmerdata.live.dyno.DynoResult
import io.github.jonathangtd.bimmerdata.live.dyno.DynoWarning
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.ui.ConnectionPillContent
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Which input a picker chooses. */
private enum class InputRole(val title: Int, val optional: Boolean, val suits: (Measurement) -> Boolean) {
    Rpm(R.string.dyno_input_rpm, false, DynoDefaults::isRpm),
    Speed(R.string.dyno_input_speed, false, DynoDefaults::isSpeed),
    Pedal(R.string.dyno_input_pedal, true, DynoDefaults::isPercent),
    Overlay(R.string.dyno_input_overlay, true, DynoDefaults::isOverlay),
}

private fun DynoInputs.id(role: InputRole) = when (role) {
    InputRole.Rpm -> rpm
    InputRole.Speed -> speed
    InputRole.Pedal -> pedal
    InputRole.Overlay -> overlay
}

private fun DynoInputs.with(role: InputRole, id: String?) = when (role) {
    InputRole.Rpm -> copy(rpm = id)
    InputRole.Speed -> copy(speed = id)
    InputRole.Pedal -> copy(pedal = id)
    InputRole.Overlay -> copy(overlay = id)
}

private sealed interface DynoDialog {
    data object Physics : DynoDialog
    data class Rename(val run: DynoRun) : DynoDialog
    data class Delete(val run: DynoRun) : DynoDialog
}

/**
 * « Banc de puissance »: the virtual dyno of the selected car. Car values (mass, drag), the
 * values read, arm / start / stop, the runs (tap to show, a second tap compares two), their curves.
 * Opened from the Graph tab.
 */
@Composable
fun DynoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val runtime = remember { DynoRuntime.get(context) }
    val repo = remember { LiveRepository.get(context) }
    val cars = remember { CarBook.get(context) }
    val scope = rememberCoroutineScope()
    val live by runtime.state.collectAsStateWithLifecycle()
    val book by runtime.store.flow.collectAsStateWithLifecycle()
    val vehicles by remember { VehicleRepository.get(context) }.state.collectAsStateWithLifecycle()
    val connection by repo.connection.collectAsStateWithLifecycle()
    val status by repo.liveDataSource.status.collectAsStateWithLifecycle()
    val paused by repo.paused.collectAsStateWithLifecycle()
    val settings by remember { AppSettings.get(context) }.state.collectAsStateWithLifecycle()
    val profileValues by ProfileMeasurements.flow.collectAsStateWithLifecycle()
    val car: Vehicle? = vehicles.garage.current
    val candidates by produceState(emptyList<Measurement>(), profileValues) { value = withContext(Dispatchers.IO) { runtime.candidates() } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runtime.book() } }

    var dialog by remember { mutableStateOf<DynoDialog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var shown by rememberSaveable { mutableStateOf(listOf<String>()) }

    val saved = car?.let { book.inputs[it.id] } ?: DynoInputs()
    val used = DynoDefaults.resolve(saved, candidates)
    val runs = car?.let { c -> book.runsOf(c.id).sortedByDescending { it.startedAtMs } }.orEmpty()
    // newest run kept: shown at once
    LaunchedEffect(live.lastRunId) { live.lastRunId?.let { id -> shown = listOf(id) } }
    val results by produceState(emptyMap<String, DynoResult>(), runs, car?.physics) {
        value = withContext(Dispatchers.Default) { runs.associate { it.id to DynoRuns.result(it, car?.physics) } }
    }

    fun act(op: () -> Unit) {
        error = null
        scope.launch(Dispatchers.IO) {
            try {
                op()
            } catch (e: DynoNotReady) {
                error = e.code
            }
        }
    }

    fun setInputs(next: DynoInputs) {
        val c = car ?: return
        scope.launch(Dispatchers.IO) { runCatching { runtime.setInputs(c.id, next) } }
    }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Cockpit.Background).padding(top = insets.calculateTopPadding()),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(R.drawable.ic_chevron_left, stringResource(R.string.dyno_back), onClick = onBack, background = Color.Transparent)
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dyno_title), color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                    car?.let { Text(it.label, color = Cockpit.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        item {
            ConnectionPillContent(
                connection, status, settings.lastMode, repo::connect, repo::disconnect, Modifier.fillMaxWidth(),
                paused = paused, onPause = repo::setReadingPaused,
            )
        }
        if (car == null) {
            item { Note(stringResource(R.string.dyno_no_car)) }
            return@LazyColumn
        }
        item { CarCard(car, onEdit = { dialog = DynoDialog.Physics }) }
        item { InputsCard(used, saved, candidates, onChange = ::setInputs) }
        item {
            ControlCard(
                live, hasPedal = used.pedal != null, error = error, locale = currentLocale(),
                onArm = { act(runtime::arm) }, onDisarm = { act(runtime::disarm) },
                onStart = { act(runtime::start) }, onStop = { act(runtime::stop) },
            )
        }
        val shownRuns = shown.mapNotNull { id -> runs.firstOrNull { it.id == id } }
        if (shownRuns.isNotEmpty()) item { ResultCard(shownRuns, results) }
        item { Hints() }
        if (runs.isNotEmpty()) {
            item { Text(stringResource(R.string.dyno_runs), color = Cockpit.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            items(runs, key = { it.id }) { run ->
                RunRow(
                    run, results[run.id], selected = run.id in shown, compared = shown.size > 1 && shown.lastOrNull() == run.id,
                    onTap = {
                        shown = when {
                            run.id in shown -> shown - run.id
                            shown.isEmpty() -> listOf(run.id)
                            else -> listOf(shown.first(), run.id)
                        }
                    },
                    onShare = {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                runCatching {
                                    var published: android.net.Uri? = null
                                    CsvFiles.create(context, DynoCsv.fileName(run)) { published = it }.use { out ->
                                        out.write(DynoCsv.encode(run, DynoRuns.result(run, car.physics), car.label).toByteArray(Charsets.UTF_8))
                                    }
                                    published
                                }.getOrNull()
                            }
                            if (uri != null) try {
                                context.startActivity(CsvFiles.shareIntent(uri, res.getString(R.string.dyno_share)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: ActivityNotFoundException) {
                            }
                        }
                    },
                    onRename = { dialog = DynoDialog.Rename(run) },
                    onDelete = { dialog = DynoDialog.Delete(run) },
                )
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        DynoDialog.Physics -> car?.let { c ->
            CarPhysicsDialog(
                c.label, c.physics, saved.drivelineLossPct,
                onSave = { physics, loss ->
                    dialog = null
                    scope.launch(Dispatchers.IO) {
                        cars.updatePhysics(c.id, physics)
                        if (loss != null && loss != saved.drivelineLossPct) runCatching { runtime.setInputs(c.id, saved.copy(drivelineLossPct = loss)) }
                    }
                },
                onDismiss = { dialog = null },
            )
        }
        is DynoDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.dyno_rename), initial = d.run.note, placeholder = stringResource(R.string.dyno_rename_placeholder),
            maxLength = DynoRuns.MAX_NOTE_LENGTH, hint = null,
            onConfirm = { text -> dialog = null; scope.launch(Dispatchers.IO) { runtime.renameRun(d.run.id, text) } },
            onDismiss = { dialog = null },
        )
        is DynoDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.dyno_delete_title), text = stringResource(R.string.dyno_delete_text),
            confirm = stringResource(R.string.dyno_delete),
            onConfirm = {
                dialog = null
                shown = shown - d.run.id
                scope.launch(Dispatchers.IO) { runtime.deleteRun(d.run.id) }
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Cockpit.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun PanelTitle(text: String) =
    Text(text, color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

@Composable
private fun Note(text: String, color: Color = Cockpit.Muted) = Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp)

private fun num(v: Double?, decimals: Int, locale: Locale): String =
    v?.let { String.format(locale, "%,.${decimals}f", it) } ?: "—"

@Composable
private fun CarCard(car: Vehicle, onEdit: () -> Unit) {
    val locale = currentLocale()
    val p = car.physics
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PanelTitle(stringResource(R.string.dyno_car_values))
                if (p.massKg == null) {
                    Note(stringResource(R.string.dyno_mass_missing), Cockpit.Warning)
                } else {
                    val r = p.resolved()!!
                    Note(stringResource(R.string.dyno_car_summary, num(r.massKg, 0, locale), num(r.dragAreaM2, 2, locale), num(r.rollingResistance, 3, locale)))
                }
            }
            PillButton(stringResource(R.string.dyno_edit), onClick = onEdit, icon = R.drawable.ic_edit)
        }
    }
}

@Composable
private fun InputsCard(used: DynoInputs, saved: DynoInputs, candidates: List<Measurement>, onChange: (DynoInputs) -> Unit) {
    Panel {
        PanelTitle(stringResource(R.string.dyno_inputs))
        for (role in InputRole.entries) {
            InputRow(role, used.id(role), saved.id(role) == null, candidates.filter(role.suits), candidates, onPick = { id -> onChange(saved.with(role, id)) })
        }
        Note(stringResource(R.string.dyno_inputs_hint))
    }
}

@Composable
private fun InputRow(role: InputRole, id: String?, isDefault: Boolean, choices: List<Measurement>, all: List<Measurement>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val m = id?.let { i -> all.firstOrNull { it.id == i } }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouch).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(role.title), color = Cockpit.Muted, fontSize = 12.sp)
                Text(
                    when {
                        m != null -> "${m.label} · ${m.unit}"
                        id != null -> id
                        role.optional -> stringResource(R.string.dyno_input_none)
                        else -> stringResource(R.string.dyno_input_missing)
                    } + if (isDefault && id != null) " " + stringResource(R.string.dyno_input_auto) else "",
                    color = if (id == null && !role.optional) Cockpit.Warning else Cockpit.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Cockpit.Card) {
            DropdownMenuItem(
                text = { Text(stringResource(if (role.optional) R.string.dyno_input_none else R.string.dyno_input_default), color = Cockpit.Muted) },
                onClick = { open = false; onPick(null) },
            )
            if (choices.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dyno_input_no_choice), color = Cockpit.Muted) }, onClick = { open = false }, enabled = false)
            }
            for (c in choices) {
                DropdownMenuItem(
                    text = { Text("${c.label} · ${c.unit}", color = if (c.id == id) Cockpit.Accent else Cockpit.Text, maxLines = 2) },
                    onClick = { open = false; onPick(c.id) },
                )
            }
        }
    }
}

@Composable
private fun ControlCard(
    live: DynoLiveState, hasPedal: Boolean, error: String?, locale: Locale,
    onArm: () -> Unit, onDisarm: () -> Unit, onStart: () -> Unit, onStop: () -> Unit,
) {
    Panel {
        val (title, color) = when (live.phase) {
            DynoPhase.Idle -> stringResource(R.string.dyno_idle) to Cockpit.Muted
            DynoPhase.Armed -> stringResource(if (hasPedal) R.string.dyno_armed_pedal else R.string.dyno_armed_manual) to Cockpit.Accent
            DynoPhase.Running -> pluralStringResource(R.plurals.dyno_running, live.runSamples, live.runSamples) to Cockpit.Warning
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Cockpit.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
        if (live.phase != DynoPhase.Idle) {
            Note(pluralStringResource(R.plurals.dyno_live, live.readings.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), num(live.rpm, 0, locale), live.pedal?.let { num(it, 0, locale) + " %" } ?: "—", live.readings))
        }
        live.lastDiscardedSamples?.let { Note(pluralStringResource(R.plurals.dyno_discarded, it, it), Cockpit.Warning) }
        error?.let { code ->
            Note(
                stringResource(
                    when (code) {
                        "car_mass_missing" -> R.string.dyno_err_mass
                        "dyno_inputs_missing" -> R.string.dyno_err_inputs
                        else -> R.string.dyno_err_car
                    },
                ),
                Cockpit.Danger,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live.phase == DynoPhase.Idle) {
                PillButton(stringResource(R.string.dyno_arm), onClick = onArm, icon = R.drawable.ic_flag, color = Cockpit.OnAccent, background = Cockpit.Accent, modifier = Modifier.weight(1f))
            } else {
                PillButton(stringResource(R.string.dyno_disarm), onClick = onDisarm, icon = R.drawable.ic_close, modifier = Modifier.weight(1f))
            }
            if (live.phase == DynoPhase.Running) {
                PillButton(stringResource(R.string.dyno_stop), onClick = onStop, icon = R.drawable.ic_pause, color = Cockpit.OnAccent, background = Cockpit.Warning, modifier = Modifier.weight(1f))
            } else {
                PillButton(stringResource(R.string.dyno_start), onClick = onStart, icon = R.drawable.ic_play, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun problemText(p: DynoProblem) = when (p) {
    DynoProblem.NoMass -> R.string.dyno_problem_mass
    DynoProblem.TooFewSamples -> R.string.dyno_problem_few
    DynoProblem.NoSpeed -> R.string.dyno_problem_speed
    DynoProblem.NotOneGear -> R.string.dyno_problem_gear
    DynoProblem.NotAccelerating -> R.string.dyno_problem_accel
}

@Composable
private fun ResultCard(runs: List<DynoRun>, results: Map<String, DynoResult>) {
    val locale = currentLocale()
    Panel {
        val main = runs.first()
        val r = results[main.id]
        PanelTitle(main.note.ifEmpty { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(main.startedAtMs)) })
        if (r == null) {
            Note("…")
            return@Panel
        }
        r.problem?.let { Note(stringResource(problemText(it)), Cockpit.Warning) }
        val chart = runs.mapIndexedNotNull { i, run -> results[run.id]?.takeIf { it.ok }?.let { ChartRun(it.points, dim = i > 0) } }
        if (chart.isNotEmpty()) {
            DynoChart(
                chart,
                stringResource(R.string.dyno_chart_description, num(r.peakWheelPowerCh, 0, locale), num(r.peakWheelPowerRpm, 0, locale), num(r.peakTorqueNm, 0, locale), num(r.peakTorqueRpm, 0, locale)),
            )
            Legend(main.overlay != null, runs.size > 1)
        }
        if (r.ok) {
            Peak(stringResource(R.string.dyno_peak_wheel), "${num(r.peakWheelPowerCh, 0, locale)} ch", num(r.peakWheelPowerRpm, 0, locale), PowerColor)
            Peak(stringResource(R.string.dyno_peak_torque), "${num(r.peakTorqueNm, 0, locale)} Nm", num(r.peakTorqueRpm, 0, locale), TorqueColor)
            Peak(stringResource(R.string.dyno_peak_engine, num(main.drivelineLossPct, 0, locale)), "${num(r.peakEnginePowerCh, 0, locale)} ch", null, Cockpit.Muted)
            if (r.peakOverlayPowerCh != null) {
                Peak(stringResource(R.string.dyno_peak_overlay, main.overlay?.label.orEmpty()), "${num(r.peakOverlayPowerCh, 0, locale)} ch · ${num(r.peakOverlayTorqueNm, 0, locale)} Nm", null, Cockpit.Muted)
            }
            if (runs.size > 1) results[runs[1].id]?.takeIf { it.ok }?.let { b ->
                Peak(
                    stringResource(R.string.dyno_compared, runs[1].note.ifEmpty { DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(runs[1].startedAtMs)) }),
                    "${num(b.peakWheelPowerCh, 0, locale)} ch · ${num(b.peakTorqueNm, 0, locale)} Nm", null, Cockpit.Muted,
                )
            }
        }
        Note(
            pluralStringResource(
                R.plurals.dyno_run_facts, r.samples, num(r.kmhPer1000Rpm, 1, locale), num(r.ratioSpreadPct, 1, locale), r.samples, num(r.sampleRateHz, 1, locale),
            ),
        )
        for (w in r.warnings) {
            Note(
                stringResource(
                    when (w) {
                        DynoWarning.FewSamples -> R.string.dyno_warn_few
                        DynoWarning.LowSampleRate -> R.string.dyno_warn_rate
                        DynoWarning.AssumedFlat -> R.string.dyno_warn_flat
                    },
                ),
            )
        }
    }
}

@Composable
private fun Legend(overlay: Boolean, compared: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(PowerColor, stringResource(R.string.dyno_legend_power))
        LegendItem(TorqueColor, stringResource(R.string.dyno_legend_torque))
    }
    if (overlay) Note(stringResource(R.string.dyno_legend_overlay))
    if (compared) Note(stringResource(R.string.dyno_legend_compared))
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Cockpit.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Peak(label: String, value: String, rpm: String?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Cockpit.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 16.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold)
        if (rpm != null) Text(" · $rpm " + stringResource(R.string.dyno_rpm), color = Cockpit.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun Hints() {
    var open by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouch).clickable(role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.dyno_hints_title), color = Cockpit.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(painterResource(if (open) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp))
        }
        if (open) Note(stringResource(R.string.dyno_hints))
    }
}

@Composable
private fun RunRow(
    run: DynoRun, result: DynoResult?, selected: Boolean, compared: Boolean,
    onTap: () -> Unit, onShare: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
) {
    val locale = currentLocale()
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Cockpit.Accent.copy(alpha = if (compared) 0.06f else 0.12f) else Cockpit.Card)
            .then(if (selected) Modifier.border(1.dp, Cockpit.Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onTap)
            .semantics { this.selected = selected }
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(run.startedAtMs))
            Text(if (run.note.isEmpty()) date else "${run.note} · $date", color = Cockpit.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    result == null -> "…"
                    result.ok -> stringResource(
                        R.string.dyno_run_summary, num(result.peakWheelPowerCh, 0, locale), num(result.peakWheelPowerRpm, 0, locale),
                        num(result.peakTorqueNm, 0, locale), num(result.kmhPer1000Rpm, 1, locale),
                    )
                    else -> stringResource(problemText(result.problem ?: DynoProblem.TooFewSamples))
                },
                color = if (result?.ok == false) Cockpit.Warning else Cockpit.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            RoundIconButton(R.drawable.ic_more, stringResource(R.string.dyno_run_actions), onClick = { menu = true }, background = Color.Transparent)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Cockpit.Card) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dyno_share), color = Cockpit.Text) }, onClick = { menu = false; onShare() },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.dyno_rename), color = Cockpit.Text) }, onClick = { menu = false; onRename() },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.dyno_delete), color = Cockpit.Danger) }, onClick = { menu = false; onDelete() },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) })
            }
        }
    }
}
