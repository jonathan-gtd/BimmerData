/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.api.android.ApiConfirmations
import kotlinx.coroutines.delay

/**
 * Shows the oldest API request that waits for the user's confirmation (writes to the car), over
 * every screen. Dismissing the dialog without a choice leaves the request to time out (refused).
 */
@Composable
fun ApiConfirmHost() {
    val pending by ApiConfirmations.pending.collectAsStateWithLifecycle()
    val p = pending.firstOrNull() ?: return
    val res = LocalResources.current
    var now by remember(p.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.id) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val seconds = ((p.deadlineMs - now) / 1000).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = Cockpit.Card,
        title = { Text(stringResource(R.string.fb3_confirm_title), color = Cockpit.Text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(ApiConfirmations.text(res, p.request), color = Cockpit.Text, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.fb3_confirm_note), color = Cockpit.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.fb3_confirm_countdown, seconds), color = Cockpit.Warning, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { ApiConfirmations.answer(p.id, true) }) { Text(stringResource(R.string.fb3_confirm_allow), color = Cockpit.Danger) }
        },
        dismissButton = {
            TextButton(onClick = { ApiConfirmations.answer(p.id, false) }) { Text(stringResource(R.string.fb3_confirm_refuse), color = Cockpit.Accent) }
        },
    )
}
