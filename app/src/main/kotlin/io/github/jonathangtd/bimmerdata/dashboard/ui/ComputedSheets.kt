/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.DashboardViewModel
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.live.ComputedKind
import io.github.jonathangtd.bimmerdata.live.ComputedValues
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.profiles.ComputedValue
import io.github.jonathangtd.bimmerdata.profiles.Computeds
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Formulas of ECU [ecu] above its values in the card picker: one chip per formula of the active
 * profile (tap = edit), and « New power value ». The formulas themselves are picked in the list
 * below like any value of the ECU. [onChanged] gets the id of a deleted formula (null otherwise).
 */
@Composable
internal fun FormulaBar(vm: DashboardViewModel, ecu: String, version: Int, onChanged: (removedId: String?) -> Unit) {
    var editing by remember { mutableStateOf<ComputedValue?>(null) }
    val all = remember(version) { vm.computedValues() }
    val mine = all.filter { it.ecu.equals(ecu, ignoreCase = true) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.computed_formulas), color = Cockpit.Muted, fontSize = 13.sp)
        mine.forEach { c -> Chip("✎ " + c.label.ifBlank { c.kind }, selected = false, onClick = { editing = c }) }
        if (all.size < Computeds.MAX) {
            Chip("+ " + stringResource(R.string.computed_add_power), selected = false, onClick = {
                editing = ComputedValue(key = Computeds.newKey(all, ComputedKind.Power), kind = ComputedKind.Power.name, ecu = ecu.lowercase())
            })
        }
    }
    editing?.let { c ->
        ComputedEditorDialog(
            vm, initial = c, isNew = all.none { it.key == c.key },
            onDismiss = { editing = null },
            onChanged = { deleted ->
                editing = null
                onChanged(if (deleted) c.id else null)
            },
        )
    }
}

/** Name and inputs (values of the formula's ECU) of a power value; save, or delete (with the cards showing it, after confirmation). */
@Composable
private fun ComputedEditorDialog(
    vm: DashboardViewModel, initial: ComputedValue, isNew: Boolean,
    onDismiss: () -> Unit, onChanged: (deleted: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val defaultName = stringResource(R.string.computed_kind_power)
    var name by remember { mutableStateOf(initial.label.ifBlank { if (isNew) defaultName else "" }) }
    var torque by remember { mutableStateOf(initial.inputs[ComputedKind.TORQUE]) }
    var rpm by remember { mutableStateOf(initial.inputs[ComputedKind.RPM]) }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val labels by produceState<Map<String, String>>(emptyMap(), torque, rpm) {
        value = listOfNotNull(torque, rpm).associateWith { id ->
            vm.measurements(id.substringBefore('/')).firstOrNull { it.id == id }?.label ?: id
        }
    }
    val canSave = torque != null && rpm != null
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Cockpit.Card,
        title = { Text(stringResource(R.string.computed_edit_title), color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(ComputedValues.MAX_LABEL_LENGTH) }, singleLine = true,
                    label = { Text(stringResource(R.string.computed_name)) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                )
                Spacer(Modifier.height(12.dp))
                InputRow(stringResource(R.string.computed_torque), torque?.let { labels[it] ?: it }) { picking = ComputedKind.TORQUE }
                InputRow(stringResource(R.string.computed_rpm), rpm?.let { labels[it] ?: it }) { picking = ComputedKind.RPM }
                Text(stringResource(R.string.computed_formula), color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = MinTouch).padding(top = 4.dp)) {
                        Text(stringResource(R.string.computed_delete), color = Cockpit.Danger)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val c = initial.copy(
                        label = name.trim(),
                        inputs = mapOf(ComputedKind.TORQUE to torque!!, ComputedKind.RPM to rpm!!),
                    )
                    scope.launch { if (vm.saveComputed(c)) onChanged(false) }
                },
                modifier = Modifier.heightIn(min = MinTouch),
            ) { Text(stringResource(R.string.computed_save), color = if (canSave) Cockpit.Accent else Cockpit.Muted) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.cancel), color = Cockpit.Muted) }
        },
    )
    picking?.let { role ->
        InputPickerDialog(
            vm, initial.ecu, role,
            onDismiss = { picking = null },
            onPick = { id ->
                if (role == ComputedKind.TORQUE) torque = id else rpm = id
                picking = null
            },
        )
    }
    if (confirmDelete) {
        val (cards, series) = vm.computedUses(initial.key)
        val shown = initial.label.ifBlank { initial.kind }
        ConfirmDialog(
            text = if (cards > 0 || series > 0) stringResource(R.string.computed_delete_used, shown, cards, series) else stringResource(R.string.computed_delete_plain, shown),
            confirm = stringResource(R.string.computed_delete),
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    vm.deleteComputed(initial.key)
                    onChanged(true)
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun InputRow(title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Cockpit.Muted, fontSize = 12.sp)
            Text(value ?: stringResource(R.string.computed_choose), color = if (value != null) Cockpit.Text else Cockpit.Accent, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Cockpit.Muted)
    }
}

/** True when [unit] fits input [role]: N·m for a torque, rpm / 1/min for an engine speed. */
internal fun unitFits(role: String, unit: String): Boolean {
    val u = unit.lowercase(Locale.ROOT).replace(" ", "").replace("·", "").replace(".", "").replace("*", "")
    return when (role) {
        ComputedKind.TORQUE -> u == "nm"
        ComputedKind.RPM -> u in setOf("rpm", "1/min", "u/min", "tr/min", "min-1", "/min")
        else -> false
    }
}

/** Values of [ecu] to use as input [role] (a formula takes its inputs from its own ECU); those with a fitting unit first. */
@Composable
private fun InputPickerDialog(vm: DashboardViewModel, ecu: String, role: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val all by produceState<List<Measurement>?>(null, ecu) {
        value = vm.measurements(ecu).filterNot { ComputedValues.isComputed(it) }.sortedByDescending { unitFits(role, it.unit) }
    }
    val selected = remember { mutableStateListOf<String>() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Cockpit.Card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f)) {
            Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
                SheetHeader(stringResource(if (role == ComputedKind.TORQUE) R.string.computed_torque else R.string.computed_rpm), onBack = onDismiss)
                MeasurementList(all, GaugeType.BigNumber, selected, onConfirm = { selected.firstOrNull()?.let(onPick) })
            }
        }
    }
}
