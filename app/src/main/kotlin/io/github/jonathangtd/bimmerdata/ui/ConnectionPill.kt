/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.gauges.currentLocale
import io.github.jonathangtd.bimmerdata.export.ExportMode
import io.github.jonathangtd.bimmerdata.live.AcquisitionState
import io.github.jonathangtd.bimmerdata.live.BackgroundSnapshot
import io.github.jonathangtd.bimmerdata.live.BackgroundWork
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.LivePermissions
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.settings.AppSettings

/** Green of the "reading" state (the cockpit palette has no green of its own). */
internal val ConnectionOk = Color(0xFF4CD787)

/** How the pill looks for a connection + acquisition status. */
internal enum class PillTone { Idle, Ok, Busy, Error }

internal fun pillTone(connection: LiveConnection, status: AcquisitionStatus): PillTone = when (connection) {
    LiveConnection.Disconnected -> PillTone.Idle
    is LiveConnection.Failed -> PillTone.Error
    is LiveConnection.Connecting -> PillTone.Busy
    is LiveConnection.Connected -> when (status.state) {
        AcquisitionState.Running -> PillTone.Ok
        AcquisitionState.Connecting, AcquisitionState.Stopped -> PillTone.Busy
        AcquisitionState.Error -> PillTone.Error
    }
}

private fun toneColor(tone: PillTone): Color = when (tone) {
    PillTone.Idle -> Cockpit.Muted
    PillTone.Ok -> ConnectionOk
    PillTone.Busy -> Cockpit.Warning
    PillTone.Error -> Cockpit.Danger
}

/**
 * The app's connection, the same on every screen that talks to the car (dashboard, faults,
 * Tool32): they all share the one connection of [LiveRepository].
 */
@Composable
fun ConnectionPill(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { LiveRepository.get(context) }
    val settings = remember { AppSettings.get(context) }
    val connection by repo.connection.collectAsStateWithLifecycle()
    val status by repo.liveDataSource.status.collectAsStateWithLifecycle()
    val s by settings.state.collectAsStateWithLifecycle()
    val rec by repo.recorder.state.collectAsStateWithLifecycle()
    ConnectionPillContent(
        connection, status, s.lastMode, onConnect = repo::connect, onDisconnect = repo::disconnect, modifier = modifier,
        recording = rec.recording,
    )
}

/**
 * Stateless pill:
 * - disconnected (or failed): a big "Connect" pill. The first time it asks Cable / Bluetooth /
 *   Simulation; afterwards it connects at once in the last mode, and its "▾" end changes the mode;
 * - connecting / connected: a colored status pill (green = reading, amber = connecting or
 *   reconnecting, red = error) with the read rate; a tap opens a menu with only "Disconnect".
 *
 * Cable and Bluetooth: asks first for the missing runtime permissions (notification of the
 * foreground service, Bluetooth connect).
 */
@Composable
fun ConnectionPillContent(
    connection: LiveConnection,
    status: AcquisitionStatus,
    lastMode: LiveMode?,
    onConnect: (LiveMode) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    /** Recording in progress: shown in the "keeps running in the background" notice. */
    recording: Boolean = false,
    /** Show that the app keeps working off screen while connected ([BackgroundNotice]). */
    showBackgroundNotice: Boolean = true,
) {
    val context = LocalContext.current
    var pendingMode by remember { mutableStateOf<LiveMode?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingMode?.let(onConnect) // connect anyway: a missing permission is reported by the connection
        pendingMode = null
    }
    fun connect(mode: LiveMode) {
        val missing = LivePermissions.missingFor(context, mode)
        if (missing.isNotEmpty()) {
            pendingMode = mode
            permissions.launch(missing)
        } else {
            onConnect(mode)
        }
    }
    var menu by remember { mutableStateOf(false) }
    val tone = pillTone(connection, status)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Box {
        when (connection) {
            LiveConnection.Disconnected, is LiveConnection.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ConnectButton(
                    lastMode = lastMode,
                    onMain = { if (lastMode == null) menu = true else connect(lastMode) },
                    onMenu = { menu = true },
                )
                if (connection is LiveConnection.Failed) {
                    Text(
                        stringResource(R.string.cp_failed, connection.message), color = Cockpit.Danger, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            is LiveConnection.Connecting, is LiveConnection.Connected -> StatusButton(connection, status, tone) { menu = true }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Cockpit.Card) {
            val current = (connection as? LiveConnection.Connected)?.mode ?: (connection as? LiveConnection.Connecting)?.mode
            if (current == null) { // mode chooser (the "▾" of Connect, or the first Connect)
                MenuEntry(stringResource(R.string.cp_mode_usb), R.drawable.ic_plug) { menu = false; connect(LiveMode.Usb) }
                MenuEntry(stringResource(R.string.bt_mode), R.drawable.ic_bluetooth) { menu = false; connect(LiveMode.Bluetooth) }
                MenuEntry(stringResource(R.string.cp_mode_sim), R.drawable.ic_dashboard) { menu = false; connect(LiveMode.Simulation) }
            } else { // connected: only Disconnect (to change the mode: disconnect, then Connect ▾)
                MenuEntry(stringResource(R.string.cp_disconnect), null, Cockpit.Danger) { menu = false; onDisconnect() }
            }
        }
    }
    if (showBackgroundNotice) {
        val tg = AppSettings.get(context).state.collectAsStateWithLifecycle().value.telegraf
        val tasks = BackgroundWork.tasks(
            BackgroundSnapshot(
                connection, status, RecordingState(recording = recording),
                telegrafLive = tg.enabled && tg.mode == ExportMode.Live && tg.url.isNotBlank(),
            ),
        )
        BackgroundNotice(tasks)
    }
    }
}

@Composable
private fun MenuEntry(text: String, icon: Int?, color: Color = Cockpit.Text, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = color, fontSize = 15.sp) },
        leadingIcon = icon?.let { { Icon(painterResource(it), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) } },
        onClick = onClick,
        modifier = Modifier.height(48.dp),
    )
}

