/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.content.SharedPreferences

/** Bluetooth adapter of Settings › Connection. */
data class BluetoothSettings(
    /** MAC address of the chosen paired adapter; null = none chosen yet. */
    val address: String? = null,
    /** Its name when it was chosen (for the UI and the connection label). */
    val name: String? = null,
    /** Connect to the adapter when the app starts (off by default: the adapter only has power in the car). */
    val connectOnStart: Boolean = false,
) {
    val label: String? get() = name ?: address
}

private const val K_BT_ADDRESS = "bt_address"
private const val K_BT_NAME = "bt_name"
private const val K_BT_ON_START = "bt_connect_on_start"

internal fun loadBluetooth(p: SharedPreferences) = BluetoothSettings(
    address = p.getString(K_BT_ADDRESS, null),
    name = p.getString(K_BT_NAME, null),
    connectOnStart = p.getBoolean(K_BT_ON_START, false),
)

internal fun SharedPreferences.Editor.saveBluetooth(s: Settings) {
    putString(K_BT_ADDRESS, s.bluetooth.address)
    putString(K_BT_NAME, s.bluetooth.name)
    putBoolean(K_BT_ON_START, s.bluetooth.connectOnStart)
}
