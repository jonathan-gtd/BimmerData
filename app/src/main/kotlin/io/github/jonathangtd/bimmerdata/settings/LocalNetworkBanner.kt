/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.net.LocalNetwork
import io.github.jonathangtd.bimmerdata.ui.Cockpit

/** Local network permission state for a settings page, see [rememberLocalNetworkAccess]. */
class LocalNetworkAccess internal constructor(
    /** Granted, or not needed on this Android version. */
    val granted: Boolean,
    /** The system will not show the dialog any more: only the app settings can grant it. */
    val blocked: Boolean,
    private val onRequest: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    /** Shows the system dialog, or opens the app settings when the dialog is blocked. */
    fun request() = if (blocked) onOpenSettings() else onRequest()

    fun openSettings() = onOpenSettings()
}

/**
 * Remembers the local network permission (Android 17+) for the current screen: re-read when the
 * screen comes back (after the app settings), [LocalNetworkAccess.request] shows the system
 * dialog, then sends to the app settings once Android stops showing it.
 */
@Composable
fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LocalNetwork.granted(context)) }
    var answered by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = LocalNetwork.granted(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok || LocalNetwork.granted(context)
        answered = true
    }
    val activity = context.findActivity()
    val blocked = !granted && answered && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, LocalNetwork.PERMISSION)
    return LocalNetworkAccess(
        granted = granted,
        blocked = blocked,
        onRequest = { if (LocalNetwork.required() && !granted) launcher.launch(LocalNetwork.PERMISSION) },
        onOpenSettings = { runCatching { context.startActivity(LocalNetwork.appSettingsIntent(context)) } },
    )
}

/** Warning shown while the local network permission is missing, with the way to grant it. */
@Composable
fun LocalNetworkBanner(access: LocalNetworkAccess, text: String) {
    if (access.granted) return
    Surface(
        color = Cockpit.Danger.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Cockpit.Danger.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = Cockpit.Text, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!access.blocked) SmallButton(stringResource(R.string.lan_allow), color = Cockpit.Accent) { access.request() }
                SmallButton(stringResource(R.string.lan_app_settings)) { access.openSettings() }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
