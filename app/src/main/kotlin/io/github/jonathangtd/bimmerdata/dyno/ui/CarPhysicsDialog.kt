/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dyno.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.MinTouch
import io.github.jonathangtd.bimmerdata.dashboard.ui.fieldColors
import io.github.jonathangtd.bimmerdata.gauges.currentLocale
import io.github.jonathangtd.bimmerdata.live.dyno.DynoParams
import io.github.jonathangtd.bimmerdata.live.vehicle.CarPhysics
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import java.util.Locale

/** A number as typed ("1 620", "0,65" or "0.65"); null for an empty field, NaN for garbage. */
internal fun parseTyped(text: String): Double? {
    val t = text.filterNot { it.isWhitespace() || it == ' ' || it == ' ' }.replace(',', '.')
    if (t.isEmpty()) return null
    return t.toDoubleOrNull() ?: Double.NaN
}

/** [v] for a text field (no grouping, the locale's decimal separator); "" for null. */
internal fun formatTyped(v: Double?, locale: Locale): String {
    if (v == null) return ""
    if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) return v.toLong().toString()
    val sep = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return v.toString().replace('.', sep)
}

/**
 * Mass, drag area, rolling resistance and air density of a car for the virtual dyno; the driveline
 * loss too when [loss] is given (from the dyno screen). Empty fields = defaults (the mass is needed).
 */
@Composable
internal fun CarPhysicsDialog(
    carLabel: String,
    physics: CarPhysics,
    loss: Double?,
    onSave: (CarPhysics, Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = currentLocale()
    var mass by remember { mutableStateOf(formatTyped(physics.massKg, locale)) }
    var cda by remember { mutableStateOf(formatTyped(physics.dragAreaM2, locale)) }
    var crr by remember { mutableStateOf(formatTyped(physics.rollingResistance, locale)) }
    var rho by remember { mutableStateOf(formatTyped(physics.airDensity, locale)) }
    var lossText by remember { mutableStateOf(formatTyped(loss, locale)) }
    val typed = CarPhysics(parseTyped(mass), parseTyped(cda), parseTyped(crr), parseTyped(rho))
    val lossValue = parseTyped(lossText)
    val massBad = typed.massKg?.let { it.isNaN() || it !in CarPhysics.MASS_RANGE } ?: false
    val cdaBad = typed.dragAreaM2?.let { it.isNaN() || it !in CarPhysics.DRAG_AREA_RANGE } ?: false
    val crrBad = typed.rollingResistance?.let { it.isNaN() || it !in CarPhysics.ROLLING_RANGE } ?: false
    val rhoBad = typed.airDensity?.let { it.isNaN() || it !in CarPhysics.AIR_DENSITY_RANGE } ?: false
    val lossBad = loss != null && lossValue != null && (lossValue.isNaN() || lossValue !in DynoParams.LOSS_RANGE)
    val valid = !(massBad || cdaBad || crrBad || rhoBad || lossBad)

    @Composable
    fun field(value: String, onChange: (String) -> Unit, label: Int, placeholder: String, bad: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = { onChange(it.take(10)) }, singleLine = true,
            label = { Text(stringResource(label)) }, placeholder = { Text(placeholder, color = Cockpit.Muted) },
            isError = bad, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = modifier, colors = fieldColors(),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Cockpit.Card,
        title = { Text(stringResource(R.string.dyno_physics_title, carLabel), color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field(mass, { mass = it }, R.string.dyno_mass, "1600", massBad)
                Text(stringResource(R.string.dyno_mass_hint), color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(cda, { cda = it }, R.string.dyno_drag_area, formatTyped(CarPhysics.DEFAULT_DRAG_AREA, locale), cdaBad, Modifier.weight(1f))
                    field(crr, { crr = it }, R.string.dyno_rolling, formatTyped(CarPhysics.DEFAULT_ROLLING, locale), crrBad, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(rho, { rho = it }, R.string.dyno_air_density, formatTyped(CarPhysics.DEFAULT_AIR_DENSITY, locale), rhoBad, Modifier.weight(1f))
                    if (loss != null) field(lossText, { lossText = it }, R.string.dyno_loss, formatTyped(DynoParams.DEFAULT_LOSS_PCT, locale), lossBad, Modifier.weight(1f))
                }
                Text(stringResource(R.string.dyno_physics_hint), color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(typed, if (loss != null) lossValue ?: DynoParams.DEFAULT_LOSS_PCT else null) },
                enabled = valid, modifier = Modifier.heightIn(min = MinTouch),
            ) { Text(stringResource(R.string.dyno_save), color = if (valid) Cockpit.Accent else Cockpit.Muted) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.cancel), color = Cockpit.Muted) }
        },
    )
}
