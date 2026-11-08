/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import android.content.Context
import android.util.Log
import io.github.jonathangtd.bimmerdata.R
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
 * The layout as JSON in app storage. Several writers may use it at the same time (the phone
 * editor, the REST API, Android Auto reads): every read and write goes through one process-wide
 * lock, and a write goes to a temporary file that is synced and then renamed over the layout, so
 * a reader or a crash never sees a half-written file.
 *
 * Writers that change the layout based on its current content should use [update] (read, change
 * and write under the lock), not [load] then [save]. Every successful write is published in
 * [saved]; the phone dashboard and Android Auto follow it.
 */
class DashboardStore(
    private val file: File,
    private val defaultLayout: () -> Dashboard,
    private val warn: (String, Throwable) -> Unit = { msg, e -> Log.w(TAG, msg, e) },
) {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME), { defaultDashboard(context) })

    private val tmp = File(file.path + ".tmp")

    /** Written by the previous androidx AtomicFile implementation while a save was in progress. */
    private val legacyBackup = File(file.path + ".bak")

    /**
     * The saved dashboard (an existing layout is never changed), or on first launch (or if the
     * file is unreadable) the default one: an empty page on the phone and one in Android Auto.
     */
    fun load(): Dashboard = lock.withLock { read() ?: defaultLayout() }

    /** Writes [dashboard]; returns false (and logs) if it could not be written. */
    fun save(dashboard: Dashboard): Boolean = lock.withLock { write(dashboard) }

    /**
     * Reads the current layout, applies [transform] and writes the result, with no other write in
     * between (e.g. the API adding a card while the editor saves). Returns the written layout, or
     * null if it could not be written.
     */
    fun update(transform: (Dashboard) -> Dashboard): Dashboard? = lock.withLock {
        val next = transform(read() ?: defaultLayout())
        if (write(next)) next else null
    }

    private fun read(): Dashboard? {
        val source = when {
            // an AtomicFile write was interrupted (old app version): the backup is the last good file
            legacyBackup.isFile -> legacyBackup
            file.isFile -> file
            else -> return null
        }
        return try {
            DashboardJson.decode(source.readText(Charsets.UTF_8), requirePages = false)
        } catch (e: Exception) {
            warn("Unreadable ${source.name}, using the default layout", e)
            null
        }
    }

    private fun write(dashboard: Dashboard): Boolean = try {
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(DashboardJson.encode(dashboard).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        legacyBackup.delete()
        _saved.value = dashboard
        true
    } catch (e: Exception) {
        tmp.delete()
        warn("Could not save the dashboard", e)
        false
    }

    companion object {
        private const val TAG = "DashboardStore"
        const val FILE_NAME = "dashboard.json"

        /** One lock for every store instance of the process: there is a single layout file. */
        private val lock = ReentrantLock()

        private val _saved = MutableStateFlow<Dashboard?>(null)

        /**
         * Last layout written in this process, by any writer (phone editor, REST API...). Android
         * Auto and the phone dashboard follow it.
         */
        val saved: StateFlow<Dashboard?> = _saved.asStateFlow()


        @Suppress("UNUSED_PARAMETER")
        fun defaultDashboard(context: Context): Dashboard = DefaultDashboard.create()

    }
}
