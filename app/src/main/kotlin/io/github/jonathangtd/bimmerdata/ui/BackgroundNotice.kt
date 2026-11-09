/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.live.BackgroundTask
import io.github.jonathangtd.bimmerdata.live.LivePermissions

/**
 * Discreet line under the connection pill while BimmerData keeps working off screen:
 * "Keeps running in the background: connection, recording" + "What does it mean?" (a short
 * help: it keeps reading with the screen off, how to stop). When notifications are off it
 * says nothing will show it, with a button to allow them. Nothing when [tasks] is empty.
 */
@Composable
fun BackgroundNotice(tasks: List<BackgroundTask>, modifier: Modifier = Modifier) {
    if (tasks.isEmpty()) return
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    val openSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!granted || !enabled) runCatching { openSettings() } // refused for good, or the channel is off
    }
    BackgroundNoticeContent(
        tasks, notificationsOff = !enabled,
        onAllow = {
            if (LivePermissions.shouldRequestNotifications(context)) launcher.launch(LivePermissions.Notifications) else runCatching { openSettings() }
        },
        modifier = modifier,
    )
}

/** Stateless content of [BackgroundNotice] (also used by the screenshot tests). */
@Composable
fun BackgroundNoticeContent(tasks: List<BackgroundTask>, notificationsOff: Boolean, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    if (tasks.isEmpty()) return
    var help by remember { mutableStateOf(false) }
    val names = tasks.map {
        stringResource(
            when (it) {
                BackgroundTask.Connection -> R.string.bg_task_connection
                BackgroundTask.Recording -> R.string.bg_task_recording
                BackgroundTask.TelegrafLive -> R.string.bg_task_telegraf
            },
        )
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.bg_notice_more)) { help = true }
                .heightIn(min = 32.dp)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.bg_notice, names.joinToString(", ")),
                color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.bg_notice_more),
                tint = Cockpit.Accent, modifier = Modifier.size(18.dp),
            )
        }
        if (notificationsOff) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bg_notifications_off), color = Cockpit.Warning, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onAllow) { Text(stringResource(R.string.bg_notifications_allow), color = Cockpit.Accent) }
            }
        }
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.bg_help_title)) },
            text = { Text(stringResource(R.string.bg_help_text)) },
            confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.bg_help_ok)) } },
            containerColor = Cockpit.Card, titleContentColor = Cockpit.Text, textContentColor = Cockpit.Text,
        )
    }
}
