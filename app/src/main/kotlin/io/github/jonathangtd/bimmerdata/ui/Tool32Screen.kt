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
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd.FrameDirection
import java.util.Locale

private val MinTouch = 48.dp

/** "Mini Tool32": pick an SGBD and a job, run it, look at results, frames and timings. */
@Composable
fun Tool32Screen(vm: Tool32ViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFolder(uri)
    }
    var sgbdPicker by rememberSaveable { mutableStateOf(false) }
    var jobPicker by rememberSaveable { mutableStateOf(false) }
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Cockpit.Background),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 12.dp, bottom = insets.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state) }
        state.message?.let { msg -> item { MessageBanner(msg, vm::dismissMessage) } }
        item { ConnectionCard(state, vm::setMode, vm::connect, vm::disconnect) }
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
            items(result.sets, key = { it.index }) { set -> ResultSetCard(set) }
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
            emptyText = if (state.jobsLoading) "Loading jobs…" else "No jobs loaded (connect first).",
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
private fun Header(state: Tool32State) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("BimmerData", color = Cockpit.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("Mini Tool32", color = Cockpit.Muted, fontSize = 14.sp)
        }
        val (color, text) = when (val c = state.connection) {
            is ConnectionState.Connected -> Cockpit.Accent to "Online"
            ConnectionState.Connecting -> Cockpit.Warning to "Connecting"
            is ConnectionState.Failed -> Cockpit.Danger to "Error"
            ConnectionState.Disconnected -> Cockpit.Muted to "Offline"
        }
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 14.sp)
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

@Composable
private fun ConnectionCard(state: Tool32State, onMode: (ConnectionMode) -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    CockpitCard {
        SectionLabel("Connection")
        val modes = listOf(ConnectionMode.Usb to "USB cable", ConnectionMode.Simulation to "Simulation")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { onMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    modifier = Modifier.heightIn(min = MinTouch),
                    enabled = !state.running,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Cockpit.Accent.copy(alpha = 0.16f), activeContentColor = Cockpit.Accent,
                        inactiveContainerColor = Cockpit.Card, inactiveContentColor = Cockpit.Muted,
                        activeBorderColor = Cockpit.Border, inactiveBorderColor = Cockpit.Border,
                    ),
                    icon = {},
                ) { Text(label) }
            }
        }
        when (val c = state.connection) {
            is ConnectionState.Connected -> {
                Text(c.label, color = Cockpit.Text, fontSize = 15.sp)
                val ign = state.ignition
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ignition (DSR)  ", color = Cockpit.Muted, fontSize = 14.sp)
                    when (ign) {
                        true -> Chip("KL15 on", Cockpit.Accent)
                        false -> Chip("KL15 off", Cockpit.Warning)
                        null -> Chip("unknown", Cockpit.Muted)
                    }
                }
            }
            is ConnectionState.Failed -> Text(c.message, color = Cockpit.Danger, fontSize = 14.sp)
            ConnectionState.Connecting -> Text("Connecting…", color = Cockpit.Warning, fontSize = 14.sp)
            ConnectionState.Disconnected -> Text(
                if (state.mode == ConnectionMode.Usb) "K+DCAN cable via USB-C OTG, ignition on." else "Simulated MSV70-like ECU with K-line timing.",
                color = Cockpit.Muted, fontSize = 14.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.connection is ConnectionState.Connected) {
                SecondaryButton("Disconnect", enabled = !state.running, onClick = onDisconnect, modifier = Modifier.weight(1f))
            } else {
                PrimaryButton("Connect", enabled = state.connection != ConnectionState.Connecting, onClick = onConnect, modifier = Modifier.weight(1f))
            }
        }
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
    val connected = state.connection is ConnectionState.Connected
    val canRun = connected && state.sgbd != null && state.job != null && !state.running && state.import == null
    CockpitCard {
        SectionLabel("Job")
        SelectorRow("SGBD", state.sgbd, "Select ECU file", enabled = !state.running && state.import == null, onClick = onPickSgbd)
        if (state.sgbd?.endsWith(".grp", ignoreCase = true) == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Group file", color = Cockpit.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                SecondaryButton("Identify variant", enabled = connected && !state.running, onClick = onResolve, color = Cockpit.Accent)
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
private fun ResultSetCard(set: ResultSetUi) {
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
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            e.value, color = if (e.name == "JOB_STATUS") Cockpit.Accent else Cockpit.Text, fontSize = 16.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        e.unit?.let { Text("  $it", color = Cockpit.Muted, fontSize = 14.sp) }
                        Spacer(Modifier.weight(1f))
                        Text(e.type, color = Cockpit.Border, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerDialog(title: String, items: List<String>, emptyText: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, items) {
        if (query.isBlank()) items else items.filter { it.contains(query.trim(), ignoreCase = true) }
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
                        Text(
                            item, color = Cockpit.Text, fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).clickable { onPick(item) }.padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                        HorizontalDivider(color = Cockpit.Border)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
