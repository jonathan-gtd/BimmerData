/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.backup

import io.github.jonathangtd.bimmerdata.api.Scope
import io.github.jonathangtd.bimmerdata.export.ExportMode
import io.github.jonathangtd.bimmerdata.i18n.EcuSelection
import io.github.jonathangtd.bimmerdata.i18n.TranslationConfig
import io.github.jonathangtd.bimmerdata.i18n.TranslationSource
import io.github.jonathangtd.bimmerdata.live.CsvSessionRecorder
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.profiles.ProfileJson
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.settings.ApiConfig
import io.github.jonathangtd.bimmerdata.settings.BluetoothSettings
import io.github.jonathangtd.bimmerdata.settings.GpsSettings
import io.github.jonathangtd.bimmerdata.settings.RecordingConfig
import io.github.jonathangtd.bimmerdata.settings.Settings
import io.github.jonathangtd.bimmerdata.settings.TelegrafAuthMode
import io.github.jonathangtd.bimmerdata.settings.TelegrafSettings
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import io.github.jonathangtd.bimmerdata.vehicle.GarageJson
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.util.Locale

/*
 * Full backup of the app (Settings › Backup, GET /api/v1/backup): everything needed to get the
 * app back as it was after a reinstall, in one JSON file. Pure JVM (tested without Android); the
 * Android side is [AndroidBackupParts].
 *
 * In it: the cars with their VIN, ECU lists, last detection and last fault read (the garage,
 * `files/vehicles.json`), every profile with its phone and Android Auto pages and its graphs
 * (`files/profiles.json`), the selected car and active profile, the settings and the cached
 * translation files. Never a secret: no API token, no Telegraf token / password, no translation
 * token, no key. Not in it either: the ECU files (hundreds of MB, imported again), the folders
 * picked with the Android folder picker (their permission does not survive a reinstall), the
 * Telegraf queue and the recordings.
 */

/** A backup could not be made or read; [code] is the API error code. */
class BackupException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Connection, recording, GPS, Telegraf and API settings of a backup (no secret). */
@Serializable
data class BackupSettings(
    val autoConnectOnCable: Boolean = false,
    /** usb, bluetooth or simulation. */
    val lastMode: String? = null,
    val bluetoothAddress: String? = null,
    val bluetoothName: String? = null,
    val bluetoothConnectOnStart: Boolean = false,
    val gpsEnabled: Boolean = false,
    val gpsIntervalMs: Long = 1000,
    val recordingRowIntervalMs: Long = 0,
    val recordingFilePrefix: String = CsvSessionRecorder.DefaultPrefix,
    val telegrafEnabled: Boolean = false,
    val telegrafUrl: String = "",
    /** none, token or basic. */
    val telegrafAuth: String = "token",
    val telegrafUsername: String = "",
    val telegrafCar: String = "",
    /** live or afterTrip. */
    val telegrafMode: String = "afterTrip",
    /** Settings › Local API without the token; restored on the phone only (never through the API itself). */
    val api: BackupApi? = null,
) {
    /** [current] with these settings; keeps what a backup never holds (token, picked folder). */
    fun applyTo(current: Settings, includeApi: Boolean): Settings = current.copy(
        autoConnectOnCable = autoConnectOnCable,
        lastMode = lastMode?.let { m -> LiveMode.entries.firstOrNull { it.name.equals(m, ignoreCase = true) } },
        bluetooth = BluetoothSettings(
            address = bluetoothAddress?.takeIf { MAC.matches(it) }?.uppercase(Locale.ROOT),
            name = bluetoothName?.take(80),
            connectOnStart = bluetoothConnectOnStart,
        ),
        gps = GpsSettings(enabled = gpsEnabled, intervalMs = gpsIntervalMs.takeIf { it in GpsSettings.INTERVALS } ?: 1000L),
        recording = current.recording.copy(
            rowIntervalMs = recordingRowIntervalMs.takeIf { it in RecordingConfig.ROW_INTERVALS } ?: 0L,
            filePrefix = CsvSessionRecorder.safePrefix(recordingFilePrefix),
        ),
        telegraf = TelegrafSettings(
            enabled = telegrafEnabled,
            url = telegrafUrl.take(2000),
            authMode = TelegrafAuthMode.entries.firstOrNull { it.name.equals(telegrafAuth, ignoreCase = true) } ?: TelegrafAuthMode.Token,
            username = telegrafUsername.take(200),
            car = telegrafCar.take(200),
            mode = if (telegrafMode.equals("live", ignoreCase = true)) ExportMode.Live else ExportMode.AfterTrip,
        ),
        api = if (includeApi && api != null) api.applyTo(current.api) else current.api,
    )

    companion object {
        private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        fun of(s: Settings) = BackupSettings(
            autoConnectOnCable = s.autoConnectOnCable,
            lastMode = s.lastMode?.name?.lowercase(Locale.ROOT),
            bluetoothAddress = s.bluetooth.address,
            bluetoothName = s.bluetooth.name,
            bluetoothConnectOnStart = s.bluetooth.connectOnStart,
            gpsEnabled = s.gps.enabled,
            gpsIntervalMs = s.gps.intervalMs,
            recordingRowIntervalMs = s.recording.rowIntervalMs,
            recordingFilePrefix = s.recording.filePrefix,
            telegrafEnabled = s.telegraf.enabled,
            telegrafUrl = s.telegraf.url,
            telegrafAuth = s.telegraf.authMode.name.lowercase(Locale.ROOT),
            telegrafUsername = s.telegraf.username,
            telegrafCar = s.telegraf.car,
            telegrafMode = if (s.telegraf.mode == ExportMode.Live) "live" else "afterTrip",
            api = BackupApi(s.api.enabled, s.api.port, s.api.scopes.map { it.id }.sorted(), s.api.https),
        )
    }
}

