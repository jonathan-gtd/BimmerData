/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Where CSV recordings go: the folder the user picked in Settings › Recording (Storage Access
 * Framework tree, permission persisted), or `Documents/BimmerData/` through MediaStore
 * ([CsvFiles]) when none is chosen or its permission was revoked.
 */
object RecordingStorage {

    data class Recording(val name: String, val uri: Uri, val sizeBytes: Long, val modifiedMs: Long)

    /** The chosen folder if it is still writable, else null (= default folder). */
    fun chosenFolder(context: Context): Uri? {
        val s = AppSettings.get(context).state.value.recording.folderUri ?: return null
        val uri = s.toUri()
        val ok = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (ok) uri else null
    }

    /** Human-readable folder, e.g. "Download/Car logs" or "Documents/BimmerData/". */
    fun describe(context: Context): String {
        val tree = chosenFolder(context) ?: return CsvFiles.RelativePath
        return AppSettings.get(context).state.value.recording.folderLabel ?: label(tree)
    }

    /** Takes the permission of a folder picked with ACTION_OPEN_DOCUMENT_TREE and stores it. */
    fun choose(context: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        val old = AppSettings.get(context).state.value.recording.folderUri
        AppSettings.get(context).updateRecording { it.copy(folderUri = tree.toString(), folderLabel = label(tree)) }
        if (old != null && old != tree.toString()) release(context, old.toUri())
    }

    /** Back to `Documents/BimmerData/`. */
    fun reset(context: Context) {
        val old = AppSettings.get(context).state.value.recording.folderUri
        AppSettings.get(context).updateRecording { it.copy(folderUri = null, folderLabel = null) }
        if (old != null) release(context, old.toUri())
    }

    private fun release(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /** "primary:Download/Car logs" -> "Download/Car logs". */
    fun label(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        val path = id.substringAfter(':', id)
        return path.ifEmpty { id.substringBefore(':') } + "/"
    }

    /** Creates [fileName]; [onPublished] gets its content Uri once the stream is closed. */
    fun create(context: Context, fileName: String, onPublished: (Uri) -> Unit = {}): OutputStream {
        val tree = chosenFolder(context) ?: return CsvFiles.create(context, fileName, onPublished)
        val resolver = context.applicationContext.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = try {
            DocumentsContract.createDocument(resolver, parent, CsvSessionRecorder.MimeType, fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot create $fileName in $tree, using ${CsvFiles.RelativePath}", e)
            null
        } ?: return CsvFiles.create(context, fileName, onPublished)
        val out = resolver.openOutputStream(doc, "w") ?: throw IOException("Cannot open $doc")
        return object : FilterOutputStream(out) {
            private var closed = false

            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } finally {
                    onPublished(doc)
                }
            }
        }
    }

    /** CSV files of the current folder, newest first. */
    fun list(context: Context): List<Recording> {
        val tree = chosenFolder(context)
        val out = if (tree != null) listTree(context, tree) else listMediaStore(context)
        return out.sortedByDescending { it.modifiedMs }
    }

    fun open(context: Context, name: String): InputStream? {
        val r = list(context).firstOrNull { it.name == name } ?: return null
        return runCatching { context.contentResolver.openInputStream(r.uri) }.getOrNull()
    }

    private fun listTree(context: Context, tree: Uri): List<Recording> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Recording>()
        runCatching {
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".csv", ignoreCase = true)) continue
                    out += Recording(name, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getLong(2), c.getLong(3))
                }
            }
        }.onFailure { Log.w(TAG, "Cannot list $tree", it) }
        return out
    }

    /** CSV files of Documents/BimmerData/ that this installation created (visible without storage permission). */
    private fun listMediaStore(context: Context): List<Recording> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val out = ArrayList<Recording>()
        runCatching {
            context.contentResolver.query(collection, projection, "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(CsvFiles.RelativePath), null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".csv", ignoreCase = true)) continue
                    out += Recording(name, ContentUris.withAppendedId(collection, c.getLong(0)), c.getLong(2), c.getLong(3) * 1000)
                }
            }
        }
        return out
    }

    private const val TAG = "RecordingStorage"
}
