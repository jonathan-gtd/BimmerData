/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.PrgFile
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * One row of an SGBD's MESSWERTETAB: a value the ECU exposes under a 16-bit common identifier,
 * readable through STATUS_MESSWERTBLOCK_LESEN (KWP2000 $2C dynamic record + $21 read).
 *
 * Physical value = raw * [mul] / [div] + [add], raw being [dataType] in the ECU record. When
 * [conversionTable] is set the value is a state whose text is looked up in that table.
 */
data class MesswertRow(
    val arg: String,
    val id: Int,
    val resultName: String,
    val info: String,
    val unit: String,
    val label: String,
    val byteOrder: String,
    val dataType: DataType,
    /** Byte order of the raw value: Motorola (big endian) unless DATENTYP says "intel". */
    val littleEndian: Boolean,
    val conversionTable: String?,
    val mul: Double,
    val div: Double,
    val add: Double,
) {
    fun physical(raw: Double): Double = raw * mul / (if (div == 0.0) 1.0 else div) + add
}

/** Raw data types of MESSWERTETAB (column DATENTYP). */
enum class DataType(val size: Int, val signed: Boolean, val float: Boolean = false) {
    UChar(1, false), SChar(1, true),
    UInt16(2, false), SInt16(2, true),
    UInt32(4, false), SInt32(4, true),
    Float32(4, true, float = true);

    companion object {
        /** "unsigned char", "signed integer", "unsigned long", "motorola float"... null if unknown. */
        fun parse(text: String): DataType? {
            val t = text.trim().lowercase(Locale.ROOT)
            val unsigned = t.startsWith("unsigned")
            return when {
                t.endsWith("float") -> Float32
                t.endsWith("char") -> if (unsigned) UChar else SChar
                t.endsWith("integer") || t.endsWith("int") || t.endsWith("word") -> if (unsigned) UInt16 else SInt16
                t.endsWith("long") -> if (unsigned) UInt32 else SInt32
                else -> null
            }
        }
    }
}

/** Parsed MESSWERTETAB of one SGBD, plus the state conversion tables its rows reference. */
class MesswerteTab(val sgbd: String, val rows: List<MesswertRow>, private val conversions: Map<String, Map<Long, String>>) {
    private val byId = rows.associateBy { it.id }

    fun row(id: Int): MesswertRow? = byId[id]

    /** Text of a state value (conversion table NAME of the row), null if none / not listed. */
    fun stateText(row: MesswertRow, raw: Long): String? {
        val table = conversions[row.conversionTable?.uppercase(Locale.ROOT) ?: return null] ?: return null
        return table[raw] ?: table[DefaultKey]
    }

    companion object {
        /** Key of the fallback row ("0xXY", "default"...) of a conversion table. */
        private const val DefaultKey = Long.MIN_VALUE

        const val TableName = "MESSWERTETAB"

        fun load(sgbd: String, file: File): MesswerteTab? = load(sgbd, PrgFile.open(file))

        fun load(sgbd: String, prg: PrgFile): MesswerteTab? {
            val table = prg.table(TableName) ?: return null
            fun col(name: String) = table.column(name)
            val cArg = col("ARG"); val cId = col("ID") ?: return null; val cRes = col("RESULTNAME") ?: return null
            val cInfo = col("INFO"); val cUnit = col("EINHEIT"); val cLabel = col("LABEL"); val cLh = col("L/H")
            val cType = col("DATENTYP") ?: return null; val cName = col("NAME")
            val cMul = col("MUL"); val cDiv = col("DIV"); val cAdd = col("ADD")
            val rows = table.rows.mapNotNull { r ->
                val id = parseInt(r[cId]) ?: return@mapNotNull null
                val type = DataType.parse(r[cType]) ?: return@mapNotNull null
                fun s(c: Int?) = c?.let { r[it].trim() } ?: ""
                MesswertRow(
                    arg = s(cArg), id = id, resultName = s(cRes), info = s(cInfo),
                    unit = s(cUnit).let { if (it == "-") "" else it }, label = s(cLabel), byteOrder = s(cLh),
                    dataType = type, littleEndian = r[cType].contains("intel", ignoreCase = true),
                    conversionTable = s(cName).takeIf { it.isNotEmpty() && it != "-" },
                    mul = parseDouble(s(cMul)) ?: 1.0, div = parseDouble(s(cDiv)) ?: 1.0, add = parseDouble(s(cAdd)) ?: 0.0,
                )
            }
            val conversions = HashMap<String, Map<Long, String>>()
            for (name in rows.mapNotNull { it.conversionTable }.distinct()) {
                val t = prg.table(name) ?: continue
                val keyCol = t.column("WERT") ?: 0
                val textCol = t.column("UWTEXT") ?: t.column("TEXT") ?: (if (t.header.size > 1) 1 else continue)
                val map = HashMap<Long, String>()
                for (row in t.rows) {
                    val key = parseInt(row[keyCol])?.toLong() ?: DefaultKey
                    map.putIfAbsent(key, row[textCol])
                }
                conversions[name.uppercase(Locale.ROOT)] = map
            }
            return MesswerteTab(sgbd, rows, conversions)
        }

        /** "0x4402", "17", "-5"; null if not a number. */
        fun parseInt(text: String): Int? {
            val t = text.trim()
            return if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toLongOrNull(16)?.toInt() else t.toIntOrNull()
        }

        /** EDIABAS tables use a decimal comma: "0,100000001490116", "1,220703125E-4". */
        fun parseDouble(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    }
}

/**
 * Header-only look at an EDIABAS object file (same layout as [PrgFile]): reads the table
 * directory without loading the whole file, to scan ~1600 SGBDs quickly.
 */
internal object PrgTableDirectory {
    private const val XorKey = 0xF7
    private const val TableEntrySize = 0x50

    /** Names of the tables of [file], upper case; empty if not an object file. */
    fun tableNames(file: File): List<String> = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 0x8C) return emptyList()
            val sig = ByteArray(16)
            raf.readFully(sig)
            if (!String(sig, Charsets.ISO_8859_1).startsWith("@EDIABAS OBJECT")) return emptyList()
            raf.seek(0x84)
            val listOffset = leInt(ByteArray(4).also { raf.readFully(it) }, 0, false)
            if (listOffset <= 0 || listOffset + 4 > raf.length()) return emptyList()
            raf.seek(listOffset.toLong())
            val count = leInt(ByteArray(4).also { raf.readFully(it) }, 0, true)
            if (count <= 0 || count > 10000 || listOffset + 4L + count.toLong() * TableEntrySize > raf.length()) return emptyList()
            val buf = ByteArray(count * TableEntrySize)
            raf.readFully(buf)
            List(count) { i ->
                val o = i * TableEntrySize
                var end = o
                while (end < o + 0x40 && (buf[end].toInt() xor XorKey) and 0xFF != 0) end++
                val name = ByteArray(end - o) { (buf[o + it].toInt() xor XorKey).toByte() }
                String(name, PrgFile.ENCODING).uppercase(Locale.ROOT)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun leInt(b: ByteArray, o: Int, decrypt: Boolean): Int {
        fun at(i: Int) = (if (decrypt) b[o + i].toInt() xor XorKey else b[o + i].toInt()) and 0xFF
        return at(0) or (at(1) shl 8) or (at(2) shl 16) or (at(3) shl 24)
    }
}
