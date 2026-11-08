/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.faults

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuCategory
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleScanner
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.vehicle.VehicleMessage
import io.github.jonathangtd.bimmerdata.vehicle.VehicleTask
import java.util.Locale

/** Status colors of the vehicle view (night cockpit: no pure green, a soft one). */
internal object FaultColors {
    val Ok = Color(0xFF4CD08A)
    val Faults = Cockpit.Warning
    val Silent = Cockpit.Danger
    val Unknown = Cockpit.Muted
}

/** Small rounded status label: OK / n faults / no answer / not read. */
@Composable
internal fun StatusPill(faults: EcuFaults?, modifier: Modifier = Modifier) {
    val (text, color) = when (faults?.status) {
        null -> stringResource(R.string.veh_status_unknown) to FaultColors.Unknown
        EcuFaultStatus.Ok -> stringResource(R.string.veh_status_ok) to FaultColors.Ok
        EcuFaultStatus.Faults -> pluralStringResource(R.plurals.veh_status_faults, faults.faults.size, faults.faults.size) to FaultColors.Faults
        EcuFaultStatus.NotResponding -> stringResource(R.string.veh_status_silent) to FaultColors.Silent
        EcuFaultStatus.Error -> stringResource(R.string.veh_status_error) to FaultColors.Silent
    }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** "0x12" in a small monospace chip. */
@Composable
internal fun AddressChip(address: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Cockpit.Border)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(String.format("%02X", address), color = Cockpit.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Fault code chip ("2F08"), colored like a fault. */
@Composable
internal fun CodeChip(code: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(FaultColors.Faults.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(code, color = FaultColors.Faults, fontSize = 14.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun categoryTitle(category: EcuCategory): String = stringResource(
    when (category) {
        EcuCategory.Gateway -> R.string.veh_cat_gateway
        EcuCategory.Powertrain -> R.string.veh_cat_powertrain
        EcuCategory.Chassis -> R.string.veh_cat_chassis
        EcuCategory.Body -> R.string.veh_cat_body
        EcuCategory.Climate -> R.string.veh_cat_climate
        EcuCategory.Infotainment -> R.string.veh_cat_infotainment
        EcuCategory.Safety -> R.string.veh_cat_safety
        EcuCategory.Other -> R.string.veh_cat_other
    },
)

@Composable
internal fun busTitle(bus: String?): String = when (bus) {
    null -> stringResource(R.string.veh_bus_none)
    "PT-CAN" -> stringResource(R.string.veh_bus_ptcan)
    "K-CAN" -> stringResource(R.string.veh_bus_kcan)
    "MOST" -> stringResource(R.string.veh_bus_most)
    "SI" -> stringResource(R.string.veh_bus_si)
    else -> bus
}

/** Label of an identification result name (IDENT / IDENT_FUNKTIONAL). */
@Composable
internal fun identLabel(name: String): String = when (name) {
    "ID_BMW_NR" -> stringResource(R.string.veh_id_part)
    "ID_HW_NR" -> stringResource(R.string.veh_id_hw)
    "ID_SW_NR_FSV" -> stringResource(R.string.veh_id_sw_fsv)
    "ID_SW_NR_OSV" -> stringResource(R.string.veh_id_sw_osv)
    "ID_SW_NR_MCV" -> stringResource(R.string.veh_id_sw_mcv)
    "ID_SW_NR_RES" -> stringResource(R.string.veh_id_sw_res)
    "ID_COD_INDEX" -> stringResource(R.string.veh_id_coding)
    "ID_DIAG_INDEX" -> stringResource(R.string.veh_id_diag)
    "ID_VAR_INDEX" -> stringResource(R.string.veh_id_variant)
    "ID_DATUM" -> stringResource(R.string.veh_id_date)
    "ID_LIEF_NR" -> stringResource(R.string.veh_id_supplier_nr)
    "ID_LIEF_TEXT" -> stringResource(R.string.veh_id_supplier)
    "SERIENNUMMER" -> stringResource(R.string.veh_id_serial)
    else -> name
}

/** Text of the running operation. */
@Composable
internal fun taskText(task: VehicleTask): String = when (task) {
    VehicleTask.Idle -> ""
    VehicleTask.Connecting -> stringResource(R.string.veh_task_connecting)
    is VehicleTask.Scanning -> {
        val p = task.progress
        when (p?.step) {
            null -> stringResource(R.string.veh_task_connecting)
            VehicleScanner.Step.Identifying -> stringResource(R.string.veh_task_identifying, p.detail ?: "", p.done.coerceAtLeast(1))
            VehicleScanner.Step.Vin -> stringResource(R.string.veh_task_vin)
            VehicleScanner.Step.Variants -> stringResource(R.string.veh_task_variants, p.done, p.total, p.detail ?: "")
            VehicleScanner.Step.Buses -> stringResource(R.string.veh_task_buses, p.detail ?: "")
            VehicleScanner.Step.Sequential -> stringResource(R.string.veh_task_sequential, p.detail ?: "", p.done, p.total)
        }
    }
    is VehicleTask.ReadingFaults ->
        if (task.ecuName != null) stringResource(R.string.veh_task_faults_ecu, task.done, task.total, task.ecuName)
        else stringResource(R.string.veh_task_faults, task.done, task.total)
    is VehicleTask.ReadingEcu -> stringResource(R.string.veh_task_ecu)
    is VehicleTask.Clearing -> stringResource(R.string.veh_task_clearing)
    is VehicleTask.ReadingDetail -> stringResource(R.string.veh_task_detail)
}

/** 0..1 progress of the running operation, null if indeterminate. */
internal fun taskProgress(task: VehicleTask): Float? = when (task) {
    is VehicleTask.Scanning -> task.progress?.takeIf { it.total > 0 && it.step != VehicleScanner.Step.Identifying }?.let { it.done.toFloat() / it.total }
    is VehicleTask.ReadingFaults -> task.total.takeIf { it > 0 }?.let { task.done.toFloat() / it }
    else -> null
}

@Composable
internal fun messageText(m: VehicleMessage): String {
    val d = m.detail ?: ""
    return when (m.kind) {
        VehicleMessage.Kind.NoEcuFiles -> stringResource(R.string.veh_msg_no_ecu_files)
        VehicleMessage.Kind.NoCable -> stringResource(R.string.veh_msg_no_cable)
        VehicleMessage.Kind.UsbDenied -> stringResource(R.string.veh_msg_usb_denied)
        VehicleMessage.Kind.CableBusy -> stringResource(R.string.veh_msg_cable_busy)
        VehicleMessage.Kind.ConnectFailed -> stringResource(R.string.veh_msg_connect_failed, d)
        VehicleMessage.Kind.ScanFailed -> stringResource(R.string.veh_msg_scan_failed, d)
        VehicleMessage.Kind.ScanCancelled -> stringResource(R.string.veh_msg_scan_cancelled)
        VehicleMessage.Kind.ReadFailed -> stringResource(R.string.veh_msg_failed, d)
        VehicleMessage.Kind.Cleared -> if (m.detail != null) stringResource(R.string.veh_msg_cleared_ecu, d) else stringResource(R.string.veh_msg_cleared)
        VehicleMessage.Kind.ClearFailed -> stringResource(R.string.veh_msg_clear_failed, d)
        VehicleMessage.Kind.NewVehicle -> stringResource(R.string.veh_msg_new_vehicle, d)
        VehicleMessage.Kind.Cancelled -> stringResource(R.string.veh_msg_cancelled)
    }
}

internal fun formatTime(context: Context, ms: Long): String =
    DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

/** Plain-text report of the last fault read, for sharing (mail, messenger, notes). */
internal fun faultReport(context: Context, res: Resources, vehicle: Vehicle, faults: List<EcuFaults>?): String = buildString {
    appendLine(res.getString(R.string.veh_report_title, vehicle.label))
    listOfNotNull(vehicle.series, vehicle.vin ?: vehicle.vinShort).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { appendLine(it) }
    faults?.maxOfOrNull { it.readAtMs }?.takeIf { it > 0 }?.let { appendLine(formatTime(context, it)) }
    appendLine()
    val byAddress = faults?.associateBy { it.address } ?: emptyMap()
    for (ecu in vehicle.ecus) {
        val f = byAddress[ecu.address]
        val status = when (f?.status) {
            null -> res.getString(R.string.veh_status_unknown)
            EcuFaultStatus.Ok -> res.getString(R.string.veh_report_no_faults)
            EcuFaultStatus.Faults -> res.getQuantityString(R.plurals.veh_status_faults, f.faults.size, f.faults.size)
            EcuFaultStatus.NotResponding -> res.getString(R.string.veh_status_silent)
            EcuFaultStatus.Error -> res.getString(R.string.veh_status_error) + (f.message?.let { " ($it)" } ?: "")
        }
        appendLine(String.format(Locale.ROOT, "%02X %s (%s): %s", ecu.address, ecu.name, ecu.sgbd ?: "?", status))
        f?.faults?.map { io.github.jonathangtd.bimmerdata.live.i18n.Translations.shared.fault(ecu.sgbd, it) }?.forEach { fault ->
            append("   ").append(fault.codeHex).append(' ').append(fault.text.ifEmpty { res.getString(R.string.veh_fault_unknown) })
            fault.symptom?.let { append(" · ").append(it) }
            fault.presence?.let { append(" · ").append(it) }
            appendLine()
        }
    }
}
