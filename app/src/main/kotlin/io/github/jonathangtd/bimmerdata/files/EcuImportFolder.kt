/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.files

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/**
 * The app's own folder on the shared storage, `Android/data/<package>/files/ecu-import/`, where
 * the user (or a script) can drop the EDIABAS Ecu folder with `adb push`; the REST API imports it
 * from there ([EcuFileStore.startDeviceImport]). The app reads it without any storage permission
 * or folder picker. It is removed with the app, and may be emptied once the files are imported.
 */
object EcuImportFolder {
    const val NAME = "ecu-import"

    /** The folder (created if needed); null when the shared storage is not available. */
    fun dir(context: Context): File? {
        val base = context.applicationContext.getExternalFilesDir(null) ?: return null
        return File(base, NAME).also { it.mkdirs() }
    }

    /**
     * Bytes the app may still write to its private storage (free space plus cache the system can
     * clear), the figure the ECU file store keeps its reserve against.
     */
    fun allocatableBytes(context: Context): Long {
        val dir = context.applicationContext.filesDir
        return try {
            val sm = context.applicationContext.getSystemService(StorageManager::class.java)
            sm.getAllocatableBytes(sm.getUuidForPath(dir))
        } catch (_: Exception) {
            StatFs(dir.path).availableBytes
        }
    }

    /** Creates the folder in the background, so that `adb push` into it works right after the first start. */
    fun prepare(context: Context) {
        val app = context.applicationContext
        Thread({ runCatching { dir(app) } }, "bd-ecu-import-dir").apply { isDaemon = true }.start()
    }
}
