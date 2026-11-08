/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.bluetooth.BluetoothAccess
import io.github.jonathangtd.bimmerdata.bluetooth.PairedDevice
import io.github.jonathangtd.bimmerdata.ediabas.deepobd.AdapterInfo
import io.github.jonathangtd.bimmerdata.ediabas.deepobd.CanMode
import io.github.jonathangtd.bimmerdata.gauges.currentLocale
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.launch

/**
 * Settings › Connection, self-contained (reads its own state): auto-connect on the cable, and the Deep OBD Bluetooth adapter (choice among the paired devices, connect at start,
 * adapter state and CAN mode).
 */
@Composable
fun ConnectionSettingsSection() {
    val context = LocalContext.current
    val store = remember { AppSettings.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard {
            SectionLabel(stringResource(R.string.settings_section_connection))
            SwitchRow(
                title = stringResource(R.string.settings_auto_connect),
                hint = stringResource(R.string.settings_auto_connect_hint),
                checked = settings.autoConnectOnCable,
                onChange = { on -> store.update { it.copy(autoConnectOnCable = on) } },
            )
        }
        BluetoothAdapterCard(settings.bluetooth, store)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BluetoothAdapterCard(bt: BluetoothSettings, store: AppSettings) {
    val context = LocalContext.current
    val repo = remember { LiveRepository.get(context) }
    val connection by repo.connection.collectAsStateWithLifecycle()
    var permissionTick by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
    val granted = remember(permissionTick) { BluetoothAccess.hasConnectPermission(context) }
    var picker by remember { mutableStateOf(false) }

    SettingsCard {
        SectionLabel(stringResource(R.string.bt_section))
        if (!granted) {
            Banner(stringResource(R.string.bt_permission_needed), Cockpit.Warning)
            SmallButton(stringResource(R.string.bt_allow), color = Cockpit.Accent) { BluetoothAccess.connectPermission?.let(permission::launch) }
        } else if (!BluetoothAccess.isEnabled(context)) {
            Banner(stringResource(R.string.bt_off), Cockpit.Warning)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                InfoRow(bt.name ?: stringResource(R.string.bt_adapter_none), bt.address, null)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(stringResource(if (bt.address == null) R.string.bt_choose else R.string.bt_change), enabled = granted, color = Cockpit.Accent) { picker = true }
            if (bt.address != null) {
                SmallButton(stringResource(R.string.bt_forget)) { store.updateBluetooth { it.copy(address = null, name = null) } }
            }
            SmallButton(stringResource(R.string.bt_pair)) { context.startActivity(BluetoothAccess.settingsIntent()) }
        }
        Hint(stringResource(R.string.bt_pair_hint))
        Divider()
        SwitchRow(
            title = stringResource(R.string.bt_connect_on_start),
            hint = stringResource(R.string.bt_connect_on_start_hint),
            checked = bt.connectOnStart,
            enabled = bt.address != null,
            onChange = { on -> store.updateBluetooth { it.copy(connectOnStart = on) } },
        )
        Divider()
        val onBluetooth = (connection as? LiveConnection.Connected)?.mode == LiveMode.Bluetooth
        AdapterStateBlock(repo, onBluetooth)
    }

    if (picker) {
        val devices = remember { BluetoothAccess.pairedDevices(context) }
        DevicePicker(devices, bt.address, onDismiss = { picker = false }) { d ->
            picker = false
            store.updateBluetooth { it.copy(address = d.address, name = d.name) }
        }
    }
}

/** Adapter type, firmware, voltage and CAN mode, read between two read cycles; CAN mode change. */
@Composable
private fun AdapterStateBlock(repo: LiveRepository, onBluetooth: Boolean) {
    val info by repo.adapterInfo.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var chooseMode by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<CanMode?>(null) }

    fun launchOp(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (ex: Exception) {
                error = ex.message ?: ex.toString()
            } finally {
                busy = false
            }
        }
    }

    Text(stringResource(R.string.bt_adapter_info), color = Cockpit.Text, fontSize = 16.sp)
    if (!onBluetooth) {
        Hint(stringResource(R.string.bt_info_connect_first))
        return
    }
    LaunchedEffect(Unit) { if (info == null) launchOp { repo.readAdapter() } }
    info?.let { AdapterInfoRows(it) }
    error?.let { Text(stringResource(R.string.bt_error, it), color = Cockpit.Danger, fontSize = 13.sp) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(stringResource(if (busy) R.string.bt_reading else R.string.bt_read), enabled = !busy) { launchOp { repo.readAdapter() } }
        SmallButton(stringResource(R.string.bt_change_can), enabled = !busy && info?.canModeCode != null, color = Cockpit.Warning) { chooseMode = true }
    }

    if (chooseMode) {
        CanModePicker(info?.canMode, onDismiss = { chooseMode = false }) { m ->
            chooseMode = false
            confirm = m
        }
    }
    confirm?.let { m ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Cockpit.Card,
            title = { Text(stringResource(R.string.bt_can_confirm_title), color = Cockpit.Text) },
            text = { Text(stringResource(R.string.bt_can_confirm_text, canModeName(m.code)), color = Cockpit.Text) },
            confirmButton = {
                TextButton(onClick = { confirm = null; launchOp { repo.writeCanMode(m) } }) {
                    Text(stringResource(R.string.bt_write), color = Cockpit.Warning)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.bt_cancel), color = Cockpit.Text) } },
        )
    }
}