/** Settings › Local API, without the token (a restored phone keeps its own token). */
@Serializable
data class BackupApi(val enabled: Boolean = false, val port: Int = ApiConfig.DEFAULT_PORT, val scopes: List<String> = emptyList(), val https: Boolean = false) {
    fun applyTo(current: ApiConfig): ApiConfig = current.copy(
        enabled = enabled,
        port = port.takeIf { it in ApiConfig.PORT_RANGE } ?: ApiConfig.DEFAULT_PORT,
        scopes = scopes.mapNotNull { Scope.fromId(it) }.filter { it in ApiConfig.GRANTABLE }.toSet(),
        https = https,
    )
}

/** Translation settings (no token, no picked folder) and the cached translation files. */
@Serializable
data class BackupTranslations(
    val language: String = "",
    /** url or folder (the folder itself has to be picked again on the phone). */
    val source: String = "url",
    val baseUrl: String = TranslationConfig.DEFAULT_URL,
    /** auto or chosen. */
    val selection: String = "auto",
    val chosen: List<String> = emptyList(),
    /** The cached files; null = not in this backup (the cache on the phone is kept). */
    val files: List<BackupTranslationFile>? = null,
    /** Files left out because the cache was larger than [BackupCodec.MAX_TRANSLATION_BYTES]. */
    val filesOmitted: Int = 0,
) {
    fun applyTo(current: TranslationConfig): TranslationConfig = current.copy(
        language = language.takeIf { it.isEmpty() || LANG.matches(it) } ?: "",
        source = if (source.equals("folder", ignoreCase = true)) TranslationSource.Folder else TranslationSource.Url,
        baseUrl = baseUrl.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(2000) ?: TranslationConfig.DEFAULT_URL,
        selection = if (selection.equals("chosen", ignoreCase = true)) EcuSelection.Chosen else EcuSelection.Auto,
        chosen = chosen.map { it.lowercase(Locale.ROOT) }.filter { SGBD.matches(it) }.toSet(),
    )

    companion object {
        val LANG = Regex("^[a-z]{2,3}$")
        val SGBD = Regex("^[a-z0-9_]{1,40}$")

        fun of(c: TranslationConfig, files: List<BackupTranslationFile>?, omitted: Int = 0) = BackupTranslations(
            language = c.language,
            source = if (c.source == TranslationSource.Folder) "folder" else "url",
            baseUrl = c.baseUrl,
            selection = if (c.selection == EcuSelection.Chosen) "chosen" else "auto",
            chosen = c.chosen.sorted(),
            files = files,
            filesOmitted = omitted,
        )
    }
}

