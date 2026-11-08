/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd.FrameDirection
import io.github.jonathangtd.bimmerdata.i18n.jobTexts
import java.util.Locale

private val MinTouch = 48.dp

/**
 * "Mini Tool32": pick an SGBD and a job, run it, look at results, frames and timings. A
 * secondary screen (Settings › Tools, or an ECU's detail with [initialSgbd] selected).
 */
@Composable
fun Tool32Screen(vm: Tool32ViewModel = viewModel(), initialSgbd: String? = null, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(initialSgbd) { initialSgbd?.let(vm::preselectSgbd) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFolder(uri)
    }
    var sgbdPicker by rememberSaveable { mutableStateOf(false) }
    var jobPicker by rememberSaveable { mutableStateOf(false) }
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    // Job descriptions and result labels, translated (see i18n).
    val translator = io.github.jonathangtd.bimmerdata.i18n.rememberTranslator()
    val tv = io.github.jonathangtd.bimmerdata.i18n.translationVersion()
    val prgName = state.sgbd?.takeIf { it.endsWith(".prg", ignoreCase = true) }
    val translations = io.github.jonathangtd.bimmerdata.i18n.TranslationRepository.get(androidx.compose.ui.platform.LocalContext.current)
    val jobTexts by androidx.compose.runtime.produceState(emptyMap<String, String>(), prgName, tv) {
        value = if (prgName == null) emptyMap() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { translations.jobTexts(prgName) }
    }
    val resultLabel: (String) -> String? = { name -> state.job?.let { job -> translator.resultLabel(prgName, job, name) } }

    // The list is clipped below the status bar so scrolled content never runs under the clock/icons.
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Cockpit.Background).padding(top = insets.calculateTopPadding()),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp, bottom = insets.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state, onBack) }
        state.message?.let { msg -> item { MessageBanner(msg, vm::dismissMessage) } }
        item { ConnectionCard(state) }
        item { EcuFilesCard(state, onImport = { pickFolder.launch(null) }, onCancel = vm::cancelImport) }
        item {
            JobCard(
                state,
                onPickSgbd = { sgbdPicker = true },
                onPickJob = { jobPicker = true },
                onArgs = vm::setArgs,
                onRepeatCount = vm::setRepeatCount,
                onRun = { vm.run(1) },
                onRepeat = { vm.run(state.repeatCount.toIntOrNull() ?: 1) },
                onCancel = vm::cancelRun,
                onResolve = vm::resolveVariant,
            )
        }
        state.result?.let { result ->
            item { ResultSummaryCard(result, state.repeat, state.showFrames, vm::setShowFrames) }
            if (state.showFrames) {
                item { SectionLabel("Raw frames (${result.frames.size})") }
                items(result.frames) { FrameRow(it) }
            }
            items(result.sets, key = { it.index }) { set -> ResultSetCard(set, resultLabel) }
        }
    }

    if (sgbdPicker) {
        PickerDialog(
            title = "SGBD",
            items = state.ecuFiles,
            emptyText = "No ECU files. Import your EDIABAS Ecu folder first.",
            onDismiss = { sgbdPicker = false },
            onPick = {
                sgbdPicker = false
                vm.selectSgbd(it)
            },
        )
    }
    if (jobPicker) {
        PickerDialog(
            title = "Job",
            items = state.jobs,
            subtitle = { jobTexts[it.uppercase(Locale.ROOT)] },
            emptyText = if (state.jobsLoading) "Loading jobs…" else "No jobs (select an SGBD first).",
            onDismiss = { jobPicker = false },
            onPick = {
                jobPicker = false
                vm.selectJob(it)
            },
        )
    }
}

// ---- building blocks ----

@Composable
private fun CockpitCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Cockpit.Card,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Cockpit.Border),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(Locale.ROOT), color = Cockpit.Muted, fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
private fun Chip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.5f))) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = MinTouch),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cockpit.Accent, contentColor = Cockpit.OnAccent),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Cockpit.Text) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = MinTouch),
        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.6f) else Cockpit.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(text) }
}

/** Tappable row that opens a picker. */
@Composable
private fun SelectorRow(label: String, value: String?, placeholder: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick),
        color = Cockpit.Background, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Cockpit.Border),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Cockpit.Muted, fontSize = 12.sp)
                Text(
                    value ?: placeholder, color = if (value != null) Cockpit.Text else Cockpit.Muted, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("▾", color = if (enabled) Cockpit.Accent else Cockpit.Border, fontSize = 18.sp)
        }
    }
}

