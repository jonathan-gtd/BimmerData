/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.content.SharedPreferences
import io.github.jonathangtd.bimmerdata.export.ExportConfig
import io.github.jonathangtd.bimmerdata.export.ExportMode
import io.github.jonathangtd.bimmerdata.export.QueueEstimate
import io.github.jonathangtd.bimmerdata.live.CsvSessionRecorder

/** CSV recording settings (Settings › Recording). */
data class RecordingConfig(
    /** SAF tree Uri of the folder chosen by the user; null = `Documents/BimmerData/`. */
    val folderUri: String? = null,
    /** Display name of [folderUri] (e.g. "Download/Car logs"). */
    val folderLabel: String? = null,
    /** At least this many ms between two rows (0 = one row per read cycle). */
    val rowIntervalMs: Long = 0,
    /** File names are `<prefix>_YYYYMMDD_HHMMSS.csv`. */
    val filePrefix: String = CsvSessionRecorder.DefaultPrefix,
) {
    companion object {
        val ROW_INTERVALS = listOf(0L, 100L, 200L, 500L, 1000L)
    }
}

enum class TelegrafAuthMode { None, Token, Basic }

/**
 * Telegraf export settings (Settings › Telegraf export). The token and the password are not here:
 * they are encrypted with the Android Keystore in [SecretStore].
 */
data class TelegrafSettings(
    val enabled: Boolean = false,
    /** Listener URL, e.g. `http://192.168.1.10:8186/api/v2/write`. */
    val url: String = "",
    val authMode: TelegrafAuthMode = TelegrafAuthMode.Token,
    val username: String = "",
    /** `car` tag; blank = "car". */
    val car: String = "",
    val pointsPerSecond: Int = 1,
    val mode: ExportMode = ExportMode.AfterTrip,
    /**
     * Free space kept on the phone, in MB (one of [QueueEstimate.RESERVES_MB]). The queue never
     * deletes points: when the phone reaches this reserve, new points are not queued.
     */
    val reserveMb: Int = 500,
) {
    init {
        require(pointsPerSecond in ExportConfig.RATES) { "pointsPerSecond must be in ${ExportConfig.RATES}" }
    }
}

/** Phone GPS position recorded with the ECU values (Settings › GPS position). */
data class GpsSettings(
    /** Off by default: the location permission is only asked when the user switches it on. */
    val enabled: Boolean = false,
    /** Time between two fixes asked from the GPS, in ms. */
    val intervalMs: Long = 1000,
) {
    companion object {
        val INTERVALS = listOf(1000L, 2000L, 5000L, 10_000L)
    }
}

// ---- persistence of the fields above, called by AppSettings.load / update ----

private const val K_REC_FOLDER = "rec_folder_uri"
private const val K_REC_FOLDER_LABEL = "rec_folder_label"
private const val K_REC_INTERVAL = "rec_row_interval_ms"
private const val K_REC_PREFIX = "rec_file_prefix"
private const val K_TG_ON = "tg_enabled"
private const val K_TG_URL = "tg_url"
private const val K_TG_AUTH = "tg_auth"
private const val K_TG_USER = "tg_user"
private const val K_TG_CAR = "tg_car"
private const val K_TG_RATE = "tg_rate"
private const val K_TG_MODE = "tg_mode"
private const val K_TG_RESERVE_MB = "tg_reserve_mb"
private const val K_GPS_ON = "gps_enabled"
private const val K_GPS_INTERVAL = "gps_interval_ms"

internal fun loadRecording(p: SharedPreferences) = RecordingConfig(
    folderUri = p.getString(K_REC_FOLDER, null),
    folderLabel = p.getString(K_REC_FOLDER_LABEL, null),
    rowIntervalMs = p.getLong(K_REC_INTERVAL, 0L).takeIf { it in RecordingConfig.ROW_INTERVALS } ?: 0L,
    filePrefix = CsvSessionRecorder.safePrefix(p.getString(K_REC_PREFIX, null).orEmpty()),
)

internal fun loadTelegraf(p: SharedPreferences) = TelegrafSettings(
    enabled = p.getBoolean(K_TG_ON, false),
    url = p.getString(K_TG_URL, null).orEmpty(),
    authMode = runCatching { TelegrafAuthMode.valueOf(p.getString(K_TG_AUTH, null) ?: "") }.getOrDefault(TelegrafAuthMode.Token),
    username = p.getString(K_TG_USER, null).orEmpty(),
    car = p.getString(K_TG_CAR, null).orEmpty(),
    pointsPerSecond = p.getInt(K_TG_RATE, 1).coerceIn(ExportConfig.RATES),
    mode = runCatching { ExportMode.valueOf(p.getString(K_TG_MODE, null) ?: "") }.getOrDefault(ExportMode.AfterTrip),
    reserveMb = p.getInt(K_TG_RESERVE_MB, 500).takeIf { it in QueueEstimate.RESERVES_MB } ?: 500,
)

internal fun loadGps(p: SharedPreferences) = GpsSettings(
    enabled = p.getBoolean(K_GPS_ON, false),
    intervalMs = p.getLong(K_GPS_INTERVAL, 1000L).takeIf { it in GpsSettings.INTERVALS } ?: 1000L,
)

internal fun SharedPreferences.Editor.saveExtras(s: Settings) {
    putString(K_REC_FOLDER, s.recording.folderUri)
    putString(K_REC_FOLDER_LABEL, s.recording.folderLabel)
    putLong(K_REC_INTERVAL, s.recording.rowIntervalMs)
    putString(K_REC_PREFIX, s.recording.filePrefix)
    putBoolean(K_TG_ON, s.telegraf.enabled)
    putString(K_TG_URL, s.telegraf.url)
    putString(K_TG_AUTH, s.telegraf.authMode.name)
    putString(K_TG_USER, s.telegraf.username)
    putString(K_TG_CAR, s.telegraf.car)
    putInt(K_TG_RATE, s.telegraf.pointsPerSecond)
    putString(K_TG_MODE, s.telegraf.mode.name)
    putInt(K_TG_RESERVE_MB, s.telegraf.reserveMb)
    putBoolean(K_GPS_ON, s.gps.enabled)
    putLong(K_GPS_INTERVAL, s.gps.intervalMs)
}
