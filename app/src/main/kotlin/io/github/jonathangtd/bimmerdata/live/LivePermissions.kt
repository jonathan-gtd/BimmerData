/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.jonathangtd.bimmerdata.bluetooth.BluetoothAccess

/**
 * Runtime permissions of the live acquisition. Only the notification permission (Android 13+)
 * is a runtime one: without it the acquisition still runs, the ongoing notification is just
 * not shown. USB access is asked by [io.github.jonathangtd.bimmerdata.usb.UsbCable] when
 * connecting; CSV files need no storage permission (MediaStore).
 *
 * In Compose:
 * ```
 * val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
 * if (LivePermissions.shouldRequestNotifications(context)) launcher.launch(LivePermissions.Notifications)
 * ```
 */
object LivePermissions {
    /** Manifest.permission.POST_NOTIFICATIONS (only meaningful on Android 13+). */
    const val Notifications: String = "android.permission.POST_NOTIFICATIONS"

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** True when the UI should launch a RequestPermission for [Notifications] (Android 13+, not granted yet). */
    fun shouldRequestNotifications(context: Context): Boolean = !notificationsGranted(context)

    /** Permissions to request at runtime before connecting; empty below Android 13. */
    fun runtimePermissions(context: Context): Array<String> =
        if (shouldRequestNotifications(context)) arrayOf(Notifications) else emptyArray()

    /**
     * Runtime permissions still missing before connecting in [mode]: the notification of the
     * foreground service (every mode, simulation included) and BLUETOOTH_CONNECT (Android 12+) for Bluetooth.
     * Connect anyway once the dialog is answered: without them the connection reports why.
     */
    fun missingFor(context: Context, mode: LiveMode): Array<String> = buildList {
        if (shouldRequestNotifications(context)) add(Notifications)
        if (mode == LiveMode.Bluetooth && !BluetoothAccess.hasConnectPermission(context)) BluetoothAccess.connectPermission?.let(::add)
    }.toTypedArray()
}
