/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.profiles

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The profiles (car, ECUs, pages of each) as JSON in app storage, `files/profiles.json`.
 *
 * Several writers may use it at the same time (phone editor, REST API, Android Auto, the automatic
 * profile choice): every read and write goes through one process-wide lock, and a write goes to a
 * temporary file that is synced and then renamed over the file, so a reader or a crash never sees
 * a half-written file. Every write is published in [state] and, for the active layout, in
 * [activeDashboard] (followed by the phone dashboard and Android Auto).
 *
 * **Migration**: before profiles, the layout was `files/dashboard.json`. When `profiles.json` does
 * not exist yet and that layout has pages or a name, it becomes the first profile (named after
 * the layout, else [defaultName]) and `profiles.json` is written at once. `dashboard.json` is left
 * as it was (a backup the app no longer reads). An empty old layout gives no profile: the first
 * launch stays empty.
 */
class ProfileStore(
    private val dir: File,
    private val defaultName: () -> String = { "My profile" },
    private val warn: (String, Throwable?) -> Unit = { msg, e -> Log.w(TAG, msg, e) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val info: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val file = File(dir, FILE_NAME)
    private val tmp = File(dir, "$FILE_NAME.tmp")
    private val legacy = File(dir, LEGACY_FILE_NAME)

    /** Written by an old app version (androidx AtomicFile) while a save of the layout was in progress. */
    private val legacyBackup = File(dir, "$LEGACY_FILE_NAME.bak")

    /** Name of a profile created without one (migration, REST API write without a profile). */
    fun defaultProfileName(): String = defaultName()

    fun now(): Long = clock()

    /** The profiles (migrating the old layout on the first call). */
    fun load(): ProfileSet = lock.withLock {
        val set = read()
        if (_state.value == null) publish(set)
        set
    }

    /**
     * Reads the profiles, applies [transform] and writes the result, with no other write in
     * between. Returns the written set, or null if it could not be written.
     */
    fun update(transform: (ProfileSet) -> ProfileSet): ProfileSet? = lock.withLock {
        val current = read()
        val next = transform(current)
        if (next == current) {
            if (_state.value == null) publish(current)
            return@withLock current
        }
        if (write(next)) next else null
    }

    private fun read(): ProfileSet {
        if (file.isFile) {
            return try {
                ProfileJson.decode(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                // keep the unreadable file aside: the next write must not erase the user's work
                val aside = File(dir, "$FILE_NAME.unreadable-${clock()}")
                warn("Unreadable $FILE_NAME, kept as ${aside.name}; starting without profiles", e)
                runCatching { file.renameTo(aside) }
                ProfileSet()
            }
        }
        val old = readLegacy() ?: return ProfileSet()
        val hasContent = old.phonePages.isNotEmpty() || old.carPages.isNotEmpty() || !old.name.isNullOrBlank()
        if (!hasContent) return ProfileSet()
        val migrated = Profiles.create(ProfileSet(), old.name?.takeIf { it.isNotBlank() } ?: defaultName(), nowMs = clock(), dashboard = old).first
        if (write(migrated)) info("Layout of ${legacy.name} moved into the first profile '${migrated.active?.name}'")
        return migrated
    }

    private fun readLegacy(): Dashboard? {
        val source = when {
            legacyBackup.isFile -> legacyBackup
            legacy.isFile -> legacy
            else -> return null
        }
        return try {
            DashboardJson.decode(source.readText(Charsets.UTF_8), requirePages = false)
        } catch (e: Exception) {
            warn("Unreadable ${source.name}, not migrated", e)
            null
        }
    }

    private fun write(set: ProfileSet): Boolean = try {
        dir.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(ProfileJson.encode(set).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        publish(set)
        true
    } catch (e: Exception) {
        tmp.delete()
        warn("Could not save the profiles", e)
        false
    }

    private fun publish(set: ProfileSet) {
        _state.value = set
        _activeDashboard.value = set.activeDashboard()
    }

    // ---- convenience writes (each one read-modify-write under the lock) ----

    fun create(name: String, vehicle: ProfileVehicle, ecus: List<String>): Profile? {
        var created: Profile? = null
        update { set -> Profiles.create(set, name, vehicle, ecus, clock()).also { created = it.second }.first } ?: return null
        return created
    }

    fun activate(id: String): ProfileSet? = update { Profiles.activate(it, id, clock()) }

    fun duplicate(id: String): Profile? {
        var copy: Profile? = null
        update { set -> Profiles.duplicate(set, id, clock())?.also { copy = it.second }?.first ?: set } ?: return null
        return copy
    }

    fun edit(id: String, name: String?, vehicle: ProfileVehicle?, ecus: List<String>?): ProfileSet? =
        update { Profiles.edit(it, id, name, vehicle, ecus) }

    fun delete(id: String): ProfileSet? = update { Profiles.delete(it, id) }

    /** Puts a whole set back (undo of a delete). */
    fun restore(set: ProfileSet): ProfileSet? = update { set }

    fun import(text: String, fallbackName: String): Profile {
        val export = ProfileJson.parseExport(text)
        var imported: Profile? = null
        update { set -> ProfileJson.importInto(set, export, clock(), fallbackName).also { imported = it.second }.first }
            ?: throw IllegalStateException("Could not save the profiles")
        return imported!!
    }

    companion object {
        private const val TAG = "ProfileStore"
        const val FILE_NAME = "profiles.json"
        const val LEGACY_FILE_NAME = "dashboard.json"

        /** One lock for every store instance of the process: there is a single profile file. */
        private val lock = ReentrantLock()

        private val _state = MutableStateFlow<ProfileSet?>(null)

        /** The profiles as last read or written in this process (null before the first read). */
        val state: StateFlow<ProfileSet?> = _state.asStateFlow()

        private val _activeDashboard = MutableStateFlow<Dashboard?>(null)

        /** Layout of the active profile, after every read or write (null before the first one). */
        val activeDashboard: StateFlow<Dashboard?> = _activeDashboard.asStateFlow()

        // holds only the application context
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ProfileStore? = null

        fun get(context: Context): ProfileStore {
            instance?.let { return it }
            val app = context.applicationContext
            return synchronized(this) {
                instance ?: ProfileStore(app.filesDir, { app.getString(R.string.profile_default_name) }).also { instance = it }
            }
        }
    }
}