// ---- sections ----

@Composable
private fun Header(@Suppress("UNUSED_PARAMETER") state: Tool32State, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            RoundIconButton(R.drawable.ic_chevron_left, stringResource(R.string.settings2_back), onBack)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("BimmerData", color = Cockpit.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("Mini Tool32", color = Cockpit.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = Cockpit.Warning.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Cockpit.Warning.copy(alpha = 0.5f))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = Cockpit.Text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text("OK", color = Cockpit.Warning) }
        }
    }
}

/** The app's single connection (shared with the dashboard and the faults) and the ignition state. */
@Composable
private fun ConnectionCard(state: Tool32State) {
    CockpitCard {
        SectionLabel("Connection")
        ConnectionPill(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cp_ignition) + "  ", color = Cockpit.Muted, fontSize = 14.sp)
            when (state.ignition) {
                true -> Chip(stringResource(R.string.cp_ignition_on), ConnectionOk)
                false -> Chip(stringResource(R.string.cp_ignition_off), Cockpit.Warning)
                null -> Chip(stringResource(R.string.cp_ignition_unknown), Cockpit.Muted)
            }
        }
        Text(
            "Jobs run between two reads of the dashboard values, which go on.",
            color = Cockpit.Muted, fontSize = 13.sp,
        )
    }
}

@Composable
private fun EcuFilesCard(state: Tool32State, onImport: () -> Unit, onCancel: () -> Unit) {
    CockpitCard {
        SectionLabel("ECU files")
        val imp = state.import
        if (imp == null) {
            Text(
                if (state.ecuFiles.isEmpty()) "None imported yet" else "${state.ecuFiles.size} files (.prg / .grp)",
                color = if (state.ecuFiles.isEmpty()) Cockpit.Muted else Cockpit.Text, fontSize = 15.sp,
            )
            SecondaryButton(
                if (state.ecuFiles.isEmpty()) "Import Ecu folder" else "Re-import Ecu folder",
                enabled = !state.running, onClick = onImport, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val fraction = if (imp.bytesTotal > 0) (imp.bytesDone.toFloat() / imp.bytesTotal).coerceIn(0f, 1f) else 0f
            Text(
                if (imp.filesTotal == 0) imp.current else "${imp.filesDone} / ${imp.filesTotal} files · ${imp.bytesDone / 1_000_000} / ${imp.bytesTotal / 1_000_000} MB",
                color = Cockpit.Text, fontSize = 15.sp,
            )
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = Cockpit.Accent, trackColor = Cockpit.Border)
            Text(imp.current, color = Cockpit.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SecondaryButton("Cancel import", enabled = true, onClick = onCancel, modifier = Modifier.fillMaxWidth(), color = Cockpit.Danger)
        }
    }
}

@Composable
private fun JobCard(
    state: Tool32State,
    onPickSgbd: () -> Unit,
    onPickJob: () -> Unit,
    onArgs: (String) -> Unit,
    onRepeatCount: (String) -> Unit,
    onRun: () -> Unit,
    onRepeat: () -> Unit,
    onCancel: () -> Unit,
    onResolve: () -> Unit,
) {
    val canRun = state.sgbd != null && state.job != null && !state.running && state.import == null
    CockpitCard {
        SectionLabel("Job")
        SelectorRow("SGBD", state.sgbd, "Select ECU file", enabled = !state.running && state.import == null, onClick = onPickSgbd)
        if (state.sgbd?.endsWith(".grp", ignoreCase = true) == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Group file", color = Cockpit.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                SecondaryButton("Identify variant", enabled = !state.running, onClick = onResolve, color = Cockpit.Accent)
            }
        }
        SelectorRow(
            "Job", state.job,
            if (state.jobsLoading) "Loading jobs…" else if (state.jobs.isEmpty()) "No jobs loaded" else "Select job (${state.jobs.size})",
            enabled = state.jobs.isNotEmpty() && !state.running, onClick = onPickJob,
        )
        OutlinedTextField(
            value = state.args, onValueChange = onArgs, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Arguments (separated by ;)") }, enabled = !state.running,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        )
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cockpit.Accent, trackColor = Cockpit.Border)
            SecondaryButton("Cancel", enabled = true, onClick = onCancel, modifier = Modifier.fillMaxWidth(), color = Cockpit.Danger)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Run", enabled = canRun, onClick = onRun, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = state.repeatCount, onValueChange = onRepeatCount, singleLine = true, modifier = Modifier.width(84.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("N") },
                )
                SecondaryButton("Repeat", enabled = canRun && (state.repeatCount.toIntOrNull() ?: 0) > 0, onClick = onRepeat)
            }
        }
    }
}

