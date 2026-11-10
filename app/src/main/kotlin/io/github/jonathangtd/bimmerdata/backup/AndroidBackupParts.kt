/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.backup

import android.annotation.SuppressLint
import android.content.Context
import io.github.jonathangtd.bimmerdata.i18n.TranslationRepository
import io.github.jonathangtd.bimmerdata.i18n.TranslationSettings
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import io.github.jonathangtd.bimmerdata.settings.SecretStore
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import java.io.File
import java.util.Locale

/** [BackupParts] on the app's real stores (garage, profiles, settings, translation cache). Blocking: call off the main thread. */
class AndroidBackupParts(context: Context) : BackupParts {
    private val app = context.applicationContext
    private val vehicles = VehicleRepository.get(app)
    private val profiles = ProfileStore.get(app)
    private val settings = AppSettings.get(app)
    private val translationSettings = TranslationSettings.get(app)
    private val translations = TranslationRepository.get(app)
    private val live = LiveRepository.get(app)

    override fun prepare() {
        if (vehicles.state.value.busy) throw BackupException("busy", "An operation on the car is running (detection, fault read): wait for it to end")
        if (live.connection.value != LiveConnection.Disconnected) {
            live.disconnect()
            val until = System.currentTimeMillis() + 10_000
            while (live.connection.value != LiveConnection.Disconnected && System.currentTimeMillis() < until) Thread.sleep(50)
            if (live.connection.value != LiveConnection.Disconnected) throw BackupException("busy", "The app could not disconnect from the car; try again")
        }
    }

    override fun capture(limitTranslations: Boolean): BackupContent {
        val all = cachedFiles()
        val size = all.sumOf { it.csv.length.toLong() }
        val keep = !limitTranslations || size <= BackupCodec.MAX_TRANSLATION_BYTES
        return BackupContent(
            garage = vehicles.garage(),
            profiles = profiles.load(),
            settings = BackupSettings.of(settings.state.value),
            translations = BackupTranslations.of(translationSettings.state.value, if (keep) all else null, if (keep) 0 else all.size),
        )
    }

    private fun cachedFiles(): List<BackupTranslationFile> {
        val root = translations.cacheRoot
        return root.listFiles().orEmpty().filter { it.isDirectory && BackupTranslations.LANG.matches(it.name) }.sortedBy { it.name }.flatMap { dir ->
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".csv") && BackupTranslations.SGBD.matches(it.name.removeSuffix(".csv")) }
                .sortedBy { it.name }
                .mapNotNull { f -> runCatching { BackupTranslationFile(dir.name, f.name.removeSuffix(".csv"), f.readText(Charsets.UTF_8)) }.getOrNull() }
        }
    }

    override fun applyGarage(garage: Garage) {
        try {
            vehicles.restoreGarage(garage)
        } catch (e: IllegalStateException) {
            throw BackupException("busy", e.message ?: "busy", e)
        }
    }

    override fun applyProfiles(profiles: ProfileSet) {
        this.profiles.update { profiles } ?: throw BackupException("storage_error", "the profiles could not be written")
    }

    override fun applySettings(settings: BackupSettings, includeApi: Boolean) {
        this.settings.update { settings.applyTo(it, includeApi) }
    }

    override fun applyTranslations(translations: BackupTranslations) {
        translationSettings.update { translations.applyTo(it) }
        val files = translations.files ?: return
        val root = this.translations.cacheRoot
        val parent = root.parentFile ?: throw BackupException("storage_error", "no app storage")
        val stamp = System.currentTimeMillis()
        val staging = File(parent, "translations.restore-$stamp")
        val old = File(parent, "translations.old-$stamp")
        try {
            staging.deleteRecursively()
            for (f in files) {
                val dir = File(staging, f.language.lowercase(Locale.ROOT)).apply { mkdirs() }
                File(dir, f.sgbd.lowercase(Locale.ROOT) + ".csv").writeText(f.csv, Charsets.UTF_8)
            }
            staging.mkdirs()
            if (root.exists() && !root.renameTo(old)) throw BackupException("storage_error", "the translation files could not be replaced")
            if (!staging.renameTo(root)) {
                old.renameTo(root)
                throw BackupException("storage_error", "the translation files could not be replaced")
            }
            old.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
        this.translations.reloadCache()
    }

    override fun telegrafSecretSet(): Boolean {
        val secrets = SecretStore.get(app)
        return secrets.has(SecretStore.TELEGRAF_TOKEN) || secrets.has(SecretStore.TELEGRAF_PASSWORD)
    }
}

/** Export and restore of the whole app (Settings › Backup and the REST API). Blocking: call off the main thread. */
class BackupManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val restorer by lazy { BackupRestorer(AndroidBackupParts(app)) }
    private val lock = Any()

    fun info(nowMs: Long = System.currentTimeMillis()): BackupInfo {
        val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        return BackupInfo(pkg?.versionName, pkg?.longVersionCode, nowMs)
    }

    /** The backup file as text. */
    fun export(): String = synchronized(lock) { restorer.export(info()) }

    /** Replaces everything with the backup [text]; [includeApi] on the phone only. Throws [BackupException]. */
    fun restore(text: String, includeApi: Boolean, dryRun: Boolean = false): RestoreSummary = synchronized(lock) { restorer.restore(text, includeApi, dryRun) }

    companion object {
        // holds only the application context
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: BackupManager? = null

        fun get(context: Context): BackupManager =
            instance ?: synchronized(this) { instance ?: BackupManager(context).also { instance = it } }
    }
}
