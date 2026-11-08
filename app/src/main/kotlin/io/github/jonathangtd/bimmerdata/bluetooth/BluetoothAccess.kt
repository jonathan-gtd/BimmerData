/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** A paired Bluetooth device the user can pick as the diagnostic adapter. */
data class PairedDevice(
    val name: String,
    val address: String,
    /** Name looks like an OBD adapter (listed first). */
    val likelyObd: Boolean,
    /** Bluetooth LE only: not usable (classic SPP needed). */
    val lowEnergyOnly: Boolean,
)

/**
 * Bluetooth helpers. The app does not scan: the user pairs the adapter in Android's Bluetooth
 * settings (PIN usually 1234), then picks it from the paired devices. Android 12+ needs the
 * runtime permission BLUETOOTH_CONNECT for both; no location or scan permission is used.
 */
object BluetoothAccess {

    /** Runtime permission needed before listing or connecting (null below Android 12). */
    val connectPermission: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    fun hasConnectPermission(context: Context): Boolean =
        connectPermission?.let { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ?: true

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /** Paired devices, likely OBD adapters first; empty without permission or Bluetooth. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<PairedDevice> {
        if (!hasConnectPermission(context)) return emptyList()
        val devices = try {
            adapter(context)?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        return devices.map { d ->
            val name = d.name?.takeIf { it.isNotBlank() } ?: d.address
            PairedDevice(name, d.address, looksLikeObd(name), d.type == BluetoothDevice.DEVICE_TYPE_LE)
        }.sortedWith(compareByDescending<PairedDevice> { it.likelyObd }.thenBy { it.lowEnergyOnly }.thenBy { it.name.lowercase() })
    }

    /** Typical names: "Deep OBD BMW", "OBDII", "OBD2", "V-LINK", "ELM327", "Android-Vlink". */
    fun looksLikeObd(name: String): Boolean {
        val n = name.lowercase()
        return listOf("obd", "elm", "deep", "vlink", "v-link", "kdcan", "dcan").any { it in n }
    }

    /** Android's Bluetooth settings, to pair a new adapter. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
