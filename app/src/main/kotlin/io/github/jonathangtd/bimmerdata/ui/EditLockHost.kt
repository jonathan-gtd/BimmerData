/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.api.EditLock
import kotlinx.coroutines.delay

/**
 * Lock screen over the whole app while an API client holds the [EditLock] (PUT /api/v1/lock):
 * the reason, the time left, and « Déverrouiller » (after a confirmation) to break it. It eats
 * every touch so nothing below can be changed; reading and recording go on underneath and API
 * confirmations ([ApiConfirmHost]) still show above it. It goes away by itself at the end.
 */
@Composable
fun EditLockHost(lock: EditLock = EditLock.shared) {
    val state by lock.state.collectAsStateWithLifecycle()
    val s = state ?: return
    var now by remember(s) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s) {
        while (lock.current() != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val left = ((s.untilMs - now + 999) / 1000).coerceAtLeast(0)
    if (left == 0L) return
    var confirm by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Cockpit.Background.copy(alpha = 0.96f))
            // swallow every touch: the app below must not be changed meanwhile
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Cockpit.Card, RoundedCornerShape(20.dp))
                .border(1.dp, Cockpit.Border, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.lock_title), color = Cockpit.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(s.reason, color = Cockpit.Accent, fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.lock_body), color = Cockpit.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.lock_time_left, "%d:%02d".format(left / 60, left % 60)), color = Cockpit.Warning, fontSize = 15.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { confirm = true }) {
                Text(stringResource(R.string.lock_unlock), color = Cockpit.Danger, fontSize = 16.sp)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Cockpit.Card,
            title = { Text(stringResource(R.string.lock_unlock_title), color = Cockpit.Text, fontWeight = FontWeight.SemiBold) },
            text = { Text(stringResource(R.string.lock_unlock_text), color = Cockpit.Text) },
            confirmButton = {
                TextButton(onClick = { confirm = false; lock.unlock() }) { Text(stringResource(R.string.lock_unlock), color = Cockpit.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.lock_keep), color = Cockpit.Accent) }
            },
        )
    }
}
