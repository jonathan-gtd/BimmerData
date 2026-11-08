/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 *
 * The EDIABAS object file layout handled here follows EdiabasLib
 * (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs: ReadAllJobs, ReadAllTables, ReadTable, IndexTable.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Read-only view of an EDIABAS object file (.PRG ECU description or .GRP group file).
 *
 * The file starts with a clear-text header; most of the content is XOR-ed with 0xF7.
 * Little-endian offsets in the header point to the job list (0x88) and table list (0x84).
 */
class PrgFile private constructor(private val data: ByteArray) {

    data class Job(val name: String, val address: Long)

    data class Table(
        val name: String,
        val columnOffset: Long,
        val columnCount: Int,
        val rowCount: Int,
    )

    /** Fully decoded table: [header] holds the column names, [rows] the data rows. */
    data class TableData(val name: String, val header: List<String>, val rows: List<List<String>>) {
        private val index = header.withIndex().associate { (i, n) -> n.uppercase() to i }

        fun column(name: String): Int? = index[name.uppercase()]

        /** Rows as maps keyed by upper-case column name. */
        fun records(): List<Map<String, String>> =
            rows.map { row -> header.indices.associate { header[it].uppercase() to row[it] } }
    }

    val isObjectFile: Boolean get() = signature.startsWith("@EDIABAS OBJECT")

    val signature: String get() = String(data, 0, 16, ENCODING).trimEnd('\u0000')

    val jobs: List<Job> by lazy { readJobs() }

    val tables: List<Table> by lazy { readTables() }

    fun table(name: String): TableData? =
        tables.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let(::readTableData)

    private fun readJobs(): List<Job> {
        val listOffset = i32(0x88)
        if (listOffset < 0) return emptyList()
        // The job count itself is stored in clear (EdiabasNet.ReadInt32), entries are encrypted.
        val count = i32(listOffset)
        var entry = listOffset + 4
        return List(count) {
            val buf = decrypt(entry, JOB_ENTRY_SIZE)
            entry += JOB_ENTRY_SIZE
            Job(cString(buf, 0, 0x40), u32(buf, 0x40))
        }
    }

    private fun readTables(): List<Table> {
        val listOffset = i32(0x84)
        if (listOffset < 0) return emptyList()
        val count = ByteBuffer.wrap(decrypt(listOffset, 4)).order(ByteOrder.LITTLE_ENDIAN).int
        var entry = listOffset + 4
        return List(count) {
            val buf = decrypt(entry, TABLE_ENTRY_SIZE)
            entry += TABLE_ENTRY_SIZE
            Table(
                name = cString(buf, 0, 0x40),
                columnOffset = u32(buf, 0x40),
                columnCount = u32(buf, 0x48).toInt(),
                rowCount = u32(buf, 0x4C).toInt(),
            )
        }
    }

    private fun readTableData(table: Table): TableData {
        // Row 0 holds the column names, followed by rowCount data rows; every cell is a
        // zero-terminated encrypted string.
        var pos = table.columnOffset.toInt()
        val all = List(table.rowCount + 1) {
            List(table.columnCount) {
                var end = pos
                while ((data[end].toInt() xor XOR_KEY) and 0xFF != 0) end++
                val cell = String(decrypt(pos, end - pos), ENCODING)
                pos = end + 1
                cell
            }
        }
        return TableData(table.name, all.first(), all.drop(1))
    }

    private fun decrypt(offset: Int, length: Int): ByteArray =
        ByteArray(length) { (data[offset + it].toInt() xor XOR_KEY).toByte() }

    private fun i32(offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun u32(buf: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun cString(buf: ByteArray, offset: Int, max: Int): String {
        var end = offset
        while (end < offset + max && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, ENCODING)
    }

    companion object {
        private const val XOR_KEY = 0xF7
        private const val JOB_ENTRY_SIZE = 0x44
        private const val TABLE_ENTRY_SIZE = 0x50

        /** EDIABAS files use Windows-1252 text (EdiabasNet: Encoding.GetEncoding(1252)). */
        val ENCODING: Charset = Charset.forName("windows-1252")

        fun open(file: File): PrgFile = PrgFile(file.readBytes())

        fun fromBytes(bytes: ByteArray): PrgFile = PrgFile(bytes)
    }
}
