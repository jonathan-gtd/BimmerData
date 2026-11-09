/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.files

import io.github.jonathangtd.bimmerdata.files.EcuFileStore.EcuFileException
import io.github.jonathangtd.bimmerdata.files.EcuFileStore.Mode
import io.github.jonathangtd.bimmerdata.files.EcuFileStore.Reason
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** The ECU file store behind /api/v1/ecu-files: names, streamed uploads, free space, import from the device folder. */
class EcuFileStoreTest {
    @TempDir lateinit var tmp: File

    private var free = Long.MAX_VALUE / 2
    private var replaces = 0
    private var changes = 0
    private val pending = ArrayList<Runnable>()

    private val filesDir get() = File(tmp, "files")
    private val importDir get() = File(tmp, "shared/ecu-import")

    /** Background work runs when [runPending] is called (or at once with [inline]). */
    private fun store(inline: Boolean = true, maxFileBytes: Long = EcuFileStore.MAX_FILE_BYTES) = EcuFileStore(
        filesDir.apply { mkdirs() }, importDir,
        freeSpace = { free }, reserveBytes = 1_000, maxFileBytes = maxFileBytes,
        beforeReplace = { replaces++ }, afterChange = { changes++ },
        runInBackground = { if (inline) it.run() else pending += it },
    )

    private fun runPending() {
        val list = pending.toList()
        pending.clear()
        list.forEach { it.run() }
    }

    private fun source(path: String, content: String, mtime: Long = 1_700_000_000_000): File =
        File(importDir, path).apply { parentFile.mkdirs(); writeText(content); setLastModified(mtime) }

    private fun bytes(s: String) = s.toByteArray()

    @Test
    fun `names are plain prg or grp files, stored in lower case`() {
        assertEquals("msv70.prg", EcuFileStore.normalizeName("MSV70.PRG"))
        assertEquals("d_0012.grp", EcuFileStore.normalizeName(" D_0012.GRP "))
        assertEquals("ews-3.prg", EcuFileStore.normalizeName("ews-3.prg"))
        for (bad in listOf("../msv70.prg", "a/msv70.prg", "a\\msv70.prg", "..prg", ".msv70.prg", "msv70.txt", "msv70", "ms v70.prg", "msv70.prg\u0000", "", "/etc/passwd.prg", "ä.prg")) {
            assertNull(EcuFileStore.normalizeName(bad), bad)
        }
    }

    @Test
    fun `put streams the body to a temporary file and moves it in place`() {
        val s = store()
        val (file, replaced) = s.put("MSV70.PRG", ByteArrayInputStream(bytes("first")), 5)
        assertEquals("msv70.prg", file.name)
        assertFalse(replaced)
        assertEquals("first", File(s.ecuDir, "msv70.prg").readText())
        val (_, again) = s.put("msv70.prg", ByteArrayInputStream(bytes("second!")), 7)
        assertTrue(again)
        assertEquals("second!", File(s.ecuDir, "msv70.prg").readText())
        assertEquals(listOf("msv70.prg"), s.files().map { it.name })
        assertEquals(2, changes)
        assertEquals(0, File(filesDir, "ecu.upload").listFiles()?.size ?: 0, "no temporary file left")
    }

    @Test
    fun `a truncated or refused upload stores nothing`() {
        val s = store(maxFileBytes = 100)
        val truncated = assertThrows<EcuFileException> { s.put("a.prg", ByteArrayInputStream(bytes("abc")), 10) }
        assertEquals(Reason.Truncated, truncated.reason)
        assertEquals(Reason.TooLarge, assertThrows<EcuFileException> { s.put("a.prg", ByteArrayInputStream(ByteArray(0)), 101) }.reason)
        assertEquals(Reason.InvalidName, assertThrows<EcuFileException> { s.put("../a.prg", ByteArrayInputStream(bytes("x")), 1) }.reason)
        free = 1_050
        assertEquals(Reason.InsufficientStorage, assertThrows<EcuFileException> { s.put("a.prg", ByteArrayInputStream(ByteArray(60)), 60) }.reason)
        assertTrue(s.files().isEmpty())
        assertEquals(0, changes)
        assertEquals(0, File(filesDir, "ecu.upload").listFiles()?.size ?: 0)
    }