/** Short name in the status pill: "Cable", "Bluetooth · Deep OBD BMW", "Simulation". */
@Composable
private fun modeName(mode: LiveMode, label: String? = null): String = when (mode) {
    LiveMode.Usb -> stringResource(R.string.cp_short_usb)
    LiveMode.Bluetooth -> stringResource(R.string.bt_short) + (label?.let { " · $it" } ?: "")
    LiveMode.Simulation -> stringResource(R.string.cp_short_sim)
}

/** Long name under "Connect". */
@Composable
private fun modeLongName(mode: LiveMode): String = stringResource(
    when (mode) {
        LiveMode.Usb -> R.string.cp_mode_usb
        LiveMode.Bluetooth -> R.string.bt_mode
        LiveMode.Simulation -> R.string.cp_mode_sim
    },
)

private fun modeIcon(mode: LiveMode?): Int = if (mode == LiveMode.Bluetooth) R.drawable.ic_bluetooth else R.drawable.ic_plug

/** Big accent "Connect" pill; its end opens the mode menu once a mode is known. */
@Composable
private fun ConnectButton(lastMode: LiveMode?, onMain: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val connectLabel = stringResource(R.string.cp_connect)
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(shape).background(Cockpit.Accent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(role = Role.Button, onClickLabel = connectLabel, onClick = onMain)
                .padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(modeIcon(lastMode)), contentDescription = null, tint = Cockpit.OnAccent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(connectLabel, color = Cockpit.OnAccent, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1)
                Text(
                    if (lastMode == null) stringResource(R.string.bt_cp_choose_mode) else modeLongName(lastMode),
                    color = Cockpit.OnAccent.copy(alpha = 0.72f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (lastMode != null) {
            val menuLabel = stringResource(R.string.cp_mode_menu)
            Box(Modifier.width(1.dp).height(32.dp).background(Cockpit.OnAccent.copy(alpha = 0.25f)))
            Box(
                Modifier
                    .width(56.dp)
                    .fillMaxHeight()
                    .clickable(role = Role.Button, onClickLabel = menuLabel, onClick = onMenu)
                    .semantics { contentDescription = menuLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text("▾", color = Cockpit.OnAccent, fontSize = 18.sp)
            }
        }
    }
}

/** Colored status pill of a connection in progress or established. */
@Composable
private fun StatusButton(connection: LiveConnection, status: AcquisitionStatus, tone: PillTone, onClick: () -> Unit) {
    val color = toneColor(tone)
    val shape = RoundedCornerShape(22.dp)
    val mode = (connection as? LiveConnection.Connected)?.mode ?: (connection as LiveConnection.Connecting).mode
    val name = modeName(mode, (connection as? LiveConnection.Connected)?.label)
    val text = when {
        connection is LiveConnection.Connecting -> stringResource(R.string.cp_connecting, name)
        tone == PillTone.Ok -> stringResource(R.string.cp_reading, name, String.format(currentLocale(), "%.1f", status.cyclesPerSecond))
        tone == PillTone.Error -> stringResource(R.string.cp_no_answer, name)
        else -> stringResource(R.string.cp_reconnecting, name)
    }
    val detail = if (tone == PillTone.Error) status.message else null
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.55f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tone == PillTone.Busy) {
            CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 2.dp)
        } else {
            Box(Modifier.size(10.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Cockpit.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (detail != null) {
            Text(
                " · $detail", color = Cockpit.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        Text("▾", color = Cockpit.Muted, fontSize = 14.sp)
    }
}
