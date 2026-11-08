/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.SgbdChoices
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit

/** A car of the Faults tab a profile can be linked to. */
internal data class CarChoice(val id: String, val label: String)

/** One line of a profile: car and ECUs ("E90 330i · MSV70"). */
@Composable
internal fun profileSummary(p: Profile): String {
    val parts = listOfNotNull(p.vehicle.label.takeIf { it.isNotEmpty() }, p.ecus.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.uppercase() })
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.profile_no_details) }
}

/** First launch: no profile yet. */
@Composable
internal fun NoProfile(onCreate: () -> Unit, onImport: () -> Unit) {
    val label = stringResource(R.string.profile_create)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 32.dp).widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(112.dp)
                    .border(1.5.dp, Cockpit.Accent.copy(alpha = 0.35f), CircleShape)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Cockpit.Accent.copy(alpha = 0.14f))
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onCreate)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(label, color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.profile_create_hint), color = Cockpit.Muted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PillButton(label, onClick = onCreate, icon = R.drawable.ic_add, color = Cockpit.OnAccent, background = Cockpit.Accent)
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.profile_import), onClick = onImport, icon = R.drawable.ic_upload, color = Cockpit.Accent)
        }
    }
}

/** What can be done to one profile (Profiles tab): the "⋮" menu of its row. */
internal class ProfileActions(
    val onEdit: (String) -> Unit,
    val onDuplicate: (String) -> Unit,
    val onExport: (String) -> Unit,
    val onDelete: (String) -> Unit,
)

/**
 * Quick switch from the dashboard header: tap a profile to make it active, or create one; the
 * full management (edit, duplicate, export, import, delete) is the Profiles tab ([onManage]).
 */
@Composable
internal fun ProfileSwitchSheet(
    set: ProfileSet,
    onDismiss: () -> Unit,
    onActivate: (String) -> Unit,
    onNew: () -> Unit,
    onManage: () -> Unit,
) {
    CockpitSheet(onDismiss) {
        ProfileSwitchContent(set, onActivate, onNew, onManage)
    }
}

@Composable
internal fun ProfileSwitchContent(set: ProfileSet, onActivate: (String) -> Unit, onNew: () -> Unit, onManage: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SheetHeader(stringResource(R.string.ptab_switch_title))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 16.dp)) {
            items(set.profiles, key = { it.id }) { p ->
                ProfileRow(p, active = p.id == set.activeId, onActivate = onActivate, actions = null)
                HorizontalDivider(color = Cockpit.Border)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.profile_new), onClick = onNew, icon = R.drawable.ic_add, color = Cockpit.Accent, modifier = Modifier.weight(1f))
            PillButton(stringResource(R.string.ptab_manage), onClick = onManage, icon = R.drawable.ic_profile, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One profile: active marker, name (+ "Active" badge), car and ECUs, page counts. Tapping the row
 * activates it; with [actions] a "⋮" menu offers edit, duplicate, export and delete.
 */
@Composable
internal fun ProfileRow(p: Profile, active: Boolean, onActivate: (String) -> Unit, actions: ProfileActions?) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Cockpit.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(role = Role.Button) { onActivate(p.id) }
            .semantics { selected = active }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (actions == null) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(if (active) Cockpit.Accent else Cockpit.Border, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name, color = Cockpit.Text, fontSize = 17.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.ptab_active), color = Cockpit.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Cockpit.Accent.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(profileSummary(p), color = Cockpit.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.profile_pages, p.dashboard.phonePages.size, p.dashboard.carPages.size),
                color = Cockpit.Muted, fontSize = 12.sp,
            )
        }
        if (actions != null) Box {
            RoundIconButton(R.drawable.ic_more, stringResource(R.string.profile_actions, p.name), onClick = { menu = true }, background = Color.Transparent)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Cockpit.Card) {
                if (!active) ProfileMenuItem(R.drawable.ic_check, stringResource(R.string.ptab_activate)) { menu = false; onActivate(p.id) }
                ProfileMenuItem(R.drawable.ic_edit, stringResource(R.string.profile_edit)) { menu = false; actions.onEdit(p.id) }
                ProfileMenuItem(R.drawable.ic_pages, stringResource(R.string.profile_duplicate)) { menu = false; actions.onDuplicate(p.id) }
                ProfileMenuItem(R.drawable.ic_download, stringResource(R.string.profile_export)) { menu = false; actions.onExport(p.id) }
                ProfileMenuItem(R.drawable.ic_delete, stringResource(R.string.profile_delete)) { menu = false; actions.onDelete(p.id) }
            }
        }
    }
}

@Composable
private fun ProfileMenuItem(icon: Int, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = Cockpit.Text, fontSize = 15.sp) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = MinTouch),
    )
}

/**
 * New profile ([initial] null) or changes of one: name, car (series, model, optional link to a car
 * of the Faults tab) and ECUs, picked from the detected ones or the whole ECU folder.
 */