    @Test
    fun `the body is never read past its length`() {
        val s = store()
        val input = ByteArrayInputStream(bytes("0123456789"))
        s.put("a.prg", input, 4)
        assertEquals("0123", File(s.ecuDir, "a.prg").readText())
        assertEquals(6, input.available())
    }

    @Test
    fun `device import replaces the set, skips what is not an ECU file and resolves duplicates`() {
        val s = store()
        s.put("old.prg", ByteArrayInputStream(bytes("old")), 3)
        source("Ecu/MSV70.PRG", "engine")
        source("Ecu/D_0012.GRP", "group")
        source("Ecu/readme.txt", "notes")
        source("Ecu/backup/msv70.prg", "older engine") // same name deeper: skipped
        source(".hidden.prg", "x")
        val outside = File(tmp, "outside.prg").apply { writeText("secret") }
        Files.createSymbolicLink(File(importDir, "Ecu/link.prg").toPath(), outside.toPath())

        val (started, isNew) = s.startDeviceImport(Mode.Replace, deleteSource = false)
        assertTrue(isNew)
        assertEquals(2, started.filesTotal)
        val done = s.deviceImport
        assertEquals(ImportPhase.Done, done.phase, done.error)
        assertEquals(2, done.copied)
        assertEquals(listOf("d_0012.grp", "msv70.prg"), s.files().map { it.name })
        assertEquals("engine", File(s.ecuDir, "msv70.prg").readText())
        val reasons = done.skipped.associate { it.path to it.reason }
        assertEquals("notEcuFile", reasons["Ecu/readme.txt"])
        assertEquals("duplicate", reasons["Ecu/backup/msv70.prg"])
        assertEquals("symbolicLink", reasons["Ecu/link.prg"])
        assertEquals("invalidName", reasons[".hidden.prg"])
        assertEquals(1, replaces)
        assertTrue(File(importDir, "Ecu/MSV70.PRG").exists(), "sources kept without deleteSource")
        assertFalse(File(filesDir, "ecu.staging").exists())
    }

    @Test
    fun `calling the import twice copies nothing the second time`() {
        val s = store()
        source("msv70.prg", "engine")
        source("egs.prg", "gearbox")
        s.startDeviceImport(Mode.Replace, false)
        val changesAfterFirst = changes
        s.startDeviceImport(Mode.Replace, false)
        val second = s.deviceImport
        assertEquals(ImportPhase.Done, second.phase)
        assertEquals(0, second.copied)
        assertEquals(2, second.unchanged)
        assertEquals(1, replaces, "no swap when the set is the same")
        assertEquals(changesAfterFirst, changes)
    }

    @Test
    fun `while an import runs a second call returns its progress and uploads wait`() {
        val s = store(inline = false)
        source("msv70.prg", "engine")
        val (_, first) = s.startDeviceImport(Mode.Replace, false)
        val (progress, second) = s.startDeviceImport(Mode.Merge, true)
        assertTrue(first)
        assertFalse(second)
        assertEquals(ImportPhase.Running, progress.phase)
        assertEquals("replace", progress.mode)
        assertEquals(Reason.Busy, assertThrows<EcuFileException> { s.put("a.prg", ByteArrayInputStream(bytes("x")), 1) }.reason)
        assertEquals(Reason.Busy, assertThrows<EcuFileException> { s.deleteAll() }.reason)
        runPending()
        assertEquals(ImportPhase.Done, s.deviceImport.phase)
        assertEquals(1, s.files().size)
    }

    @Test
    fun `an interrupted replace keeps the previous set and goes on where it stopped`() {
        source("a.prg", "A")
        source("b.prg", "B")
        source("c.prg", "C")
        val s = store()
        s.put("old.prg", ByteArrayInputStream(bytes("old")), 3)
        // an earlier run (app killed) already staged a.prg with the source's time, and a file no longer wanted
        val staging = File(filesDir, "ecu.staging").apply { mkdirs() }
        File(staging, "a.prg").apply { writeText("A"); setLastModified(File(importDir, "a.prg").lastModified()) }
        File(staging, "stale.prg").writeText("not wanted any more")
        assertEquals(listOf("old.prg"), s.files().map { it.name }, "the previous set is still in use")
        s.startDeviceImport(Mode.Replace, false)
        val r = s.deviceImport
        assertEquals(ImportPhase.Done, r.phase, r.error)
        assertEquals(1, r.unchanged, "the file already staged is not copied again")
        assertEquals(2, r.copied)
        assertEquals(listOf("a.prg", "b.prg", "c.prg"), s.files().map { it.name })
    }

