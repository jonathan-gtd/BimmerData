/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.android

import android.Manifest
import android.content.Context
import android.os.Build
import io.github.jonathangtd.bimmerdata.api.PermissionView
import io.github.jonathangtd.bimmerdata.bluetooth.BluetoothAccess
import io.github.jonathangtd.bimmerdata.gps.GpsRuntime
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.LivePermissions
import io.github.jonathangtd.bimmerdata.net.LocalNetwork
import io.github.jonathangtd.bimmerdata.settings.AppSettings

/**
 * The permissions report of `GET /api/v1/permissions`: every runtime permission, per-device access
 * and system switch the app depends on, with what the user must tap (texts in English, for the
 * API client; the phone shows its own localized prompts).
 */
internal object AndroidPermissions {
    fun report(context: Context): List<PermissionView> {
        val app = context.applicationContext
        val s = AppSettings.get(app).state.value
        val gps = GpsRuntime.get(app)
        val btWanted = s.bluetooth.address != null || s.lastMode == LiveMode.Bluetooth
        val btPermission = when {
            BluetoothAccess.connectPermission == null -> "notRequired"
            BluetoothAccess.hasConnectPermission(app) -> "granted"
            else -> "missing"
        }
        val btOn = runCatching { BluetoothAccess.isEnabled(app) }.getOrDefault(false)
        val notifications = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "notRequired"
            LivePermissions.notificationsGranted(app) -> "granted"
            else -> "missing"
        }
        val location = if (gps.hasPermission()) "granted" else "missing"
        val locationOn = runCatching { gps.locationEnabled() }.getOrDefault(false)
        val lan = when (LocalNetwork.state(app)) {
            "granted" -> "granted"
            "denied" -> "missing"
            else -> "notRequired"
        }
        val lanWanted = s.api.enabled || (s.telegraf.enabled && LocalNetwork.isLocalUrl(s.telegraf.url))
        return listOf(
            PermissionView(
                id = "bluetoothConnect", permission = "android.permission.BLUETOOTH_CONNECT", kind = "runtime", status = btPermission,
                blocking = btPermission == "missing" && btWanted,
                neededFor = "Listing the paired Bluetooth devices and connecting to the Deep OBD adapter (Android 12 and later, shown as \"Nearby devices\").",
                howToGrant = "In BimmerData: Settings › Connection › Bluetooth adapter › Allow. Or Android settings › Apps › BimmerData › Permissions › Nearby devices › Allow.",
            ),
            PermissionView(
                id = "bluetoothOn", kind = "systemSetting", status = if (btOn) "on" else "off",
                blocking = !btOn && btWanted,
                neededFor = "Connecting to the Bluetooth adapter.",
                howToGrant = "Switch Bluetooth on (quick settings). Pairing a new adapter also happens on the phone: Android settings › Bluetooth › Pair new device (PIN usually 1234).",
            ),
            PermissionView(
                id = "notifications", permission = LivePermissions.Notifications, kind = "runtime", status = notifications,
                neededFor = "The ongoing notification while the car is read, and the Allow / Refuse notification of the API calls that need a confirmation (fault clearing, jobs, adapter CAN mode) when the app is not on screen.",
                howToGrant = "Answer Allow when BimmerData asks at the first connection. Or Android settings › Apps › BimmerData › Notifications › Allow.",
            ),
            PermissionView(
                id = "location", permission = Manifest.permission.ACCESS_FINE_LOCATION, kind = "runtime", status = location,
                blocking = location == "missing" && s.gps.enabled,
                neededFor = "The phone's GPS position recorded with the ECU values (Settings › GPS position); not needed otherwise.",
                howToGrant = "In BimmerData: Settings › GPS position › switch on, then allow the precise location. Or Android settings › Apps › BimmerData › Permissions › Location › Allow only while using the app, with Use precise location on.",
            ),
            PermissionView(
                id = "locationOn", kind = "systemSetting", status = if (locationOn) "on" else "off",
                blocking = !locationOn && s.gps.enabled,
                neededFor = "The GPS position (when it is switched on in BimmerData).",
                howToGrant = "Switch the phone's location on (quick settings).",
            ),
            PermissionView(
                id = "localNetwork", permission = LocalNetwork.PERMISSION, kind = "runtime", status = lan,
                blocking = lan == "missing" && lanWanted,
                neededFor = "Other devices of the Wi-Fi network reaching this API, and a Telegraf or translation server on the local network (Android 17 and later). Without it the API only answers on the phone itself (127.0.0.1, adb forward).",
                howToGrant = "In BimmerData: Settings › Local API › Allow. Or Android settings › Apps › BimmerData › Permissions › Nearby devices (local network) › Allow.",
            ),
            PermissionView(
                id = "usbDevice", kind = "perDevice", status = "askedOnConnect",
                neededFor = "The USB K+DCAN cable: Android asks once per cable when the app connects to it.",
                howToGrant = "Tap OK in the USB dialog the first time POST /api/v1/connect {\"mode\":\"usb\"} runs with the cable plugged in (tick \"Always open BimmerData\" so it is not asked again).",
            ),
        )
    }
}
