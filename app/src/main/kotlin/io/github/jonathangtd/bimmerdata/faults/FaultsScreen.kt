/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.faults

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import io.github.jonathangtd.bimmerdata.i18n.ecuDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.SectionTitle
import io.github.jonathangtd.bimmerdata.dashboard.ui.SegmentedTabs
import io.github.jonathangtd.bimmerdata.dashboard.ui.fieldColors
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.ScanMethod
import io.github.jonathangtd.bimmerdata.live.vehicle.TreeGroup
import io.github.jonathangtd.bimmerdata.live.vehicle.UserConfirmation
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleTables
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleTree
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.ui.ConnectionPill
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import io.github.jonathangtd.bimmerdata.vehicle.VehicleState
import io.github.jonathangtd.bimmerdata.vehicle.VehicleTask

/**
 * Faults tab: the vehicle view. Vehicle header (several cars), scan / read / clear-all actions,
 * then the ECU tree built automatically from the scan (by function, or by bus when the topology is
 * known) with a status pill per ECU; an ECU opens its detail (identification, faults, clear).
 */
@Composable
fun FaultsScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val repo = remember { VehicleRepository.get(context) }
    val state by repo.state.collectAsStateWithLifecycle()
    var openEcu by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val vehicle = state.current
    val ecu = openEcu?.let { a -> vehicle?.ecu(a) }
    BackHandler(enabled = ecu != null) { openEcu = null }

    Box(Modifier.fillMaxSize().background(Cockpit.Background)) {
        if (ecu != null && vehicle != null) {
            EcuDetailScreen(
                vehicle = vehicle,
                ecu = ecu,
                state = state,
                onBack = { openEcu = null },
                onReadAgain = { repo.readEcu(ecu.address) },
                onClear = { repo.clearEcu(ecu.address, UserConfirmation("FS_LOESCHEN ${ecu.sgbd}")) },
                onDetail = { fault -> repo.readDetail(ecu.address, fault) },
                onCancel = repo::cancel,
                onDismissMessage = repo::dismissMessage,
            )
        } else {
            VehicleOverview(
                state = state,
                onScan = repo::scan,
                onReadFaults = repo::readFaults,
                onClearAll = { confirmClearAll = true },
                onShare = {
                    val v = state.current ?: return@VehicleOverview
                    val text = faultReport(context, resources, v, state.currentFaults)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        .putExtra(Intent.EXTRA_SUBJECT, resources.getString(R.string.veh_report_title, v.label))
                    context.startActivity(Intent.createChooser(send, null))
                },
                onCancel = repo::cancel,
                connectionBar = { ConnectionPill(Modifier.fillMaxWidth()) },
                onSelectVehicle = repo::selectVehicle,
                onRename = repo::renameVehicle,
                onForget = repo::deleteVehicle,
                onSeries = repo::setSeries,
                seriesChoices = { repo.availableSeriesSgbds() },
                onOpenEcu = { openEcu = it },
                onDismissMessage = repo::dismissMessage,
            )
        }
    }

    if (confirmClearAll && vehicle != null) {
        ConfirmDialog(
            title = stringResource(R.string.veh_clear_all_title),
            text = stringResource(R.string.veh_clear_all_text, vehicle.ecus.size, vehicle.label),
            confirm = stringResource(R.string.veh_clear_confirm),
            onConfirm = {
                confirmClearAll = false
                repo.clearAll(UserConfirmation("FS_LOESCHEN_FUNKTIONAL ${vehicle.seriesSgbd}"))
            },
            onDismiss = { confirmClearAll = false },
        )
    }
}