/** One cached translation file, `translations/<language>/<sgbd>.csv` in app storage. */
@Serializable
data class BackupTranslationFile(val language: String, val sgbd: String, val csv: String)

/** What a backup holds, decoded. */
data class BackupContent(
    val garage: Garage,
    val profiles: ProfileSet,
    val settings: BackupSettings,
    val translations: BackupTranslations,
)

/** Who made a backup and when (header of the file). */
data class BackupInfo(val appVersion: String?, val appVersionCode: Long?, val createdAtMs: Long)

/** The file format, version [BackupCodec.VERSION]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class BackupFileDto(
    @EncodeDefault val format: String = BackupCodec.FORMAT,
    @EncodeDefault val version: Int = BackupCodec.VERSION,
    val appVersion: String? = null,
    val appVersionCode: Long? = null,
    /** ISO-8601, UTC. */
    val createdAt: String? = null,
    val createdAtMs: Long = 0,
    /** Reminder for whoever opens the file: the VINs are in it, keep it private. */
    @EncodeDefault val note: String = BackupCodec.NOTE,
    /** The cars (with VIN), their ECU lists, last detection and last fault read; the selected car. */
    val garage: JsonElement,
    /** Every profile (car, ECUs, phone and Android Auto pages, graphs) and the active one. */
    val profiles: JsonElement,
    val settings: BackupSettings = BackupSettings(),
    val translations: BackupTranslations = BackupTranslations(),
)

object BackupCodec {
    const val FORMAT = "bimmerdata-backup"
    const val VERSION = 1

    /** Translation files beyond this total size are left out of a backup (they can be downloaded again). */
    const val MAX_TRANSLATION_BYTES = 4L * 1024 * 1024

    /** Largest backup file read (the API upload limit too). */
    const val MAX_FILE_BYTES = 16L * 1024 * 1024

    const val NOTE = "BimmerData backup: cars (with their VIN), profiles, graphs and settings. No token or password. Keep it private."

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(content: BackupContent, info: BackupInfo): String = json.encodeToString(
        BackupFileDto.serializer(),
        BackupFileDto(
            appVersion = info.appVersion,
            appVersionCode = info.appVersionCode,
            createdAt = Instant.ofEpochMilli(info.createdAtMs).toString(),
            createdAtMs = info.createdAtMs,
            garage = json.parseToJsonElement(GarageJson.encode(content.garage)),
            profiles = json.parseToJsonElement(ProfileJson.encode(content.profiles)),
            settings = content.settings,
            translations = content.translations,
        ),
    )

