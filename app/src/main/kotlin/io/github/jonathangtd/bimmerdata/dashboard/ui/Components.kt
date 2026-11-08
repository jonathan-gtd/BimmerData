/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import java.util.Locale

internal val MinTouch = 48.dp
internal val RecordRed = Color(0xFFFF5A5F)
internal val RecordText = Color(0xFFFF8A8E)
internal val RecordBorder = Color(0xFF5A2A2D)
internal val RecordBg = Color(0xFF1E1214)
internal val Track = Color(0xFF232830)

/** Round 48 dp icon button on a card-colored disc. */
@Composable
internal fun RoundIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Cockpit.Text,
    background: Color = Cockpit.Card,
    size: Dp = MinTouch,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (enabled) tint else Cockpit.Muted.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
    }
}

/** Rounded rectangular button used by toolbars and sheets. */
@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    color: Color = Cockpit.Text,
    background: Color = Track,
    enabled: Boolean = true,
    bold: Boolean = true,
) {
    Row(
        modifier
            .heightIn(min = MinTouch)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) color else Cockpit.Muted.copy(alpha = 0.5f)
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = c, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c, fontSize = 14.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Mockup "A" tabs: a card-colored rail with the selected tab raised. Scrolls when there are many. */
@Composable
internal fun SegmentedTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Cockpit.Card, shape = RoundedCornerShape(14.dp)) {
        // Equal-width tabs while they fit (mockup), scrolling tabs sized to their text beyond.
        val scroll = labels.size > 3 || labels.sumOf { it.length } > 30 || trailing != null
        Row(
            Modifier
                .padding(4.dp)
                .let { if (scroll) it.horizontalScroll(rememberScrollState()) else it },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, label ->
                val isSel = i == selected
                Box(
                    Modifier
                        .let { if (scroll) it.widthIn(min = 76.dp) else it.weight(1f) }
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSel) Track else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(i) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = if (isSel) Cockpit.Text else Cockpit.Muted, fontSize = 14.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            trailing?.invoke(this)
        }
    }
}

/** "−  value  +" control with 48 dp targets. */
@Composable
internal fun Stepper(label: String, value: String, onDecrease: () -> Unit, onIncrease: () -> Unit, canDecrease: Boolean, canIncrease: Boolean, decreaseLabel: String, increaseLabel: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Cockpit.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Surface(color = Cockpit.Background, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Cockpit.Border)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−", decreaseLabel, canDecrease, onDecrease)
                Text(
                    value, color = Cockpit.Text, fontSize = 18.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.defaultMinSize(minWidth = 44.dp),
                )
                StepButton("+", increaseLabel, canIncrease, onIncrease)
            }
        }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(MinTouch)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = if (enabled) Cockpit.Accent else Cockpit.Border, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

/** Numeric text field: empty = automatic; accepts "," or "." as decimal separator. */
@Composable
internal fun NumberField(label: String, value: String, onValue: (String) -> Unit, placeholder: String?, modifier: Modifier = Modifier, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValue(v.filter { it.isDigit() || it in ",.-−+" }.take(12)) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Cockpit.Muted) } },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        colors = fieldColors(),
    )
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cockpit.Accent,
    unfocusedBorderColor = Cockpit.Border,
    focusedLabelColor = Cockpit.Accent,
    unfocusedLabelColor = Cockpit.Muted,
    cursorColor = Cockpit.Accent,
    focusedContainerColor = Cockpit.Background,
    unfocusedContainerColor = Cockpit.Background,
)

/** Parses what [NumberField] accepts; null for blank or invalid input. */
internal fun parseNumber(text: String): Double? =
    text.trim().replace('−', '-').replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()

/** Shows a number for editing, in the user's locale and without useless decimals. */
internal fun editNumber(value: Double?, locale: Locale): String {
    if (value == null) return ""
    val s = if (value % 1.0 == 0.0) value.toLong().toString() else value.toBigDecimal().stripTrailingZeros().toPlainString()
    return if (locale.language == "fr") s.replace('.', ',') else s
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = Cockpit.Muted, fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}
