/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.jonathangtd.bimmerdata.live.CsvFiles.RelativePath
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * CSV recordings in the shared `Documents/BimmerData/` folder through MediaStore (API 29+, no
 * storage permission). A file is created "pending" (invisible to other apps) and published when
 * its stream is closed, so a half-written file never shows up in file managers.
 */
object CsvFiles {
    val RelativePath: String = Environment.DIRECTORY_DOCUMENTS + "/BimmerData/"

    /** Creates [fileName] in [RelativePath]; [onPublished] gets its content Uri once the stream is closed. */
    fun create(context: Context, fileName: String, onPublished: (Uri) -> Unit = {}): OutputStream {
        val resolver = context.applicationContext.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, CsvSessionRecorder.MimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RelativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $RelativePath$fileName")
        val out = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open $uri")
        } catch (ex: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw ex
        }
        return object : FilterOutputStream(out) {
            private var closed = false

            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } finally {
                    val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    runCatching { resolver.update(uri, publish, null, null) }
                    onPublished(uri)
                }
            }
        }
    }

    /** "Share" chooser for a recording (e.g. to mail it or open it in a spreadsheet app). */
    fun shareIntent(uri: Uri, title: String = "Share recording"): Intent {
        // ClipData carries the read grant to the chosen app (needed for SAF document Uris too)
        val send = Intent(Intent.ACTION_SEND)
            .setType(CsvSessionRecorder.MimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** "Open with" intent (spreadsheet / text viewer). */
    fun viewIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, CsvSessionRecorder.MimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