@Composable
internal fun VehicleOverview(
    state: VehicleState,
    onScan: () -> Unit,
    onReadFaults: () -> Unit,
    onClearAll: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onSelectVehicle: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onForget: (String) -> Unit,
    onSeries: (String) -> Unit,
    seriesChoices: () -> List<String>,
    onOpenEcu: (Int) -> Unit,
    onDismissMessage: () -> Unit,
    /** The connection pill shown under the vehicle name. */
    connectionBar: @Composable () -> Unit = {},
) {
    val vehicle = state.current
    val faults = state.currentFaults?.associateBy { it.address }
    var byBus by rememberSaveable { mutableStateOf(false) }
    val busGroups = vehicle?.let { VehicleTree.byBus(it) }
    val groups: List<TreeGroup> = vehicle?.let { if (byBus && busGroups != null) busGroups else VehicleTree.byFunction(it) } ?: emptyList()

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            VehicleHeader(state, connectionBar, onSelectVehicle, onRename, onForget, onSeries, seriesChoices)
        }
        item(key = "actions") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    stringResource(if (vehicle == null) R.string.veh_scan else R.string.veh_rescan), onScan,
                    icon = R.drawable.ic_plug, color = Cockpit.OnAccent, background = if (state.busy) Cockpit.Border else Cockpit.Accent, enabled = !state.busy,
                )
                if (vehicle != null) {
                    PillButton(stringResource(R.string.veh_read_faults), onReadFaults, icon = R.drawable.ic_faults, enabled = !state.busy)
                    if (faults != null) PillButton(stringResource(R.string.veh_share), onShare, icon = R.drawable.ic_share, enabled = !state.busy)
                    if (faults?.values?.any { it.status == EcuFaultStatus.Faults } == true) {
                        PillButton(
                            stringResource(R.string.veh_clear_all), onClearAll, icon = R.drawable.ic_delete,
                            color = Cockpit.Danger, background = Cockpit.Danger.copy(alpha = 0.12f), enabled = !state.busy,
                        )
                    }
                }
            }
        }
        if (state.busy) item(key = "task") { TaskCard(state.task, onCancel) }
        state.message?.let { m -> item(key = "message") { MessageCard(messageText(m), m.error, onDismissMessage) } }

        if (vehicle == null) {
            if (!state.busy) item(key = "empty") { EmptyVehicle() }
            return@LazyColumn
        }

        item(key = "stats") { StatsRow(vehicle, faults) }
        if (vehicle.scanMethod == ScanMethod.Sequential) {
            item(key = "seq") { Text(stringResource(R.string.veh_sequential_note), color = Cockpit.Muted, fontSize = 13.sp) }
        }
        if (busGroups != null) {
            item(key = "view") {
                SegmentedTabs(
                    listOf(stringResource(R.string.veh_by_function), stringResource(R.string.veh_by_bus)),
                    selected = if (byBus) 1 else 0, onSelect = { byBus = it == 1 },
                )
            }
        }
        for (group in groups) {
            item(key = "g-${group.category}-${group.bus}") {
                SectionTitle(if (group.bus != null) busTitle(group.bus) else group.category?.let { categoryTitle(it) } ?: busTitle(null))
            }
            item(key = "c-${group.category}-${group.bus}") {
                Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Cockpit.Border)) {
                    Column {
                        group.ecus.forEachIndexed { i, ecu ->
                            if (i > 0) HorizontalDivider(color = Cockpit.Border, modifier = Modifier.padding(start = 56.dp))
                            EcuRow(ecu, faults?.get(ecu.address), faults != null) { onOpenEcu(ecu.address) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleHeader(
    state: VehicleState,
    connectionBar: @Composable () -> Unit,
    onSelectVehicle: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onForget: (String) -> Unit,
    onSeries: (String) -> Unit,
    seriesChoices: () -> List<String>,
) {
    val context = LocalContext.current
    val vehicle = state.current
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    var series by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    vehicle?.label ?: stringResource(R.string.veh_title), color = Cockpit.Text, fontSize = 26.sp,
                    fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (vehicle != null) {
                    val line = listOfNotNull(
                        vehicle.series,
                        (vehicle.vin ?: vehicle.vinShort)?.let { stringResource(R.string.veh_vin, it) },
                        vehicle.scannedAtMs?.let { stringResource(R.string.veh_scanned_at, formatTime(context, it)) },
                    ).joinToString(" · ")
                    Text(line, color = Cockpit.Muted, fontSize = 13.sp, maxLines = 2)
                }
            }
            if (state.garage.vehicles.isNotEmpty()) {
                Box {
                    RoundIconButton(R.drawable.ic_more, stringResource(R.string.veh_menu), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Cockpit.Card) {
                        if (state.garage.vehicles.size > 1) {
                            Text(
                                stringResource(R.string.veh_switch).uppercase(), color = Cockpit.Muted, fontSize = 11.sp, letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            state.garage.vehicles.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v.label, color = if (v.id == vehicle?.id) Cockpit.Accent else Cockpit.Text) },
                                    onClick = { menu = false; onSelectVehicle(v.id) },
                                    enabled = !state.busy,
                                )
                            }
                            HorizontalDivider(color = Cockpit.Border)
                        }
                        if (vehicle != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.veh_rename)) }, onClick = { menu = false; rename = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.veh_series)) }, onClick = { menu = false; series = true })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.veh_forget), color = Cockpit.Danger) },
                                onClick = { menu = false; forget = true }, enabled = !state.busy,
                            )
                        }
                    }
                }
            }
        }
        // the app's single connection, shared with the dashboard and Tool32
        connectionBar()
    }

    if (rename && vehicle != null) {
        var text by remember { mutableStateOf(vehicle.label) }
        AlertDialog(
            onDismissRequest = { rename = false },
            containerColor = Cockpit.Card,
            title = { Text(stringResource(R.string.veh_rename_title)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true, colors = fieldColors()) },
            confirmButton = { TextButton(onClick = { rename = false; onRename(vehicle.id, text) }) { Text(stringResource(R.string.veh_save)) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.veh_cancel)) } },
        )
    }
    if (forget && vehicle != null) {
        ConfirmDialog(
            title = stringResource(R.string.veh_forget_title, vehicle.label),
            text = stringResource(R.string.veh_forget_text),
            confirm = stringResource(R.string.veh_forget_confirm),
            onConfirm = { forget = false; onForget(vehicle.id) },
            onDismiss = { forget = false },
        )
    }
    if (series && vehicle != null) {
        val choices = remember { seriesChoices() }
        AlertDialog(
            onDismissRequest = { series = false },
            containerColor = Cockpit.Card,
            title = { Text(stringResource(R.string.veh_series)) },
            text = {
                Column {
                    choices.forEach { sgbd ->
                        val label = "${sgbd.uppercase()} · ${VehicleTables.FamilyLabels[sgbd] ?: ""}"
                        Text(
                            if (sgbd == vehicle.seriesSgbd) stringResource(R.string.veh_series_auto, label) else label,
                            color = if (sgbd == vehicle.seriesSgbd) Cockpit.Accent else Cockpit.Text, fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) { series = false; onSeries(sgbd) }
                                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { series = false }) { Text(stringResource(R.string.veh_cancel)) } },
        )
    }
}

