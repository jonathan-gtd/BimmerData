/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Copies the user's EDIABAS Ecu folder (picked with ACTION_OPEN_DOCUMENT_TREE, no storage
 * permission) into app-private storage. Only *.prg / *.grp are taken; names are stored in lower
 * case so the interpreter finds them on the case-sensitive file system without scanning the
 * folder. A re-import replaces the previous set only once it is complete; cancelling keeps it.
 */
class EcuImporter(context: Context) {
    private val appContext = context.applicationContext

    val ecuDir: File = File(appContext.filesDir, "ecu")
    private val tmpDir = File(appContext.filesDir, "ecu.import")
    private val oldDir = File(appContext.filesDir, "ecu.old")

    data class Progress(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesTotal: Long, val current: String)

    private class Doc(val id: String, val name: String, val size: Long)

    /** Imported SGBD files (lower case names), sorted. */
    fun importedFiles(): List<String> =
        ecuDir.listFiles()?.filter { it.isFile && isEcuFile(it.name) }?.map { it.name }?.sorted() ?: emptyList()

    /** Runs on [Dispatchers.IO]; cancel the calling coroutine to abort. Returns the number of files copied. */
    suspend fun import(treeUri: Uri, onProgress: (Progress) -> Unit): Int = withContext(Dispatchers.IO) {
        val docs = listEcuDocuments(treeUri)
        if (docs.isEmpty()) throw IOException("No .prg / .grp file in the selected folder")
        tmpDir.deleteRecursively()
        if (!tmpDir.mkdirs()) throw IOException("Cannot create $tmpDir")
        try {
            val totalBytes = docs.sumOf { it.size.coerceAtLeast(0) }
            var bytesDone = 0L
            var lastReport = 0L
            val buffer = ByteArray(256 * 1024)
            val resolver = appContext.contentResolver
            docs.forEachIndexed { index, doc ->
                ensureActive()
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.id)
                val target = File(tmpDir, doc.name.lowercase(Locale.ROOT))
                (resolver.openInputStream(uri) ?: throw IOException("Cannot read ${doc.name}")).use { input ->
                    target.outputStream().use { output ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            bytesDone += n
                            ensureActive()
                        }
                    }
                }
                val now = System.nanoTime()
                if (now - lastReport > 100_000_000L || index == docs.size - 1) {
                    lastReport = now
                    onProgress(Progress(index + 1, docs.size, bytesDone, totalBytes, doc.name))
                }
            }
            // swap in the new set
            oldDir.deleteRecursively()
            if (ecuDir.exists() && !ecuDir.renameTo(oldDir)) throw IOException("Cannot replace $ecuDir")
            if (!tmpDir.renameTo(ecuDir)) {
                oldDir.renameTo(ecuDir)
                throw IOException("Cannot move the imported files in place")
            }
            oldDir.deleteRecursively()
            docs.size
        } catch (ex: Throwable) {
            tmpDir.deleteRecursively()
            throw ex
        }
    }

    /** Lists the folder with one query (DocumentFile would query once per file: slow with ~1600 files). */
    private fun listEcuDocuments(treeUri: Uri): List<Doc> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val docs = ArrayList<Doc>()
        appContext.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR || !isEcuFile(name)) continue
                docs.add(Doc(c.getString(0), name, if (c.isNull(3)) 0 else c.getLong(3)))
            }
        } ?: throw IOException("Cannot list the selected folder")
        return docs
    }

    companion object {
        fun isEcuFile(name: String): Boolean =
            name.endsWith(".prg", ignoreCase = true) || name.endsWith(".grp", ignoreCase = true)
    }
}
