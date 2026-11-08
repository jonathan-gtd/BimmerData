/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream

/**
 * On-disk queue of line-protocol lines for the Telegraf export. **It never deletes a point by
 * itself**: a point leaves the phone only once Telegraf answered 2xx for its batch.
 *
 * - Lines are appended to an open segment `seg-<n>.lp`. Once it reaches [segmentBytes] (raw), it
 *   is closed: gzip-compressed (about 10× smaller) into `seg-<n>-<lines>-<rawBytes>.lp.gz`, synced
 *   to disk (fsync), and only then the raw file is deleted. A closed segment is one upload batch,
 *   sent as-is with `Content-Encoding: gzip`.
 * - [remove] (after a 2xx) is the only way a batch is deleted. [reject] (HTTP 400: the server
 *   will never accept it) moves it to `rejected/`, where it stays until the user retries
 *   ([retryRejected]), exports or deletes it by hand.
 * - Space: the only limit is the free space of the storage, minus [reserveBytes]. When the next
 *   append would eat into the reserve, the lines are **not queued** (the queue stops growing,
 *   nothing already queued is touched): [append] returns false and [State.blocked] /
 *   [State.refusedLines] say so. The CSV recording, independent of this queue, keeps the data.
 * - Survives process death: at start every segment is reloaded; a raw segment cut by a crash is
 *   trimmed to its last whole line and compressed; a half-written `.gz.tmp` is dropped (its raw
 *   file is still there).
 *
 * Thread safe.
 */