@Composable
internal fun AdapterInfoRows(info: AdapterInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyValue(stringResource(R.string.bt_type), info.typeName)
        KeyValue(stringResource(R.string.bt_firmware), info.firmwareText)
        info.serialText?.let { KeyValue(stringResource(R.string.bt_serial), it) }
        info.voltage?.let { KeyValue(stringResource(R.string.bt_voltage), stringResource(R.string.bt_voltage_value, String.format(currentLocale(), "%.1f", it))) }
        info.canModeCode?.let { KeyValue(stringResource(R.string.bt_can_mode), canModeName(it)) }
        if (info.canMode?.reachesKLine == false) Banner(stringResource(R.string.bt_can_warning), Cockpit.Warning)
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, color = Cockpit.Muted, fontSize = 14.sp, modifier = Modifier.weight(0.4f))
        Text(value, color = Cockpit.Text, fontSize = 14.sp, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun canModeName(code: Int): String = when (CanMode.fromCode(code)) {
    CanMode.Auto -> stringResource(R.string.bt_can_auto)
    CanMode.KLineOnly -> stringResource(R.string.bt_can_kline)
    CanMode.DCan500 -> stringResource(R.string.bt_can_500)
    CanMode.Can100 -> stringResource(R.string.bt_can_100)
    null -> stringResource(R.string.bt_can_unknown, code)
}

/** The modes that make sense for a BMW: automatic, K-line only, D-CAN only. */
@Composable
private fun CanModePicker(current: CanMode?, onDismiss: () -> Unit, onPick: (CanMode) -> Unit) {
    var selected by remember { mutableStateOf(current ?: CanMode.Auto) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cockpit.Card,
        title = { Text(stringResource(R.string.bt_can_mode), color = Cockpit.Text) },
        text = {
            Column {
                for (m in listOf(CanMode.Auto, CanMode.KLineOnly, CanMode.DCan500)) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { selected = m },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == m, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Cockpit.Accent, unselectedColor = Cockpit.Muted),
                        )
                        Text(canModeName(m.code), color = Cockpit.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }, enabled = selected != current) { Text(stringResource(R.string.bt_change), color = Cockpit.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_cancel), color = Cockpit.Text) } },
    )
}

@Composable
private fun DevicePicker(devices: List<PairedDevice>, current: String?, onDismiss: () -> Unit, onPick: (PairedDevice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cockpit.Card,
        title = { Text(stringResource(R.string.bt_pick_title), color = Cockpit.Text) },
        text = {
            if (devices.isEmpty()) {
                Hint(stringResource(R.string.bt_pick_empty))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (d in devices) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(enabled = !d.lowEnergyOnly, role = Role.RadioButton) { onPick(d) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = d.address == current, onClick = null, enabled = !d.lowEnergyOnly,
                                colors = RadioButtonDefaults.colors(selectedColor = Cockpit.Accent, unselectedColor = Cockpit.Muted),
                            )
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(d.name, color = if (d.lowEnergyOnly) Cockpit.Muted else Cockpit.Text, fontSize = 15.sp)
                                val note = when {
                                    d.lowEnergyOnly -> stringResource(R.string.bt_pick_le)
                                    d.likelyObd -> stringResource(R.string.bt_pick_obd)
                                    else -> null
                                }
                                Text(listOfNotNull(d.address, note).joinToString(" · "), color = Cockpit.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_cancel), color = Cockpit.Text) } },
    )
}
