/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 *
 * The description block layout follows EdiabasLib (https://github.com/uholeschak/ediabaslib,
 * Ulrich Holeschak, GPL-3.0), file EdiabasLib/EdiabasNet.cs: ReadDescriptions, GetJobList.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.PrgFile
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** One documented argument or result of a job (ARG/ARGTYPE/ARGCOMMENT or RESULT/RESULTTYPE/RESULTCOMMENT). */
data class ItemDescription(val name: String, val type: String, val comments: List<String>) {
    /** "binary" results are raw telegrams; everything else is a number or a text. */
    val isBinary: Boolean get() = type.trim().equals("binary", ignoreCase = true)

    val isText: Boolean get() = type.trim().equals("string", ignoreCase = true)
}

/** Documentation of one job: what `_JOBCOMMENTS`, `_ARGUMENTS` and `_RESULTS` return for it. */
data class JobDescription(
    val name: String,
    val comments: List<String>,
    val args: List<ItemDescription>,
    val results: List<ItemDescription>,
)

/**
 * Self-description of an SGBD: the clear-text documentation block of the object file (header
 * offset 0x90), i.e. the data behind the EDIABAS virtual jobs `_JOBS`, `_JOBCOMMENTS`,
 * `_ARGUMENTS`, `_RESULTS` and the ECU / ECUCOMMENT lines of `_VERSIONINFO`, read without
 * loading the whole file (a few kB to ~900 kB per SGBD, against up to 5.5 MB for the file).
 *
 * [jobs] only lists documented jobs that also exist in the file's own job table. Jobs a file
 * inherits through USES (45 of 1251 SGBDs use "tmode") are not followed.
 */
class SgbdDescription(
    /** ECU line, e.g. "MSV70 fuer N52 / N52K" or "KOMBI E85"; may be empty. */
    val ecu: String,
    /** ECUCOMMENT lines joined with a space ("-" and blanks removed). */
    val ecuComment: String,
    val origin: String,
    val jobs: List<JobDescription>,
) {
    /** One-line description for pickers: ECU text, plus the ECU comment when it adds something. */
    val summary: String
        get() = listOf(ecu, ecuComment).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")

    companion object {
        private const val XorKey = 0xF7
        private const val JobEntrySize = 0x44

        /** Reads the description of [file]; null if it is not an EDIABAS object file. */
        fun read(file: File): SgbdDescription? = try {
            RandomAccessFile(file, "r").use { raf -> read(raf) }
        } catch (_: Exception) {
            null
        }

        private fun read(raf: RandomAccessFile): SgbdDescription? {
            val length = raf.length()
            if (length < 0x98) return null
            val header = ByteArray(0x98)
            raf.readFully(header)
            if (!String(header, 0, 16, Charsets.ISO_8859_1).startsWith("@EDIABAS OBJECT")) return null
            val jobNames = readJobNames(raf, leInt(header, 0x88), length)
            val descOffset = leInt(header, 0x90)
            if (descOffset < 0 || descOffset + 4L > length) return SgbdDescription("", "", "", emptyList())
            raf.seek(descOffset.toLong())
            val countBytes = ByteArray(4).also { raf.readFully(it) }
            val count = leInt(countBytes, 0)
            if (count <= 0 || descOffset + 4L + count > length) return SgbdDescription("", "", "", emptyList())
            val block = ByteArray(count)
            raf.readFully(block)
            for (i in block.indices) block[i] = (block[i].toInt() xor XorKey).toByte()
            return parse(block, jobNames)
        }

        /** Upper-case names of the job table (count in clear, entries XOR-ed), null if unreadable. */
        private fun readJobNames(raf: RandomAccessFile, offset: Int, length: Long): Set<String>? {
            if (offset < 0 || offset + 4L > length) return null
            raf.seek(offset.toLong())
            val count = leInt(ByteArray(4).also { raf.readFully(it) }, 0)
            if (count < 0 || count > 20000 || offset + 4L + count.toLong() * JobEntrySize > length) return null
            val buf = ByteArray(count * JobEntrySize)
            raf.readFully(buf)
            val names = HashSet<String>(count * 2)
            for (i in 0 until count) {
                val o = i * JobEntrySize
                var end = o
                while (end < o + 0x40 && (buf[end].toInt() xor XorKey) and 0xFF != 0) end++
                val name = ByteArray(end - o) { (buf[o + it].toInt() xor XorKey).toByte() }
                names += String(name, PrgFile.ENCODING).uppercase(Locale.ROOT)
            }
            return names
        }

        /**
         * Parses the decrypted block: "KEY:value" lines; a JOBNAME line starts a job, ARG and
         * RESULT lines start an item, *TYPE / *COMMENT lines belong to the current item.
         * Lines are cut at 1098 bytes like EdiabasNet.ReadDescriptions does.
         */
        internal fun parse(block: ByteArray, jobNames: Set<String>?): SgbdDescription {
            var ecu = ""
            val ecuComments = ArrayList<String>()
            var origin = ""
            val jobs = ArrayList<JobDescription>()

            var jobName: String? = null
            var jobComments = ArrayList<String>()
            var args = ArrayList<ItemDescription>()
            var results = ArrayList<ItemDescription>()
            var item: MutableItem? = null
            var itemIsArg = false

            fun closeItem() {
                val it = item ?: return
                val d = ItemDescription(it.name, it.type, it.comments)
                if (itemIsArg) args += d else results += d
                item = null
            }

            fun closeJob() {
                closeItem()
                val name = jobName ?: return
                if (jobNames == null || name.uppercase(Locale.ROOT) in jobNames) {
                    jobs += JobDescription(name, jobComments, args, results)
                }
                jobName = null
            }

            var start = 0
            val n = block.size
            while (start < n) {
                var end = start
                while (end < n && block[end] != '\n'.code.toByte() && end - start < 1098) end++
                var lineEnd = end
                if (lineEnd > start && block[lineEnd - 1] == '\r'.code.toByte()) lineEnd--
                val line = String(block, start, lineEnd - start, PrgFile.ENCODING)
                start = if (end < n && block[end] == '\n'.code.toByte()) end + 1 else end
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim().uppercase(Locale.ROOT)
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "JOBNAME" -> {
                        closeJob()
                        jobName = value
                        jobComments = ArrayList()
                        args = ArrayList()
                        results = ArrayList()
                    }
                    "JOBCOMMENT" -> if (jobName != null) jobComments += value
                    "ARG", "RESULT" -> if (jobName != null) {
                        closeItem()
                        item = MutableItem(value)
                        itemIsArg = key == "ARG"
                    }
                    "ARGTYPE", "RESULTTYPE" -> item?.let { if ((key == "ARGTYPE") == itemIsArg) it.type = value }
                    "ARGCOMMENT", "RESULTCOMMENT" -> item?.let { if ((key == "ARGCOMMENT") == itemIsArg && value.isNotEmpty()) it.comments += value }
                    "ECU" -> if (jobName == null && ecu.isEmpty()) ecu = value
                    "ECUCOMMENT" -> if (jobName == null && value.isNotEmpty() && value != "-") ecuComments += value
                    "ORIGIN" -> if (jobName == null && origin.isEmpty()) origin = value
                }
            }
            closeJob()
            return SgbdDescription(ecu, ecuComments.joinToString(" "), origin, jobs)
        }

        private fun leInt(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }

    private class MutableItem(val name: String) {
        var type = ""
        val comments = ArrayList<String>()
    }
}
