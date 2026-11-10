/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.cars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.MinTouch
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.faults.formatTime
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit

/** What the Profiles tab's car card can do. */
internal class CarCardActions(
    val onDetect: () -> Unit = {},
    val onEcus: () -> Unit = {},
    val onRename: () -> Unit = {},
)

/** "E8x/E9x 330i · VIN …AB12345" (or "VIN not read yet"). */
@Composable
internal fun carSubtitle(c: Vehicle): String {
    val vin = (c.vinShort ?: c.vin?.takeLast(7))?.let { stringResource(R.string.cars_vin, it) } ?: stringResource(R.string.cars_vin_unknown)
    return listOfNotNull(listOfNotNull(c.series, c.model).joinToString(" ").takeIf { it.isNotEmpty() }, vin).joinToString(" · ")
}

/** The car list of the Profiles tab: the cars ([shownId] checked, a tap shows another), then "Add a car" and "My cars". */
internal class CarPicker(
    val cars: List<Vehicle> = emptyList(),
    val onSelect: (String) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onManage: () -> Unit = {},
)

/** The drop-down list of [picker] (anchored to the enclosing Box). */
@Composable
internal fun CarPickerMenu(expanded: Boolean, onDismiss: () -> Unit, picker: CarPicker, shownId: String?) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = Cockpit.Card) {
        picker.cars.forEach { c ->
            val shown = c.id == shownId
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            c.label, color = if (shown) Cockpit.Accent else Cockpit.Text, fontSize = 17.sp, fontFamily = BarlowCondensed,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(carSubtitle(c), color = Cockpit.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                leadingIcon = {
                    Icon(
                        painterResource(if (shown) R.drawable.ic_check else R.drawable.ic_profile), contentDescription = null,
                        tint = if (shown) Cockpit.Accent else Cockpit.Muted, modifier = Modifier.size(20.dp),
                    )
                },
                onClick = { onDismiss(); if (!shown) picker.onSelect(c.id) },
                modifier = Modifier.heightIn(min = MinTouch),
            )
        }
        if (picker.cars.isNotEmpty()) HorizontalDivider(color = Cockpit.Border)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.cars_add), color = Cockpit.Text, fontSize = 15.sp) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) },
            onClick = { onDismiss(); picker.onAdd() },
            modifier = Modifier.heightIn(min = MinTouch),
        )
        if (picker.cars.isNotEmpty()) DropdownMenuItem(
            text = { Text(stringResource(R.string.cars_title), color = Cockpit.Text, fontSize = 15.sp) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) },
            onClick = { onDismiss(); picker.onManage() },
            modifier = Modifier.heightIn(min = MinTouch),
        )
    }
}

/**
 * The car shown (Profiles tab): name with ▾ (a tap opens the car list of [picker]: show another
 * car, add one, My cars), series / model and VIN state, its ECU list and last detection, the two
 * things done to it most (detect its ECUs, its ECU list) as buttons, rename in ⋮.
 * [busy]: a detection or fault read runs (detect disabled).
 */
@Composable
internal fun CarCard(car: Vehicle, busy: Boolean, actions: CarCardActions, picker: CarPicker = CarPicker(listOf(car)), modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf(false) }
    Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.DropdownList, onClickLabel = stringResource(R.string.cars_change)) { list = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Cockpit.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_profile), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    car.label, color = Cockpit.Text, fontSize = 24.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.padding(start = 4.dp).size(22.dp))
                            }
                            Text(carSubtitle(car), color = Cockpit.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    CarPickerMenu(list, { list = false }, picker, car.id)
                }
                Box {
                    RoundIconButton(R.drawable.ic_more, stringResource(R.string.cars_actions, car.label), onClick = { menu = true }, background = Color.Transparent)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Cockpit.Card) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cars_rename), color = Cockpit.Text, fontSize = 15.sp) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) },
                            onClick = { menu = false; actions.onRename() },
                            modifier = Modifier.heightIn(min = MinTouch),
                        )
                    }
                }
            }
            Spacer(Modifier.size(10.dp))
            val ecus = car.sgbds().size
            Text(
                listOfNotNull(
                    if (ecus == 0) stringResource(R.string.cars_no_ecus) else pluralStringResource(R.plurals.cars_ecus, ecus, ecus),
                    car.scannedAtMs?.let { stringResource(R.string.veh_scanned_at, formatTime(context, it)) } ?: stringResource(R.string.car_not_scanned),
                ).joinToString(" · "),
                color = Cockpit.Text, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp),
            )
            Spacer(Modifier.size(12.dp))
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    stringResource(R.string.car_detect_short), onClick = actions.onDetect, icon = R.drawable.ic_plug, enabled = !busy,
                    color = Cockpit.OnAccent, background = if (busy) Cockpit.Accent.copy(alpha = 0.35f) else Cockpit.Accent, modifier = Modifier.weight(1f),
                )
                PillButton(stringResource(R.string.cars_ecus_action), onClick = actions.onEcus, icon = R.drawable.ic_ecu, modifier = Modifier.weight(1f))
            }
        }
    }
}