    @Test
    fun `cancelling keeps the previous set`() {
        val s = store(inline = false)
        s.put("old.prg", ByteArrayInputStream(bytes("old")), 3)
        source("a.prg", "A")
        s.startDeviceImport(Mode.Replace, false)
        assertTrue(s.cancelDeviceImport())
        runPending()
        assertEquals(ImportPhase.Failed, s.deviceImport.phase)
        assertEquals(listOf("old.prg"), s.files().map { it.name })
        assertFalse(s.cancelDeviceImport())
        // again: completes
        s.startDeviceImport(Mode.Replace, false)
        runPending()
        assertEquals(listOf("a.prg"), s.files().map { it.name })
    }

    @Test
    fun `merge keeps the other files and deleteSource empties the folder`() {
        val s = store()
        s.put("old.prg", ByteArrayInputStream(bytes("old")), 3)
        source("Ecu/new.prg", "new")
        source("Ecu/notes.txt", "kept")
        s.startDeviceImport(Mode.Merge, deleteSource = true)
        val r = s.deviceImport
        assertEquals(ImportPhase.Done, r.phase, r.error)
        assertEquals(1, r.sourceDeleted)
        assertEquals(listOf("new.prg", "old.prg"), s.files().map { it.name })
        assertEquals(0, replaces, "merge never swaps the folder")
        assertFalse(File(importDir, "Ecu/new.prg").exists())
        assertTrue(File(importDir, "Ecu/notes.txt").exists(), "files that were not imported stay")
        assertTrue(importDir.isDirectory)
    }

    @Test
    fun `nothing to import or no room is refused before anything is copied`() {
        val s = store()
        importDir.mkdirs()
        assertEquals(Reason.NoEcuFiles, assertThrows<EcuFileException> { s.startDeviceImport(Mode.Replace, false) }.reason)
        source("a.prg", "x".repeat(500))
        free = 1_200
        assertEquals(Reason.InsufficientStorage, assertThrows<EcuFileException> { s.startDeviceImport(Mode.Replace, false) }.reason)
        assertEquals(ImportPhase.Idle, s.deviceImport.phase)
        val noDir = EcuFileStore(filesDir, null, freeSpace = { free })
        assertEquals(Reason.NoImportDir, assertThrows<EcuFileException> { noDir.startDeviceImport(Mode.Replace, false) }.reason)
    }

    @Test
    fun `status, delete one and delete all`() {
        val s = store()
        s.put("a.prg", ByteArrayInputStream(bytes("aa")), 2)
        s.put("b.grp", ByteArrayInputStream(bytes("bbb")), 3)
        source("c.prg", "c")
        val st = s.status(withFiles = true)
        assertEquals(2, st.fileCount)
        assertEquals(5, st.totalBytes)
        assertEquals(1, st.importDirFiles)
        assertEquals(importDir.absolutePath, st.importDir)
        assertEquals(listOf("a.prg", "b.grp"), st.files!!.map { it.name })
        assertNull(s.status().files)
        assertTrue(s.delete("A.PRG"))
        assertFalse(s.delete("a.prg"))
        assertEquals(1, s.deleteAll())
        assertTrue(s.files().isEmpty())
        assertEquals(1, replaces)
    }

    @Test
    fun `uploaded content is exactly what was sent`() {
        val s = store()
        val data = ByteArray(1_000_000) { (it * 31 % 251).toByte() }
        val slow = object : InputStream() {
            private val inner = ByteArrayInputStream(data)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 777))
        }
        s.put("big.prg", slow, data.size.toLong())
        assertArrayEquals(data, File(s.ecuDir, "big.prg").readBytes())
    }
}