@Composable
private fun ResultSummaryCard(result: JobResultUi, repeat: RepeatUi?, showFrames: Boolean, onShowFrames: (Boolean) -> Unit) {
    CockpitCard {
        SectionLabel("Result")
        Text(result.title, color = Cockpit.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                result.error != null -> Chip(if (result.error == "Cancelled") "CANCELLED" else "ERROR", Cockpit.Danger)
                result.jobStatus != null -> Chip(result.jobStatus, if (result.jobStatus == "OKAY") Cockpit.Accent else Cockpit.Warning)
                else -> Chip("no JOB_STATUS", Cockpit.Muted)
            }
            Text(String.format(Locale.ROOT, "%.1f ms · %d telegrams", result.elapsedMs, result.telegrams), color = Cockpit.Muted, fontSize = 14.sp)
        }
        result.error?.let { Text(it, color = Cockpit.Danger, fontSize = 14.sp) }
        repeat?.let { r ->
            HorizontalDivider(color = Cockpit.Border)
            Text("Repeat ${r.done} / ${r.total}", color = Cockpit.Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("avg", r.avgMs)
                Metric("min", r.minMs)
                Metric("max", r.maxMs)
                r.avgTelegramMs?.let { Metric("/tel", it) }
            }
        }
        HorizontalDivider(color = Cockpit.Border)
        Row(Modifier.fillMaxWidth().heightIn(min = MinTouch), verticalAlignment = Alignment.CenterVertically) {
            Text("Show raw frames", color = Cockpit.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = showFrames, onCheckedChange = onShowFrames)
        }
    }
}

@Composable
private fun RowScope.Metric(label: String, ms: Double) {
    Column {
        Text(String.format(Locale.ROOT, "%.1f", ms), color = Cockpit.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("$label ms", color = Cockpit.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun FrameRow(frame: FrameUi) {
    val (tag, color) = when (frame.direction) {
        FrameDirection.Tx -> "TX" to Cockpit.Accent
        FrameDirection.Echo -> "ECHO" to Cockpit.Muted
        FrameDirection.Rx -> "RX" to Cockpit.Text
        FrameDirection.Error -> "ERR" to Cockpit.Danger
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(String.format(Locale.ROOT, "%9.2f", frame.offsetMs), color = Cockpit.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(" $tag".padEnd(6), color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(frame.text, frame.hex.ifEmpty { null }).joinToString("  "),
            color = if (frame.direction == FrameDirection.Echo) Cockpit.Muted else color, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ResultSetCard(set: ResultSetUi, label: (String) -> String? = { null }) {
    var expanded by rememberSaveable(set.index) { mutableStateOf(set.index != 0) }
    CockpitCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(if (set.index == 0) "System results" else "Set ${set.index}")
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "▴" else "▾ ${set.entries.size}", color = Cockpit.Muted, fontSize = 14.sp)
        }
        if (expanded) {
            set.entries.forEach { e ->
                Column(Modifier.fillMaxWidth()) {
                    Text(e.name, color = Cockpit.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    label(e.name)?.let { Text(it, color = Cockpit.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            e.value, color = if (e.name == "JOB_STATUS") Cockpit.Accent else Cockpit.Text, fontSize = 16.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        e.unit?.let { Text("  $it", color = Cockpit.Muted, fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerDialog(
    title: String,
    items: List<String>,
    emptyText: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    /** Optional second line (job description); the search also looks into it. */
    subtitle: (String) -> String? = { null },
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, items, subtitle) {
        val q = query.trim()
        if (q.isEmpty()) items else items.filter { it.contains(q, ignoreCase = true) || subtitle(it)?.contains(q, ignoreCase = true) == true }
    }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f),
            color = Cockpit.Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Cockpit.Border),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Cockpit.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text("Close", color = Cockpit.Accent) }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text("${filtered.size} of ${items.size}", color = Cockpit.Muted, fontSize = 12.sp)
                if (items.isEmpty()) {
                    Text(emptyText, color = Cockpit.Muted, fontSize = 15.sp)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(filtered) { item ->
                        Column(Modifier.fillMaxWidth().heightIn(min = MinTouch).clickable { onPick(item) }.padding(horizontal = 8.dp, vertical = 12.dp)) {
                            Text(item, color = Cockpit.Text, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                            subtitle(item)?.let { Text(it, color = Cockpit.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        HorizontalDivider(color = Cockpit.Border)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
