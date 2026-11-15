/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.gauges.AlertCondition
import io.github.jonathangtd.bimmerdata.gauges.AlertLevel
import io.github.jonathangtd.bimmerdata.gauges.CardAlert
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import java.util.Locale

/** One alert rule while it is edited: the threshold is the text typed (empty or invalid = not saved). */
internal data class AlertDraft(
    val enabled: Boolean = true,
    val condition: AlertCondition = AlertCondition.Above,
    val threshold: String = "",
    val level: AlertLevel = AlertLevel.Warning,
    val sound: Boolean = false,
) {
    /** The rule, or null while the threshold is not a number. */
    fun rule(): CardAlert? = parseNumber(threshold)?.takeIf { it.isFinite() }?.let { CardAlert(enabled, condition, it, level, sound) }

    companion object {
        fun of(a: CardAlert, locale: Locale) = AlertDraft(a.enabled, a.condition, editNumber(a.threshold, locale), a.level, a.sound)
    }
}

/**
 * « Alertes » section of the card editor: one block per rule — switch, « Si la valeur est
 * [inférieure à | supérieure à] [seuil] [unité] », then [Orange | Rouge] and the bell (sound),
 * and a delete button; « Ajouter une alerte » up to [CardAlert.MAX_PER_CARD].
 */
@Composable
internal fun AlertsSection(rules: SnapshotStateList<AlertDraft>, unit: String) {
    SectionTitle(stringResource(R.string.alerts_title))
    Text(stringResource(R.string.alerts_hint), color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rules.forEachIndexed { i, r ->
            fun set(next: AlertDraft) {
                rules[i] = next
            }
            val color = if (r.level == AlertLevel.Danger) Cockpit.Danger else Cockpit.Warning
            Surface(
                color = if (r.enabled) color.copy(alpha = 0.07f) else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (r.enabled) color.copy(alpha = 0.45f) else Cockpit.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(
                            checked = r.enabled, onCheckedChange = { set(r.copy(enabled = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Cockpit.OnAccent, checkedTrackColor = Cockpit.Accent,
                                uncheckedThumbColor = Cockpit.Muted, uncheckedTrackColor = Cockpit.Background, uncheckedBorderColor = Cockpit.Border,
                            ),
                        )
                        Text(stringResource(R.string.alert_if), color = if (r.enabled) Cockpit.Text else Cockpit.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        RoundIconButton(
                            R.drawable.ic_delete, stringResource(R.string.alert_delete), onClick = { rules.removeAt(i) },
                            tint = Cockpit.Muted, background = Color.Transparent,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val below = r.condition == AlertCondition.Below
                        PillButton(
                            stringResource(if (below) R.string.alert_below else R.string.alert_above),
                            onClick = { set(r.copy(condition = if (below) AlertCondition.Above else AlertCondition.Below)) },
                            icon = if (below) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up,
                        )
                        NumberField(
                            stringResource(R.string.alert_threshold), r.threshold, { set(r.copy(threshold = it)) }, null,
                            Modifier.weight(1f), isError = r.rule() == null,
                        )
                        if (unit.isNotBlank()) Text(unit, color = Cockpit.Muted, fontSize = 14.sp, modifier = Modifier.width(44.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((level, label) in listOf(AlertLevel.Warning to R.string.alert_orange, AlertLevel.Danger to R.string.alert_red)) {
                            val sel = r.level == level
                            val c = if (level == AlertLevel.Danger) Cockpit.Danger else Cockpit.Warning
                            PillButton(
                                stringResource(label), onClick = { set(r.copy(level = level)) },
                                color = if (sel) Cockpit.Background else c, background = if (sel) c else Track, bold = sel,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        PillButton(
                            stringResource(R.string.alert_sound), onClick = { set(r.copy(sound = !r.sound)) }, icon = R.drawable.ic_bell,
                            color = if (r.sound) Cockpit.OnAccent else Cockpit.Muted, background = if (r.sound) Cockpit.Accent else Track,
                            bold = r.sound, modifier = Modifier.weight(1f),
                        )
                    }
                    if (r.rule() == null) Text(stringResource(R.string.alert_threshold_missing), color = Cockpit.Warning, fontSize = 12.sp)
                }
            }
        }
        if (rules.size < CardAlert.MAX_PER_CARD) {
            PillButton(stringResource(R.string.alert_add), onClick = { rules.add(AlertDraft()) }, icon = R.drawable.ic_add, color = Cockpit.Accent)
        }
    }
}
