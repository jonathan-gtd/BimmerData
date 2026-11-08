/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Bounded on-disk queue of line-protocol lines, so no point is lost when the network is down or
 * the server is only reachable at home ("after the trip" mode).
 *
 * - Lines are appended to an open segment file (`seg-<n>.lp`); a segment is closed once it
 *   reaches [segmentBytes], and a closed segment is one upload batch.
 * - When the total size exceeds [maxBytes], the oldest segments are deleted (counted in
 *   [State.droppedLines]): the most recent data is kept.
 * - Survives process death: at start every existing segment is reloaded (a line cut by a crash
 *   is trimmed off).
 *
 * Thread safe.
 */
class LineQueue(
    private val dir: File,
    private val segmentBytes: Long = 256 * 1024,
    private val maxBytes: Long = 50L * 1024 * 1024,
) {
    data class State(val lines: Long = 0, val bytes: Long = 0, val droppedLines: Long = 0)

    /** One upload batch: the content of one segment. */
    class Batch internal constructor(internal val file: File, val body: ByteArray, val lines: Int)

    private class Segment(val file: File, var bytes: Long, var lines: Int)

    private val segments = ArrayDeque<Segment>()
    private var open: Segment? = null
    private var openStream: FileOutputStream? = null
    private var nextSeq = 0L
    private var dropped = 0L

    init {
        dir.mkdirs()
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("seg-") && f.name.endsWith(".lp") }.orEmpty()
            .sortedBy { it.name.removePrefix("seg-").removeSuffix(".lp").toLongOrNull() ?: 0L }
        for (f in files) {
            trimPartialLine(f)
            val lines = countLines(f)
            if (lines == 0) {
                f.delete()
                continue
            }
            segments.addLast(Segment(f, f.length(), lines))
        }
        nextSeq = (files.mapNotNull { it.name.removePrefix("seg-").removeSuffix(".lp").toLongOrNull() }.maxOrNull() ?: -1L) + 1
    }

    @Synchronized
    fun state(): State {
        val all = segments + listOfNotNull(open)
        return State(all.sumOf { it.lines.toLong() }, all.sumOf { it.bytes }, dropped)
    }

    @Synchronized
    fun isEmpty(): Boolean = segments.isEmpty() && (open?.lines ?: 0) == 0

    /** Appends [lines] (without line ends). */
    @Synchronized
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        val bytes = lines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)
        val seg = open ?: newSegment()
        openStream!!.write(bytes)
        seg.bytes += bytes.size
        seg.lines += lines.size
        if (seg.bytes >= segmentBytes) closeOpen()
        enforceLimit()
    }

    /** Closes the open segment, so the next [next] can send it. */
    @Synchronized
    fun seal() {
        if ((open?.lines ?: 0) > 0) closeOpen()
    }

    /** Oldest batch, or null. With [includeOpen], the open segment is closed and sent too. */
    @Synchronized
    fun next(includeOpen: Boolean): Batch? {
        if (segments.isEmpty() && includeOpen) seal()
        val seg = segments.firstOrNull() ?: return null
        openStream?.flush()
        val body = seg.file.readBytes()
        return Batch(seg.file, body, seg.lines)
    }

    /** The batch was accepted (or rejected for good): forget it. */
    @Synchronized
    fun remove(batch: Batch) {
        val it = segments.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.file == batch.file) {
                it.remove()
                s.file.delete()
                return
            }
        }
    }

    /** Deletes everything. */
    @Synchronized
    fun clear() {
        openStream?.close()
        openStream = null
        open?.file?.delete()
        open = null
        segments.forEach { it.file.delete() }
        segments.clear()
        dropped = 0
    }

    @Synchronized
    fun close() {
        runCatching { openStream?.close() }
        openStream = null
        open?.let { if (it.lines > 0) segments.addLast(it) else it.file.delete() }
        open = null
    }

    private fun newSegment(): Segment {
        val f = File(dir, "seg-${nextSeq++}.lp")
        val s = Segment(f, 0, 0)
        open = s
        openStream = FileOutputStream(f, true)
        return s
    }

    private fun closeOpen() {
        runCatching { openStream?.close() }
        openStream = null
        open?.let { segments.addLast(it) }
        open = null
    }

    private fun enforceLimit() {
        var total = segments.sumOf { it.bytes } + (open?.bytes ?: 0)
        while (total > maxBytes && segments.isNotEmpty()) {
            val s = segments.removeFirst()
            s.file.delete()
            dropped += s.lines
            total -= s.bytes
        }
    }

    private fun trimPartialLine(f: File) {
        RandomAccessFile(f, "rw").use { raf ->
            var len = raf.length()
            while (len > 0) {
                raf.seek(len - 1)
                if (raf.read() == '\n'.code) break
                len--
            }
            if (len != raf.length()) raf.setLength(len)
        }
    }

    private fun countLines(f: File): Int {
        var n = 0
        f.inputStream().buffered().use { input ->
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code) n++
            }
        }
        return n
    }
}
