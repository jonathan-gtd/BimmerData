/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dev

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Debug builds only: hands a new build of the app to Android's package installer, so that a
 * development install can be recorded as "installed by Google Play, initiated by the package
 * installer". Android Auto only lists Car App Library apps installed that way in a real car;
 * `adb install -i com.android.vending` sets the installer name but leaves the shell as initiator.
 *
 * The developer copies the APK to `files/update/bimmerdata-update.apk` (`adb shell run-as`) and
 * starts the installer with `content://<applicationId>.update/bimmerdata-update.apk` and
 * Google Play as installer (tools/ or a local script). Only that one file is served, read only,
 * and only to the package installer. Not part of release builds.
 */
class UpdateApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val caller = callingPackage
        if (caller !in INSTALLERS) throw SecurityException("only the package installer may read the update (caller $caller)")
        if (uri.lastPathSegment != FILE_NAME) throw FileNotFoundException(uri.toString())
        val file = File(File(context!!.filesDir, DIR), FILE_NAME)
        if (!file.isFile) throw FileNotFoundException("no update waiting")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = APK_TYPE

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val DIR = "update"
        const val FILE_NAME = "bimmerdata-update.apk"
        const val APK_TYPE = "application/vnd.android.package-archive"
        val INSTALLERS = setOf("com.google.android.packageinstaller", "com.android.packageinstaller")
    }
}