    /** Checks the header first, so that a wrong file gets a clear message. */
    fun decode(text: String): Pair<BackupContent, BackupInfo> {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw BackupException("not_a_backup", "Not a BimmerData backup: the file is not JSON", e)
        } as? JsonObject ?: throw BackupException("not_a_backup", "Not a BimmerData backup: the file is not a JSON object")
        val format = (root["format"] as? JsonPrimitive)?.contentOrNull
        val kind = (root["kind"] as? JsonPrimitive)?.contentOrNull
        if (format != FORMAT) {
            throw BackupException(
                "not_a_backup",
                if (kind == "bimmerdata-profile") "This is a profile file, not a backup: import it in Profiles › Import (or POST /api/v1/profiles/import)"
                else "Not a BimmerData backup: \"format\" must be \"$FORMAT\"",
            )
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
            ?: throw BackupException("invalid_backup", "Invalid backup: \"version\" is missing")
        if (version > VERSION) throw BackupException("newer_version", "This backup was made by a newer version of BimmerData (format $version); update the app first")
        if (version < 1) throw BackupException("invalid_backup", "Invalid backup: unknown version $version")
        val dto = try {
            json.decodeFromJsonElement(BackupFileDto.serializer(), root)
        } catch (e: SerializationException) {
            throw BackupException("invalid_backup", "Invalid backup: ${e.message?.lineSequence()?.firstOrNull()?.take(300)}", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException("invalid_backup", "Invalid backup: ${e.message?.take(300)}", e)
        }
        val garage = try {
            GarageJson.decode(dto.garage.toString())
        } catch (e: Exception) {
            throw BackupException("invalid_backup", "Invalid backup: the cars cannot be read (${e.message?.lineSequence()?.firstOrNull()?.take(200)})", e)
        }
        val profiles = try {
            ProfileJson.decode(dto.profiles.toString())
        } catch (e: Exception) {
            throw BackupException("invalid_backup", "Invalid backup: the profiles cannot be read (${e.message?.lineSequence()?.firstOrNull()?.take(200)})", e)
        }
        val carIds = garage.vehicles.map { it.id }.toSet()
        profiles.profiles.firstOrNull { it.carId !in carIds }?.let {
            throw BackupException("invalid_backup", "Invalid backup: profile '${it.name}' belongs to car '${it.carId}', which is not in the backup")
        }
        dto.translations.files?.firstOrNull { !BackupTranslations.LANG.matches(it.language) || !BackupTranslations.SGBD.matches(it.sgbd) }?.let {
            throw BackupException("invalid_backup", "Invalid backup: bad translation file name '${it.language.take(10)}/${it.sgbd.take(40)}'")
        }
        val content = BackupContent(garage, profiles, dto.settings, dto.translations)
        return content to BackupInfo(dto.appVersion, dto.appVersionCode, dto.createdAtMs)
    }

    /** `bimmerdata-backup-20261110-1432.json` (local time). */
    fun fileName(timeMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        "bimmerdata-backup-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.ofEpochMilli(timeMs).atZone(zone)) + ".json"
}

/** What a restore put back (also the answer of POST /api/v1/backup/restore). */
@Serializable
data class RestoreSummary(
    val cars: Int,
    val carsWithVin: Int,
    val carsScanned: Int,
    val profiles: Int,
    val graphs: Int,
    val activeProfile: String? = null,
    val selectedCar: String? = null,
    /** Translation files put back; null when the backup had none (the phone's cache was kept). */
    val translationFiles: Int? = null,
    /** True when Settings › Local API was restored too (phone only); false: kept as it was. */
    val apiSettingsRestored: Boolean,
    val createdAt: String? = null,
    val appVersion: String? = null,
    /** What still has to be done by hand (token, folders, ECU files). */
    val todo: List<String> = emptyList(),
    /** The same as codes ([Todo.code]): the phone shows them in its language. */
    val todoCodes: List<String> = emptyList(),
    /** True for a dry run: nothing was changed. */
    val dryRun: Boolean = false,
) {
    companion object {
        fun of(content: BackupContent, info: BackupInfo, includeApi: Boolean, dryRun: Boolean, telegrafSecretSet: Boolean): RestoreSummary {
            val g = content.garage
            val p = content.profiles
            val s = content.settings
            val codes = buildList {
                add(Todo.EcuFiles)
                if (s.telegrafEnabled && !s.telegrafAuth.equals("none", ignoreCase = true) && !telegrafSecretSet) add(Todo.TelegrafSecret)
                if (content.translations.source.equals("folder", ignoreCase = true)) add(Todo.TranslationFolder)
                add(Todo.RecordingFolder)
            }
            return RestoreSummary(
                cars = g.vehicles.size,
                carsWithVin = g.vehicles.count { it.hasVin },
                carsScanned = g.vehicles.count { it.scannedAtMs != null },
                profiles = p.profiles.size,
                graphs = p.profiles.sumOf { it.graphs.size },
                activeProfile = p.active?.name,
                selectedCar = g.current?.label,
                translationFiles = content.translations.files?.size,
                apiSettingsRestored = includeApi && s.api != null,
                createdAt = info.createdAtMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                appVersion = info.appVersion,
                todo = codes.map { it.text },
                todoCodes = codes.map { it.code },
                dryRun = dryRun,
            )
        }
    }
}

/** What a restore cannot put back by itself. */
enum class Todo(val code: String, val text: String) {
    EcuFiles("ecu_files", "ECU files are not in a backup: import the Ecu folder if the phone has none (Settings › Tools › Tool32, or POST /api/v1/ecu-files/import-from-device)"),
    TelegrafSecret("telegraf_secret", "Telegraf export: enter the token / password again (Settings › Telegraf export, or PUT /api/v1/export/telegraf/auth)"),
    TranslationFolder("translation_folder", "Translations: pick the translation folder again (Settings › Translations)"),
    RecordingFolder("recording_folder", "Recording folder: the default one is used; pick yours again in Settings › Recording if you had chosen one"),
    ;

    companion object {
        fun of(code: String): Todo? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The app's state as the backup sees it, part by part. [AndroidBackupParts] reads and writes the
 * real stores; the tests use a fake. Each apply replaces that part as a whole.
 */
interface BackupParts {
    /** Stops what could write meanwhile (the connection to the car); throws [BackupException] `busy` when an operation on the car runs. */
    fun prepare()

    /** The state now; [limitTranslations]: leave the translation files out beyond [BackupCodec.MAX_TRANSLATION_BYTES] (export), else all of them (rollback). */
    fun capture(limitTranslations: Boolean): BackupContent

    fun applyGarage(garage: Garage)
    fun applyProfiles(profiles: ProfileSet)
    fun applySettings(settings: BackupSettings, includeApi: Boolean)

    /** Translation settings, and the cache replaced by [BackupTranslations.files] unless that is null (cache kept). */
    fun applyTranslations(translations: BackupTranslations)

    /** A Telegraf token / password is stored on this phone. */
    fun telegrafSecretSet(): Boolean = false
}

/**
 * Restore = replace everything, all or nothing: the file is fully decoded and checked before
 * anything changes; then the parts are replaced one after the other and, if one fails, the
 * state captured just before is put back in every part.
 */
class BackupRestorer(private val parts: BackupParts) {
    fun export(info: BackupInfo): String = BackupCodec.encode(parts.capture(limitTranslations = true), info)

    /** [includeApi]: restore Settings › Local API too (only from the phone). [dryRun]: check and summarize only. */
    fun restore(text: String, includeApi: Boolean, dryRun: Boolean = false): RestoreSummary {
        val (content, info) = BackupCodec.decode(text)
        val summary = RestoreSummary.of(content, info, includeApi, dryRun, parts.telegrafSecretSet())
        if (dryRun) return summary
        parts.prepare()
        val before = parts.capture(limitTranslations = false)
        try {
            parts.applyGarage(content.garage)
            parts.applyProfiles(content.profiles)
            parts.applySettings(content.settings, includeApi)
            parts.applyTranslations(content.translations)
        } catch (e: Exception) {
            val rollback = runCatching {
                parts.applyGarage(before.garage)
                parts.applyProfiles(before.profiles)
                parts.applySettings(before.settings, includeApi = true)
                parts.applyTranslations(before.translations)
            }
            throw BackupException(
                "restore_failed",
                "The backup could not be restored (${e.message ?: e.javaClass.simpleName}); " +
                    if (rollback.isSuccess) "nothing was changed" else "the previous state could not be fully put back",
                e,
            )
        }
        return summary
    }
}
