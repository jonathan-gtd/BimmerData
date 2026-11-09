/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.files

import io.github.jonathangtd.bimmerdata.export.LineQueue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The imported EDIABAS ECU files (`filesDir/ecu`, the folder [EcuImporter] fills) managed without
 * the folder picker, for the REST API. Pure JVM.
 *
 * - [put]: one `.prg` / `.grp` file, streamed to a temporary file then moved in place (never held
 *   in memory, never half written under its final name).
 * - [startDeviceImport]: copies every ECU file of [importDir] (the app's own folder on the shared
 *   storage, reachable with `adb push` and readable without any permission) in the background.
 *   `replace` builds the new set aside and swaps it in only once complete, like the in-app import;
 *   `merge` adds and replaces files one by one. Files that are already there (same size and time)
 *   are not copied again, so calling it twice, or again after an interruption, is safe and fast.
 * - Names follow the in-app import (`.prg` / `.grp`, stored in lower case) and must be plain file
 *   names (no folder, no `..`, letters, digits, `_`, `-`, `.`); only regular files are read
 *   (symbolic links are skipped); the free space never goes below [reserveBytes].
 *
 * [beforeReplace] runs before a whole set is replaced or deleted (the app disconnects: the
 * interpreter reads the folder), [afterChange] after any change (caches, catalog).
 */