@Composable
internal fun TaskCard(task: VehicleTask, onCancel: () -> Unit) {
    Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Cockpit.Border)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(taskText(task), color = Cockpit.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                TextButton(onClick = onCancel) { Text(stringResource(R.string.veh_cancel), color = Cockpit.Accent) }
            }
            Spacer(Modifier.height(6.dp))
            val p = taskProgress(task)
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp), color = Cockpit.Accent, trackColor = Cockpit.Border)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp), color = Cockpit.Accent, trackColor = Cockpit.Border)
            }
        }
    }
}

@Composable
internal fun MessageCard(text: String, error: Boolean, onDismiss: () -> Unit) {
    val color = if (error) Cockpit.Danger else Cockpit.Accent
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.35f))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Cockpit.Text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            RoundIconButton(R.drawable.ic_close, stringResource(R.string.veh_dismiss), onDismiss, background = Color.Transparent, tint = Cockpit.Muted)
        }
    }
}

@Composable
private fun EmptyVehicle() {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_faults), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.veh_none_title), color = Cockpit.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = BarlowCondensed)
        Spacer(Modifier.height(8.dp))
        val prepare = io.github.jonathangtd.bimmerdata.ui.ConnectHints.prepare()
        val text = if (prepare == null) stringResource(R.string.fb3_veh_none_sim) else prepare + " " + stringResource(R.string.veh_none_text)
        Text(text, color = Cockpit.Muted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatsRow(vehicle: Vehicle, faults: Map<Int, EcuFaults>?) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.veh_stat_ecus), vehicle.ecus.size.toString(), Cockpit.Text, Modifier.weight(1f))
            StatTile(
                stringResource(R.string.veh_stat_faults), faults?.values?.count { it.status == EcuFaultStatus.Faults }?.toString() ?: "–",
                FaultColors.Faults, Modifier.weight(1f),
            )
            StatTile(
                stringResource(R.string.veh_stat_silent),
                faults?.values?.count { it.status == EcuFaultStatus.NotResponding || it.status == EcuFaultStatus.Error }?.toString() ?: "–",
                FaultColors.Silent, Modifier.weight(1f),
            )
        }
        val readAt = faults?.values?.maxOfOrNull { it.readAtMs }?.takeIf { it > 0 }
        Text(
            readAt?.let { stringResource(R.string.veh_faults_read_at, formatTime(context, it)) } ?: stringResource(R.string.veh_faults_not_read),
            color = Cockpit.Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier) {
    Surface(modifier, color = Cockpit.Card, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Cockpit.Border)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, color = color, fontSize = 26.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold)
            Text(label, color = Cockpit.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EcuRow(ecu: VehicleEcu, faults: EcuFaults?, read: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AddressChip(ecu.address, Modifier.width(34.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(ecu.name, color = Cockpit.Text, fontSize = 16.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val translator = io.github.jonathangtd.bimmerdata.i18n.rememberTranslator()
            val tv = io.github.jonathangtd.bimmerdata.i18n.translationVersion()
            val description = androidx.compose.runtime.remember(ecu, tv) { translator.ecuDescription(ecu, null) }
            val sub = listOfNotNull(ecu.sgbd?.uppercase() ?: stringResource(R.string.veh_ecu_unknown_variant), description).joinToString(" · ")
            Text(sub, color = if (ecu.sgbdAvailable) Cockpit.Muted else Cockpit.Warning, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (read || faults != null) StatusPill(faults)
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cockpit.Card,
        title = { Text(title, color = Cockpit.Text) },
        text = { Text(text, color = Cockpit.Muted, fontSize = 15.sp, lineHeight = 21.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Cockpit.Danger, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.veh_cancel), color = Cockpit.Text) } },
    )
}