@Composable
internal fun ProfileEditorSheet(
    initial: Profile?,
    cars: List<CarChoice>,
    loadChoices: suspend () -> SgbdChoices,
    onDismiss: () -> Unit,
    onSave: (name: String, vehicle: ProfileVehicle, ecus: List<String>) -> Unit,
) {
    CockpitSheet(onDismiss) {
        ProfileEditorContent(initial, cars, loadChoices, onSave)
    }
}

@Composable
internal fun ProfileEditorContent(
    initial: Profile?,
    cars: List<CarChoice>,
    loadChoices: suspend () -> SgbdChoices,
    onSave: (name: String, vehicle: ProfileVehicle, ecus: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var series by remember { mutableStateOf(initial?.vehicle?.series.orEmpty()) }
    var model by remember { mutableStateOf(initial?.vehicle?.model.orEmpty()) }
    var carId by remember { mutableStateOf(initial?.vehicle?.vehicleId) }
    val ecus = remember { mutableStateListOf<String>().apply { addAll(initial?.ecus.orEmpty()) } }
    var nameError by remember { mutableStateOf(false) }
    val choices by produceState<SgbdChoices?>(null) { value = loadChoices() }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
        SheetHeader(
            stringResource(if (initial == null) R.string.profile_new else R.string.profile_edit),
            trailing = {
                PillButton(
                    stringResource(if (initial == null) R.string.profile_create_button else R.string.save),
                    onClick = {
                        if (name.isBlank()) nameError = true
                        else onSave(name, ProfileVehicle(series, model, carId), ecus.toList())
                    },
                    icon = R.drawable.ic_check, color = Cockpit.OnAccent, background = Cockpit.Accent,
                )
            },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(Profiles.MAX_NAME_LENGTH); nameError = false }, singleLine = true,
                label = { Text(stringResource(R.string.profile_name)) },
                placeholder = { Text(stringResource(R.string.profile_name_placeholder), color = Cockpit.Muted) },
                isError = nameError,
                supportingText = if (nameError) ({ Text(stringResource(R.string.profile_name_required)) }) else null,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = series, onValueChange = { series = it.take(Profiles.MAX_FIELD_LENGTH) }, singleLine = true,
                    label = { Text(stringResource(R.string.profile_series)) }, modifier = Modifier.weight(1f), colors = fieldColors(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it.take(Profiles.MAX_FIELD_LENGTH) }, singleLine = true,
                    label = { Text(stringResource(R.string.profile_model)) }, modifier = Modifier.weight(1f), colors = fieldColors(),
                )
            }
            if (cars.isNotEmpty()) {
                SectionTitle(stringResource(R.string.profile_car_link))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.profile_car_link_none), selected = carId == null, onClick = { carId = null })
                    cars.forEach { c -> Chip(c.label, selected = carId == c.id, onClick = { carId = c.id }) }
                }
                Text(stringResource(R.string.profile_car_link_hint), color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            SectionTitle(stringResource(R.string.profile_ecus))
            if (ecus.isEmpty()) {
                Text(stringResource(R.string.profile_ecus_none), color = Cockpit.Muted, fontSize = 13.sp)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ecus.forEach { e ->
                        Chip(e.uppercase(), selected = true, onClick = { ecus.remove(e) }, removeLabel = stringResource(R.string.profile_ecu_remove, e.uppercase()))
                    }
                }
            }
            Text(stringResource(R.string.profile_ecus_hint), color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SgbdStep(choices) { picked ->
                if (picked !in ecus && ecus.size < Profiles.MAX_ECUS) ecus.add(picked)
            }
        }
    }
}

/** Small selectable pill; with [removeLabel] it shows a "×" and tapping removes it. */
@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit, removeLabel: String? = null) {
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Cockpit.Accent.copy(alpha = 0.16f) else Cockpit.Background)
            .border(1.dp, if (selected) Cockpit.Accent.copy(alpha = 0.6f) else Cockpit.Border, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected; if (removeLabel != null) contentDescription = removeLabel }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (selected) Cockpit.Text else Cockpit.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (removeLabel != null) {
            Spacer(Modifier.width(6.dp))
            Text("×", color = Cockpit.Muted, fontSize = 16.sp)
        }
    }
}

/** Card picker: the profile's ECUs as tabs, and "Other ECU…" for the whole ECU folder. */
@Composable
internal fun ProfileEcuBar(ecus: List<String>, current: String, onPick: (String) -> Unit, onOther: () -> Unit) {
    Surface(color = Color.Transparent, border = BorderStroke(0.dp, Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ecus.forEach { e -> Chip(e.uppercase(), selected = e.equals(current, ignoreCase = true), onClick = { onPick(e) }) }
            Chip(stringResource(R.string.profile_other_ecu), selected = ecus.none { it.equals(current, ignoreCase = true) }, onClick = onOther)
        }
    }
}