class LineQueue(
    private val dir: File,
    /** Raw size from which the open segment is closed (one upload batch). */
    private val segmentBytes: Long = 256 * 1024,
    /** Free space of the storage holding [dir], in bytes. */
    private val freeSpace: () -> Long = { dir.usableSpace },
    reserveBytes: Long = DEFAULT_RESERVE_BYTES,
) {
    data class State(
        val lines: Long = 0,
        /** Size on disk (closed segments compressed). */
        val bytes: Long = 0,
        /** Uncompressed size of the queued lines. */
        val rawBytes: Long = 0,
        /** Lines not queued because the storage reached the reserve (never queued, not deleted). */
        val refusedLines: Long = 0,
        /** Batches refused by the server for good (HTTP 400), kept in `rejected/`. */
        val rejectedBatches: Int = 0,
        val rejectedLines: Long = 0,
        /** The storage reached the reserve: new points are not queued until space is freed. */
        val blocked: Boolean = false,
        val freeBytes: Long = 0,
        val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    )

    /** One upload batch: a closed segment, gzip-compressed. */
    class Batch internal constructor(internal val file: File, val body: ByteArray, val lines: Int) {
        /** [body] is gzip data (send it with `Content-Encoding: gzip`). */
        val gzip: Boolean get() = true
    }

    private class Segment(val file: File, val seq: Long, val bytes: Long, val lines: Int, val rawBytes: Long)

    private class Open(val file: File, val seq: Long, var bytes: Long, var lines: Int)

    /** Space kept free on the storage; queuing stops (without deleting anything) below it. */
    @Volatile var reserveBytes: Long = reserveBytes.coerceAtLeast(0)
        set(value) {
            field = value.coerceAtLeast(0)
        }

    private val rejectedDir = File(dir, REJECTED)
    private val segments = ArrayList<Segment>() // sorted by seq
    private var open: Open? = null
    private var openStream: FileOutputStream? = null
    private var nextSeq = 0L
    private var refused = 0L
    private var blocked = false

    init {
        dir.mkdirs()
        rejectedDir.mkdirs()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }.orEmpty().forEach { it.delete() }
        val closed = dir.listFiles { f -> f.isFile && parseClosed(f.name) != null }.orEmpty()
        val closedSeqs = HashSet<Long>()
        for (f in closed) {
            val (seq, lines, raw) = parseClosed(f.name)!!
            segments += Segment(f, seq, f.length(), lines, raw)
            closedSeqs += seq
        }
        val raws = dir.listFiles { f -> f.isFile && parseOpen(f.name) != null }.orEmpty().sortedBy { parseOpen(it.name) }
        for (f in raws) {
            val seq = parseOpen(f.name)!!
            if (seq in closedSeqs) {
                f.delete() // crash after the compressed copy was complete
                continue
            }
            trimPartialLine(f)
            val lines = countLines(f)
            if (lines == 0) {
                f.delete()
                continue
            }
            segments += compress(f, seq, lines)
        }
        segments.sortBy { it.seq }
        val maxSeq = (segments.map { it.seq } + rejectedFiles().mapNotNull { parseClosed(it.name)?.first }).maxOrNull() ?: -1L
        nextSeq = maxSeq + 1
    }

    @Synchronized
    fun state(): State {
        val o = open
        val rejected = rejectedFiles()
        return State(
            lines = segments.sumOf { it.lines.toLong() } + (o?.lines ?: 0),
            bytes = segments.sumOf { it.bytes } + (o?.bytes ?: 0),
            rawBytes = segments.sumOf { it.rawBytes } + (o?.bytes ?: 0),
            refusedLines = refused,
            rejectedBatches = rejected.size,
            rejectedLines = rejected.sumOf { (parseClosed(it.name)?.second ?: 0).toLong() },
            blocked = blocked,
            freeBytes = freeSpace(),
            reserveBytes = reserveBytes,
        )
    }

    @Synchronized
    fun isEmpty(): Boolean = segments.isEmpty() && (open?.lines ?: 0) == 0

    /**
     * Appends [lines] (without line ends). Returns false, queuing nothing, when the storage would
     * go below [reserveBytes] (see [State.blocked]).
     */
    @Synchronized
    fun append(lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        val bytes = lines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)
        if (freeSpace() - bytes.size < reserveBytes) {
            blocked = true
            refused += lines.size
            return false
        }
        blocked = false
        val seg = open ?: newOpen()
        openStream!!.write(bytes)
        openStream!!.flush()
        seg.bytes += bytes.size
        seg.lines += lines.size
        if (seg.bytes >= segmentBytes) closeOpen()
        return true
    }

    /** Closes (compresses) the open segment, so the next [next] can send it. */
    @Synchronized
    fun seal() {
        if ((open?.lines ?: 0) > 0) closeOpen()
    }

    /** Oldest batch, or null. With [includeOpen], the open segment is closed and sent too. */
    @Synchronized
    fun next(includeOpen: Boolean): Batch? {
        if (segments.isEmpty() && includeOpen) seal()
        val seg = segments.firstOrNull() ?: return null
        return Batch(seg.file, seg.file.readBytes(), seg.lines)
    }

    /** Telegraf answered 2xx for [batch]: the only way a queued point is deleted. */
    @Synchronized
    fun remove(batch: Batch) {
        val i = segments.indexOfFirst { it.file == batch.file }
        if (i < 0) return
        segments.removeAt(i).file.delete()
    }

    /**
     * The server refused [batch] for good (HTTP 400): it moves to `rejected/` with [reason], out
     * of the upload loop, and stays there until the user acts.
     */
    @Synchronized
    fun reject(batch: Batch, reason: String) {
        val i = segments.indexOfFirst { it.file == batch.file }
        if (i < 0) return
        val seg = segments.removeAt(i)
        val target = File(rejectedDir, seg.file.name)
        if (!seg.file.renameTo(target)) {
            seg.file.copyTo(target, overwrite = true)
            seg.file.delete()
        }
        File(rejectedDir, seg.file.name + ".txt").writeText(reason + "\n")
    }

    /** Rejected batches (gzip line protocol), oldest first. */
    @Synchronized
    fun rejectedFiles(): List<File> =
        rejectedDir.listFiles { f -> f.isFile && parseClosed(f.name) != null }.orEmpty().sortedBy { parseClosed(it.name)!!.first }

    /** Puts every rejected batch back into the upload queue (e.g. after fixing the server). */
    @Synchronized
    fun retryRejected(): Int {
        val files = rejectedFiles()
        for (f in files) {
            val (seq, lines, raw) = parseClosed(f.name)!!
            val target = File(dir, f.name)
            if (!f.renameTo(target)) {
                f.copyTo(target, overwrite = true)
                f.delete()
            }
            File(rejectedDir, f.name + ".txt").delete()
            segments += Segment(target, seq, target.length(), lines, raw)
        }
        segments.sortBy { it.seq }
        return files.size
    }

    /** Deletes the rejected batches (only on the user's explicit request). */
    @Synchronized
    fun deleteRejected() {
        rejectedDir.listFiles().orEmpty().forEach { it.delete() }
    }

    /** Deletes every queued point (only on the user's explicit request). */
    @Synchronized
    fun clear() {
        runCatching { openStream?.close() }
        openStream = null
        open?.file?.delete()
        open = null
        segments.forEach { it.file.delete() }
        segments.clear()
        refused = 0
        blocked = false
    }

    /** Clears the "refused" counter (e.g. once the user saw it). */
    @Synchronized
    fun resetRefused() {
        refused = 0
    }

    @Synchronized
    fun close() {
        if ((open?.lines ?: 0) > 0) {
            closeOpen()
        } else {
            runCatching { openStream?.close() }
            openStream = null
            open?.file?.delete()
            open = null
        }
    }

    private fun newOpen(): Open {
        val seq = nextSeq++
        val f = File(dir, "seg-$seq.lp")
        val o = Open(f, seq, 0, 0)
        open = o
        openStream = FileOutputStream(f, true)
        return o
    }

    private fun closeOpen() {
        val o = open ?: return
        runCatching {
            openStream?.fd?.sync()
            openStream?.close()
        }
        openStream = null
        open = null
        segments += compress(o.file, o.seq, o.lines)
    }

    /** gzip [raw] into its closed name (via a synced .tmp file), then deletes [raw]. */
    private fun compress(raw: File, seq: Long, lines: Int): Segment {
        val rawBytes = raw.length()
        val target = File(dir, closedName(seq, lines, rawBytes))
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            GZIPOutputStream(fos, 64 * 1024).use { gz ->
                raw.inputStream().use { it.copyTo(gz, 64 * 1024) }
                gz.finish()
                fos.flush()
                fos.fd.sync()
            }
        }
        check(tmp.renameTo(target)) { "Cannot rename ${tmp.name}" }
        raw.delete()
        return Segment(target, seq, target.length(), lines, rawBytes)
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

    companion object {
        /** Megabyte as Android shows file sizes (SI): "500 MB" in the settings is 500 000 000 bytes. */
        const val MB = 1_000_000L
        const val DEFAULT_RESERVE_BYTES = 500 * MB
        const val REJECTED = "rejected"

        private val ClosedName = Regex("""seg-(\d+)-(\d+)-(\d+)\.lp\.gz""")
        private val OpenName = Regex("""seg-(\d+)\.lp""")

        internal fun closedName(seq: Long, lines: Int, rawBytes: Long) = "seg-$seq-$lines-$rawBytes.lp.gz"

        /** (seq, lines, raw bytes) of a closed segment file name. */
        internal fun parseClosed(name: String): Triple<Long, Int, Long>? =
            ClosedName.matchEntire(name)?.destructured?.let { (s, l, r) -> Triple(s.toLong(), l.toInt(), r.toLong()) }

        internal fun parseOpen(name: String): Long? = OpenName.matchEntire(name)?.groupValues?.get(1)?.toLong()
    }
}