class EcuFileStore(
    filesDir: File,
    /** Folder read by [startDeviceImport]; null when there is none (no shared storage). */
    val importDir: File?,
    /** Bytes the app may still write to [filesDir] (on Android: StorageManager.getAllocatableBytes). */
    private val freeSpace: () -> Long,
    val reserveBytes: Long = LineQueue.DEFAULT_RESERVE_BYTES,
    val maxFileBytes: Long = MAX_FILE_BYTES,
    private val beforeReplace: () -> Unit = {},
    private val afterChange: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val runInBackground: (Runnable) -> Unit = { Thread(it, "bd-ecu-import").apply { isDaemon = true }.start() },
) {
    val ecuDir = File(filesDir, "ecu")
    private val uploadDir = File(filesDir, "ecu.upload")
    private val stagingDir = File(filesDir, "ecu.staging")
    private val oldDir = File(filesDir, "ecu.staging.old")

    private val lock = Any()
    private var activePuts = 0
    private val cancel = AtomicBoolean(false)

    @Volatile var deviceImport: DeviceImport = DeviceImport()
        private set

    enum class Mode { Replace, Merge }

    enum class Reason { Busy, InvalidName, TooLarge, InsufficientStorage, NotFound, NoImportDir, NoEcuFiles, Truncated }

    class EcuFileException(val reason: Reason, message: String) : IOException(message)

    // ---------------------------------------------------------------- read

    /** Imported files, sorted by name. */
    fun files(): List<EcuFile> =
        ecuDir.listFiles()?.filter { it.isFile && EcuImporter.isEcuFile(it.name) }?.map { EcuFile(it.name, it.length(), it.lastModified()) }?.sortedBy { it.name }.orEmpty()

    fun status(withFiles: Boolean = false): EcuFilesStatus {
        val list = files()
        val waiting = importDir?.let { dir -> runCatching { scan(dir).first.size }.getOrNull() }
        return EcuFilesStatus(
            fileCount = list.size,
            totalBytes = list.sumOf { it.sizeBytes },
            lastChangeMs = ecuDir.takeIf { it.isDirectory }?.lastModified()?.takeIf { it > 0 },
            freeBytes = freeSpace(),
            reserveBytes = reserveBytes,
            maxFileBytes = maxFileBytes,
            importDir = importDir?.absolutePath,
            importDirFiles = waiting,
            deviceImport = deviceImport,
            files = if (withFiles) list else null,
        )
    }

    // ---------------------------------------------------------------- one file

    /**
     * Stores [length] bytes of [input] as [rawName] (normalized with [normalizeName]); replaces a
     * file of that name. Returns the stored file and whether it replaced one.
     */
    fun put(rawName: String, input: InputStream, length: Long): Pair<EcuFile, Boolean> {
        val name = normalizeName(rawName) ?: throw invalidName(rawName)
        if (length <= 0) throw EcuFileException(Reason.Truncated, "Empty body: send the file content with its Content-Length")
        if (length > maxFileBytes) throw EcuFileException(Reason.TooLarge, "'$name' is larger than ${maxFileBytes / MB} MB")
        requireRoom(length)
        synchronized(lock) {
            if (deviceImport.phase == ImportPhase.Running) throw busy()
            activePuts++
        }
        try {
            uploadDir.mkdirs()
            val part = File(uploadDir, "$name.${UUID.randomUUID()}.part")
            try {
                var written = 0L
                part.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (written < length) {
                        val n = try {
                            input.read(buf, 0, minOf(buf.size.toLong(), length - written).toInt())
                        } catch (e: EOFException) {
                            -1
                        }
                        if (n < 0) throw EcuFileException(Reason.Truncated, "The body ended after $written of $length bytes; nothing was stored")
                        out.write(buf, 0, n)
                        written += n
                        if (written % CHECK_EVERY < n) requireRoom(length - written)
                    }
                }
                if (!ecuDir.isDirectory && !ecuDir.mkdirs()) throw IOException("Cannot create $ecuDir")
                val target = File(ecuDir, name)
                val replaced = target.exists()
                move(part, target)
                afterChange()
                return EcuFile(name, target.length(), target.lastModified()) to replaced
            } finally {
                part.delete()
            }
        } finally {
            synchronized(lock) { activePuts-- }
        }
    }

    /** Deletes one imported file; false when there is none of that name. */
    fun delete(rawName: String): Boolean {
        val name = normalizeName(rawName) ?: throw invalidName(rawName)
        synchronized(lock) { if (deviceImport.phase == ImportPhase.Running) throw busy() }
        val f = File(ecuDir, name)
        if (!f.isFile) return false
        if (!f.delete()) throw IOException("Cannot delete $name")
        afterChange()
        return true
    }

    /** Deletes every imported file; returns how many there were. */
    fun deleteAll(): Int {
        synchronized(lock) {
            if (deviceImport.phase == ImportPhase.Running || activePuts > 0) throw busy()
        }
        val count = files().size
        beforeReplace()
        ecuDir.deleteRecursively()
        stagingDir.deleteRecursively()
        uploadDir.deleteRecursively()
        afterChange()
        return count
    }

    // ---------------------------------------------------------------- import from the device folder

    /**
     * Starts copying the ECU files of [importDir] in the background and returns at once. While an
     * import runs, returns its progress instead of starting another one (second item: false).
     * Throws when there is nothing to import or not enough room.
     */
    fun startDeviceImport(mode: Mode, deleteSource: Boolean): Pair<DeviceImport, Boolean> {
        val dir = importDir ?: throw EcuFileException(Reason.NoImportDir, "This phone has no shared storage folder to import from")
        synchronized(lock) {
            if (deviceImport.phase == ImportPhase.Running) return deviceImport to false
            if (activePuts > 0) throw busy()
            dir.mkdirs()
            val (sources, skipped) = scan(dir)
            if (sources.isEmpty()) {
                throw EcuFileException(Reason.NoEcuFiles, "No .prg / .grp file in ${dir.absolutePath} (${skipped.size} other entr${if (skipped.size == 1) "y" else "ies"} skipped)")
            }
            val base = if (mode == Mode.Merge) ecuDir else stagingDir
            val upToDate = mode == Mode.Replace && sameSet(sources, ecuDir)
            val toCopy = if (upToDate) emptyList() else sources.filterNot { same(it.file, File(base, it.name)) }
            requireRoom(toCopy.sumOf { it.file.length() })
            cancel.set(false)
            deviceImport = DeviceImport(
                phase = ImportPhase.Running, mode = mode.name.lowercase(Locale.ROOT), deleteSource = deleteSource,
                filesTotal = sources.size, bytesTotal = sources.sumOf { it.file.length() },
                skipped = skipped.take(MAX_LISTED), skippedCount = skipped.size, startedAtMs = clock(),
            )
            runInBackground { runImport(dir, sources, mode, deleteSource) }
            return deviceImport to true
        }
    }

    /** Asks a running import to stop after the current file (a replace keeps the previous set). */
    fun cancelDeviceImport(): Boolean {
        if (deviceImport.phase != ImportPhase.Running) return false
        cancel.set(true)
        return true
    }

    private class Source(val file: File, val name: String)

    private fun runImport(root: File, sources: List<Source>, mode: Mode, deleteSource: Boolean) {
        var copied = 0
        var unchanged = 0
        var bytesDone = 0L
        var changedLive = false
        try {
            val target = if (mode == Mode.Merge) ecuDir else stagingDir
            if (mode == Mode.Replace && sameSet(sources, ecuDir)) {
                // the imported set is already exactly this folder: nothing to copy or swap
                unchanged = sources.size
                bytesDone = sources.sumOf { it.file.length() }
                stagingDir.deleteRecursively()
            } else {
                if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create $target")
                if (mode == Mode.Replace) {
                    // a staging folder left by an interrupted import is reused; drop what is not wanted any more
                    val wanted = sources.map { it.name }.toSet()
                    target.listFiles()?.filter { it.name !in wanted }?.forEach { it.deleteRecursively() }
                }
                uploadDir.mkdirs()
                for (s in sources) {
                    if (cancel.get()) throw IOException("Cancelled")
                    val dest = File(target, s.name)
                    deviceImport = deviceImport.copy(current = s.name)
                    if (same(s.file, dest)) {
                        unchanged++
                    } else {
                        requireRoom(s.file.length())
                        val part = File(uploadDir, "${s.name}.${UUID.randomUUID()}.part")
                        try {
                            s.file.inputStream().use { input -> part.outputStream().use { input.copyTo(it, BUFFER) } }
                            part.setLastModified(s.file.lastModified())
                            move(part, dest)
                        } finally {
                            part.delete()
                        }
                        copied++
                        if (mode == Mode.Merge) changedLive = true
                    }
                    bytesDone += s.file.length()
                    deviceImport = deviceImport.copy(filesDone = copied + unchanged, bytesDone = bytesDone, copied = copied, unchanged = unchanged)
                }
                if (mode == Mode.Replace) {
                    beforeReplace()
                    oldDir.deleteRecursively()
                    if (ecuDir.exists() && !ecuDir.renameTo(oldDir)) throw IOException("Cannot replace $ecuDir")
                    if (!stagingDir.renameTo(ecuDir)) {
                        oldDir.renameTo(ecuDir)
                        throw IOException("Cannot move the imported files in place")
                    }
                    oldDir.deleteRecursively()
                    changedLive = true
                }
            }
            if (changedLive) afterChange()
            var deleted = 0
            if (deleteSource) {
                for (s in sources) if (s.file.delete()) deleted++
                removeEmptyFolders(root)
            }
            deviceImport = deviceImport.copy(
                phase = ImportPhase.Done, filesDone = sources.size, bytesDone = bytesDone, copied = copied, unchanged = unchanged,
                current = null, sourceDeleted = deleted, finishedAtMs = clock(),
            )
        } catch (e: Exception) {
            if (changedLive) runCatching { afterChange() }
            deviceImport = deviceImport.copy(
                phase = ImportPhase.Failed, copied = copied, unchanged = unchanged, current = null,
                error = e.message ?: e.javaClass.simpleName, finishedAtMs = clock(),
            )
        }
    }

    /**
     * ECU files of [root] and its sub-folders (a pushed `Ecu` folder may land one level down), and
     * what was skipped with why. Symbolic links are never followed; names that occur twice keep
     * the shallowest file.
     */
    private fun scan(root: File): Pair<List<Source>, List<Skipped>> {
        val base = root.canonicalFile
        val found = ArrayList<Pair<String, File>>()
        val skipped = ArrayList<Skipped>()
        fun walk(dir: File, rel: String, depth: Int) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (f in children) {
                val path = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                val p = f.toPath()
                when {
                    Files.isSymbolicLink(p) -> skipped += Skipped(path, "symbolicLink")
                    Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ->
                        if (depth < MAX_DEPTH) walk(f, path, depth + 1) else skipped += Skipped(path, "tooDeep")
                    !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) -> skipped += Skipped(path, "notRegularFile")
                    !f.canonicalFile.path.startsWith(base.path + File.separator) -> skipped += Skipped(path, "outsideFolder")
                    !EcuImporter.isEcuFile(f.name) -> skipped += Skipped(path, "notEcuFile")
                    else -> found += path to f
                }
            }
        }
        walk(base, "", 0)
        val sources = ArrayList<Source>()
        val names = HashSet<String>()
        for ((path, f) in found.sortedWith(compareBy({ it.first.count { c -> c == '/' } }, { it.first }))) {
            val name = normalizeName(f.name)
            when {
                name == null -> skipped += Skipped(path, "invalidName")
                f.length() > maxFileBytes -> skipped += Skipped(path, "tooLarge")
                f.length() == 0L -> skipped += Skipped(path, "empty")
                !names.add(name) -> skipped += Skipped(path, "duplicate")
                else -> sources += Source(f, name)
            }
        }
        return sources.sortedBy { it.name } to skipped
    }

    private fun sameSet(sources: List<Source>, dir: File): Boolean {
        val present = dir.listFiles()?.filter { it.isFile && EcuImporter.isEcuFile(it.name) }?.map { it.name }?.toSet() ?: return false
        return present == sources.map { it.name }.toSet() && sources.all { same(it.file, File(dir, it.name)) }
    }

    /** Same size and modification time: the file was copied from this source already. */
    private fun same(src: File, dest: File): Boolean = dest.isFile && dest.length() == src.length() && dest.lastModified() == src.lastModified()

    private fun removeEmptyFolders(root: File) {
        fun clean(dir: File) {
            dir.listFiles()?.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }?.forEach { sub ->
                clean(sub)
                if (sub.listFiles()?.isEmpty() == true) sub.delete()
            }
        }
        clean(root)
    }

    private fun requireRoom(bytes: Long) {
        val free = freeSpace()
        if (free - bytes < reserveBytes) {
            throw EcuFileException(
                Reason.InsufficientStorage,
                "Not enough free space: ${free / MB} MB free, ${bytes / MB} MB needed and ${reserveBytes / MB} MB always kept free",
            )
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun busy() = EcuFileException(Reason.Busy, "An import of ECU files is running; wait until it is done (GET /api/v1/ecu-files)")

    private fun invalidName(raw: String) = EcuFileException(
        Reason.InvalidName,
        "'${raw.take(80)}' is not an ECU file name: a plain .prg or .grp file name (letters, digits, '_', '-', '.'), e.g. '<sgbd>.prg'",
    )

    companion object {
        const val MB = 1_000_000L

        /** Largest single ECU file accepted (real ones are well under 10 MB). */
        const val MAX_FILE_BYTES = 64 * MB
        const val MAX_LISTED = 100
        private const val MAX_DEPTH = 3
        private const val BUFFER = 256 * 1024
        private const val CHECK_EVERY = 4 * MB
        private val NAME = Regex("[a-z0-9_][a-z0-9_.\\-]{0,99}")

        /**
         * The stored name of an ECU file: lower case, a plain name with a `.prg` / `.grp`
         * extension; null when [raw] is not acceptable (folders, `..`, odd characters).
         */
        fun normalizeName(raw: String): String? {
            val name = raw.trim().lowercase(Locale.ROOT)
            if (!NAME.matches(name) || ".." in name || !EcuImporter.isEcuFile(name)) return null
            return name
        }
    }
}

@Serializable
data class EcuFile(val name: String, val sizeBytes: Long, val modifiedMs: Long)

@Serializable
data class Skipped(
    /** Path inside the import folder. */
    val path: String,
    /** notEcuFile, invalidName, duplicate, tooLarge, empty, symbolicLink, notRegularFile, outsideFolder, tooDeep */
    val reason: String,
)

@Serializable
enum class ImportPhase { @SerialName("idle") Idle, @SerialName("running") Running, @SerialName("done") Done, @SerialName("failed") Failed }

/** Progress and result of the last import from the device folder (this app run). */
@Serializable
data class DeviceImport(
    val phase: ImportPhase = ImportPhase.Idle,
    /** replace or merge */
    val mode: String? = null,
    val deleteSource: Boolean = false,
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    /** Files copied (new or changed). */
    val copied: Int = 0,
    /** Files already there with the same size and time (not copied again). */
    val unchanged: Int = 0,
    /** Entries of the folder that were not imported (first [EcuFileStore.MAX_LISTED]). */
    val skipped: List<Skipped> = emptyList(),
    val skippedCount: Int = 0,
    /** Source files deleted after the import (deleteSource). */
    val sourceDeleted: Int = 0,
    val current: String? = null,
    val error: String? = null,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
)

@Serializable
data class EcuFilesStatus(
    val fileCount: Int,
    val totalBytes: Long,
    /** Last import or change of the folder, epoch ms; absent when nothing was imported. */
    val lastChangeMs: Long? = null,
    val freeBytes: Long,
    val reserveBytes: Long,
    val maxFileBytes: Long,
    /** Folder read by POST /api/v1/ecu-files/import-from-device (absolute path on the phone). */
    val importDir: String? = null,
    /** ECU files waiting in [importDir]. */
    val importDirFiles: Int? = null,
    val deviceImport: DeviceImport,
    val files: List<EcuFile>? = null,
)
