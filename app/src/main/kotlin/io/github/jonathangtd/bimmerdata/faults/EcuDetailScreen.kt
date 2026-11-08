/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.faults

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.SectionTitle
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.Fault
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultDetail
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.vehicle.DetailState
import io.github.jonathangtd.bimmerdata.vehicle.VehicleState
import java.text.NumberFormat

/** One ECU: identification, fault memory with details, read again / clear (with confirmation). */
@Composable
internal fun EcuDetailScreen(
    vehicle: Vehicle,
    ecu: VehicleEcu,
    state: VehicleState,
    onBack: () -> Unit,
    onReadAgain: () -> Unit,
    onClear: () -> Unit,
    onDetail: (Fault) -> Unit,
    onCancel: () -> Unit,
    onDismissMessage: () -> Unit,
    initiallyExpanded: Int? = null,
) {
    val faults = state.currentFaults?.firstOrNull { it.address == ecu.address }
    var confirmClear by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val canClear = ecu.sgbdAvailable && faults?.status == EcuFaultStatus.Faults

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "top") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(R.drawable.ic_chevron_left, stringResource(R.string.veh_back), onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ecu.name, color = Cockpit.Text, fontSize = 24.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        listOfNotNull(String.format("0x%02X", ecu.address), ecu.sgbd?.uppercase(), vehicle.label).joinToString(" · "),
                        color = Cockpit.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(faults)
            }
        }
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.veh_ecu_read_again), onReadAgain, icon = R.drawable.ic_reset, enabled = !state.busy && ecu.sgbdAvailable)
                if (canClear) {
                    PillButton(
                        stringResource(R.string.veh_ecu_clear), { confirmClear = true }, icon = R.drawable.ic_delete,
                        color = Cockpit.Danger, background = Cockpit.Danger.copy(alpha = 0.12f), enabled = !state.busy,
                    )
                }
            }
        }
        if (state.busy) item(key = "task") { TaskCard(state.task, onCancel) }
        state.message?.let { m -> item(key = "message") { MessageCard(messageText(m), m.error, onDismissMessage) } }
        if (!ecu.sgbdAvailable) {
            item(key = "nosgbd") {
                Text(
                    stringResource(R.string.veh_ecu_missing_sgbd, ecu.sgbd?.uppercase() ?: stringResource(R.string.veh_ecu_unknown_variant)),
                    color = Cockpit.Warning, fontSize = 13.sp,
                )
            }
        }

        item(key = "faults-title") { SectionTitle(stringResource(R.string.veh_ecu_faults)) }
        item(key = "faults") {
            when {
                faults == null -> Text(stringResource(R.string.veh_ecu_not_read), color = Cockpit.Muted, fontSize = 14.sp)
                faults.status == EcuFaultStatus.Ok -> Text(stringResource(R.string.veh_ecu_no_faults), color = FaultColors.Ok, fontSize = 15.sp)
                faults.status == EcuFaultStatus.NotResponding || faults.status == EcuFaultStatus.Error ->
                    Text(faults.message ?: stringResource(R.string.veh_status_silent), color = FaultColors.Silent, fontSize = 14.sp)
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    faults.message?.let { Text(it, color = Cockpit.Warning, fontSize = 13.sp) }
                    faults.faults.forEach { fault ->
                        val key = VehicleState.detailKey(vehicle.id, ecu.address, fault.code)
                        val open = expanded == fault.code
                        FaultCard(
                            fault = fault,
                            open = open,
                            detail = state.details[key],
                            canDetail = ecu.sgbdAvailable,
                            onToggle = {
                                expanded = if (open) null else fault.code
                                if (!open && state.details[key] !is DetailState.Loaded && !state.busy) onDetail(fault)
                            },
                        )
                    }
                }
            }
        }

        item(key = "info-title") { SectionTitle(stringResource(R.string.veh_ecu_info)) }
        item(key = "info") {
            KeyValueCard(
                listOfNotNull(
                    stringResource(R.string.veh_ecu_address) to String.format("0x%02X", ecu.address),
                    stringResource(R.string.veh_ecu_variant) to (ecu.sgbd?.uppercase() ?: stringResource(R.string.veh_ecu_unknown_variant)),
                    ecu.group?.let { stringResource(R.string.veh_ecu_group) to it.uppercase() },
                    ecu.bus?.let { stringResource(R.string.veh_ecu_bus) to it },
                    ecu.description?.let { "" to it },
                ),
            )
        }
        if (ecu.ident.isNotEmpty()) {
            item(key = "ident-title") { SectionTitle(stringResource(R.string.veh_ecu_ident)) }
            item(key = "ident") { KeyValueCard(ecu.ident.map { identLabel(it.name) to it.value }) }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.veh_clear_ecu_title),
            text = stringResource(R.string.veh_clear_ecu_text, ecu.name, ecu.sgbd?.uppercase() ?: "?"),
            confirm = stringResource(R.string.veh_clear_confirm),
            onConfirm = { confirmClear = false; onClear() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun FaultCard(fault: Fault, open: Boolean, detail: DetailState?, canDetail: Boolean, onToggle: () -> Unit) {
    Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Cockpit.Border)) {
        Column(
            Modifier.fillMaxWidth()
                .clickable(enabled = canDetail, role = Role.Button, onClickLabel = stringResource(if (open) R.string.veh_fault_hide else R.string.veh_fault_details), onClick = onToggle)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                CodeChip(fault.codeHex)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        fault.text.ifEmpty { stringResource(R.string.veh_fault_unknown) }, color = Cockpit.Text, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, lineHeight = 20.sp,
                    )
                    fault.symptom?.let { Text(it, color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp) }
                    fault.presence?.let { Text(it, color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp) }
                    fault.warning?.let { Text(it, color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp) }
                }
            }
            AnimatedVisibility(visible = open) {
                Column(Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = Cockpit.Border)
                    Spacer(Modifier.height(10.dp))
                    when (detail) {
                        null, DetailState.Loading -> CircularProgressIndicator(color = Cockpit.Accent, strokeWidth = 2.dp, modifier = Modifier.padding(4.dp).width(22.dp).height(22.dp))
                        is DetailState.Failed -> Text(stringResource(R.string.veh_fault_detail_failed, detail.message), color = Cockpit.Warning, fontSize = 13.sp)
                        is DetailState.Loaded -> DetailContent(detail.detail)
                    }
                    fault.hex?.let { Text(it, color = Cockpit.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DetailContent(d: FaultDetail) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val counters = listOfNotNull(
            d.frequency?.let { stringResource(R.string.veh_fault_frequency) to it.toString() },
            d.logisticCounter?.let { stringResource(R.string.veh_fault_logistic) to it.toString() },
            d.pcode?.let { stringResource(R.string.veh_fault_obd) to listOfNotNull(it, d.pcodeText).joinToString(" · ") },
        )
        counters.forEach { (k, v) -> KeyValueLine(k, v) }
        d.kinds.forEach { Text("· $it", color = Cockpit.Muted, fontSize = 13.sp) }
        d.occurrences.forEachIndexed { i, occ ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.veh_fault_occurrence, i + 1).uppercase(), color = Cockpit.Muted, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                occ.mileageKm?.let { Text(stringResource(R.string.veh_fault_mileage, numbers.format(it)), color = Cockpit.Accent, fontSize = 13.sp, fontFamily = BarlowCondensed) }
            }
            occ.values.forEach { v -> KeyValueLine(v.name, listOfNotNull(v.value, v.unit).joinToString(" ")) }
        }
    }
}

@Composable
private fun KeyValueLine(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, color = Cockpit.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(value, color = Cockpit.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun KeyValueCard(rows: List<Pair<String, String>>) {
    Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Cockpit.Border)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(color = Cockpit.Border)
                if (k.isEmpty()) {
                    Text(v, color = Cockpit.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(k, color = Cockpit.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
                        Text(v, color = Cockpit.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
