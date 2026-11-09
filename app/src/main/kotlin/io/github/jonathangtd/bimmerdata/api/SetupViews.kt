/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import kotlinx.serialization.Serializable

/*
 * What the setup routes (SetupRoutes.kt) show of the app: permissions, Bluetooth, GPS, recording
 * and translation settings. Secrets are never part of a view, only whether one is stored.
 */

/** One runtime permission, special access or system switch the app depends on. */
@Serializable
data class PermissionView(
    /** Stable id: bluetoothConnect, notifications, location, localNetwork, bluetoothOn, locationOn, usbDevice. */
    val id: String,
    /** Android permission name, when it is one. */
    val permission: String? = null,
    /** runtime (Android dialog), systemSetting (a switch of the phone) or perDevice (asked for each device). */
    val kind: String,
    /** granted, missing, notRequired (this Android version does not have it), on, off, askedOnConnect. */
    val status: String,
    /** True when something the user enabled needs it while it is missing / off. */
    val blocking: Boolean = false,
    /** What it is for in the app. */
    val neededFor: String,
    /** What the user has to tap on the phone (an app cannot grant itself these). */
    val howToGrant: String,
)

@Serializable
data class BluetoothView(
    /** BLUETOOTH_CONNECT: granted, missing or notRequired (before Android 12). */
    val permission: String,
    /** Bluetooth switched on on the phone. */
    val enabled: Boolean,
    /** Adapter chosen for `POST /connect {"mode":"bluetooth"}`; absent when none. */
    val adapter: BluetoothAdapterView? = null,
    val connectOnStart: Boolean,
    /** Connected to the car through the adapter right now. */
    val connected: Boolean,
    /** Last adapter state read (only while connected over Bluetooth). */
    val adapterInfo: AdapterInfoView? = null,
)

@Serializable
data class PairedDeviceView(
    val name: String,
    val address: String,
    /** The name looks like an OBD adapter (listed first). */
    val likelyAdapter: Boolean,
    /** Bluetooth LE only: cannot be used (a classic serial port profile adapter is needed). */
    val lowEnergyOnly: Boolean,
    val selected: Boolean,
)

@Serializable
data class AdapterInfoView(
    val type: String,
    val firmware: String,
    val serial: String? = null,
    val voltage: Double? = null,
    /** auto, kLineOnly, dCan500, can100 or unknown. */
    val canMode: String? = null,
    val canModeCode: Int? = null,
    /** False when the CAN mode does not reach the K-line (older cars then do not answer). */
    val reachesKLine: Boolean? = null,
)

@Serializable
data class GpsView(
    val enabled: Boolean,
    val intervalMs: Long,
    val intervals: List<Long>,
    /** Location permission: granted or missing. */
    val permission: String,
    /** Location of the phone switched on. */
    val locationOn: Boolean,
    /** off, idle (not connected to the car), noPermission, locationOff, searching, fix */
    val state: String,
    val fixAtMs: Long? = null,
    val accuracyM: Double? = null,
)

@Serializable
data class RecordingSettingsView(
    /** Where the CSV files go, e.g. `Documents/BimmerData/`. */
    val folder: String,
    /** A folder picked on the phone (only the phone can pick one; the API can go back to the default). */
    val customFolder: Boolean,
    /** At least this many ms between two rows (0 = one row per read cycle). */
    val rowIntervalMs: Long,
    val rowIntervals: List<Long>,
    val filePrefix: String,
    /** Name the next recording would get. */
    val nextFileName: String,
)

data class RecordingSettingsPatch(
    val rowIntervalMs: Long? = null,
    val filePrefix: String? = null,
    /** Back to the default folder (forgets the folder picked on the phone). */
    val useDefaultFolder: Boolean = false,
)

@Serializable
data class TranslationUpdateView(val updated: List<String>, val notFound: List<String>, val errors: List<String>, val atMs: Long)

@Serializable
data class TranslationsView(
    /** False when the language is `de` (original texts). */
    val enabled: Boolean,
    /** Language code; empty = the phone's language; `de` = original texts. */
    val language: String,
    val effectiveLanguage: String,
    val languages: List<String>,
    /** url or folder */
    val source: String,
    val baseUrl: String,
    val defaultBaseUrl: String,
    /** A token for the URL source is stored (it is never returned). */
    val tokenSet: Boolean,
    /** Folder picked on the phone for the `folder` source; absent when none. */
    val folder: String? = null,
    /** auto or chosen */
    val selection: String,
    val chosen: List<String>,
    val cachedEcus: List<String>,
    val updating: Boolean,
    val lastUpdate: TranslationUpdateView? = null,
    val lastError: String? = null,
)

/** Changes of the translation settings (null = unchanged; token "" deletes it). */
data class TranslationsPatch(
    val language: String? = null,
    val source: String? = null,
    val baseUrl: String? = null,
    val token: String? = null,
    val selection: String? = null,
    val chosen: List<String>? = null,
)
