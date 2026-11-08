/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs (object file loading: uses, version, descriptions, jobs, tables).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet.Companion.leUInt32
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet.Companion.readAndDecryptBytes
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet.Companion.readInt32
import java.io.EOFException

/*
 * Object file header (offsets of little-endian Int32 values, clear text):
 *  0x10 file type (0 = group file), 0x18 array size, 0x7C uses list, 0x84 table list,
 *  0x88 job list, 0x90 descriptions, 0x94 version info. Everything else is XOR 0xF7 encrypted.
 */

private fun readHeaderInt32(fs: EdStream, position: Long, what: String): Int {
    val buffer = ByteArray(4)
    fs.position = position
    if (fs.read(buffer, 0, buffer.size) != buffer.size) {
        throw EOFException(what)
    }
    return leUInt32(buffer, 0).toInt()
}

private fun trimEndNul(s: String): String = s.trimEnd('\u0000')

// EdiabasNet.cs: ReadAllUses
internal fun EdiabasNet.readAllUses(fs: EdStream): EdiabasNet.UsesInfos {
    val usesOffset = readHeaderInt32(fs, 0x7C, "ReadAllUses")
    if (usesOffset < 0) {
        return EdiabasNet.UsesInfos(emptyArray())
    }
    fs.position = usesOffset.toLong()
    val usesCount = readInt32(fs)
    val usesBuffer = ByteArray(0x100)
    val array = Array(usesCount) {
        readAndDecryptBytes(fs, usesBuffer, 0, 0x100)
        EdiabasNet.UsesInfo(trimEndNul(Cp1252.decode(usesBuffer, 0, 0x100)))
    }
    return EdiabasNet.UsesInfos(array)
}

// EdiabasNet.cs: ReadVersionInfos
internal fun EdiabasNet.readVersionInfos(fs: EdStream): EdiabasNet.VersionInfos {
    val versionInfosLocal = EdiabasNet.VersionInfos()
    val infoOffset = readHeaderInt32(fs, 0x94, "ReadVersionInfos")
    if (infoOffset < 0) {
        return versionInfosLocal
    }
    fs.position = infoOffset.toLong()
    val infoBuffer = ByteArray(0x6C)
    readAndDecryptBytes(fs, infoBuffer, 0, infoBuffer.size)

    fun u8(i: Int) = infoBuffer[i].toInt() and 0xFF
    fun i16(i: Int) = (u8(i) or (u8(i + 1) shl 8)).toShort().toInt()

    versionInfosLocal.bipVersion = ((u8(2) shl 16) or (u8(1) shl 8) or u8(0)).toLong()
    versionInfosLocal.author = trimEndNul(Cp1252.decode(infoBuffer, 0x08, 0x40))
    versionInfosLocal.revision = "${i16(0x06)}.${i16(0x04)}"
    versionInfosLocal.from = trimEndNul(Cp1252.decode(infoBuffer, 0x48, 0x20))
    versionInfosLocal.packageVersion = leUInt32(infoBuffer, 0x68).toInt().toLong()
    return versionInfosLocal
}

// EdiabasNet.cs: ReadDescriptions
internal fun EdiabasNet.readDescriptions(fs: EdStream): EdiabasNet.DescriptionInfos {
    val descriptionOffset = readHeaderInt32(fs, 0x90, "ReadDescriptions")
    val descriptionInfosLocal = EdiabasNet.DescriptionInfos()
    if (descriptionOffset < 0) {
        return descriptionInfosLocal
    }
    fs.position = descriptionOffset.toLong()
    val numBytes = readInt32(fs)

    var commentList = ArrayList<String>()
    var previousJobName: String? = null

    val recordBuffer = ByteArray(1100)
    var recordOffset = 0
    for (i in 0 until numBytes) {
        readAndDecryptBytes(fs, recordBuffer, recordOffset, 1)
        recordOffset += 1

        if (recordOffset >= 1098) {
            recordBuffer[recordOffset++] = 10 // \n
        }

        if (recordBuffer[recordOffset - 1] == 10.toByte()) { // \n
            recordBuffer[recordOffset] = 0
            val comment = Cp1252.decode(recordBuffer, 0, recordOffset - 1)
            if (comment.startsWith("JOBNAME:", ignoreCase = true)) {
                if (previousJobName == null) {
                    descriptionInfosLocal.globalComments = commentList
                } else {
                    if (!descriptionInfosLocal.jobComments.containsKey(previousJobName)) {
                        descriptionInfosLocal.jobComments[previousJobName] = commentList
                    }
                }
                commentList = ArrayList()
                previousJobName = comment.substring(8)
            }
            commentList.add(comment)
            recordOffset = 0
        }
    }

    if (previousJobName == null) {
        descriptionInfosLocal.globalComments = commentList
    } else {
        if (!descriptionInfosLocal.jobComments.containsKey(previousJobName)) {
            descriptionInfosLocal.jobComments[previousJobName] = commentList
        }
    }
    return descriptionInfosLocal
}

// EdiabasNet.cs: ReadAllJobs
internal fun EdiabasNet.readAllJobs(fs: EdStream): EdiabasNet.JobInfos {
    val jobListComplete = getJobList(fs, null)

    for (usesInfo in usesInfos!!.usesInfoArray) {
        val fileName = EdFiles.combine(ecuPath, EdHelpers.lowerInv(usesInfo.name) + EdiabasNet.PrgFileExt)
        try {
            EdFiles.openRead(fileName).use { tempFs ->
                try {
                    jobListComplete.addAll(getJobList(tempFs, usesInfo))
                } catch (ex: Exception) {
                    logString(EdLogLevel.Error, "ReadAllJobs exception: $ex")
                }
            }
        } catch (ex: Exception) {
            logString(EdLogLevel.Error, "ReadAllJobs file not found: $fileName")
        }
    }

    val jobNameDict = LinkedHashMap<String, UInt>()
    val jobInfoArray = jobListComplete.toTypedArray()
    for ((index, jobInfo) in jobInfoArray.withIndex()) {
        val key = EdHelpers.upperInv(jobInfo.jobName)
        var addJob = true
        if (jobInfo.usesInfo != null) {
            if (key.equals(EdiabasNet.JobNameInit, ignoreCase = true) || key.equals(EdiabasNet.JobNameExit, ignoreCase = true)) {
                addJob = false
            }
        }
        if (addJob && !jobNameDict.containsKey(key)) {
            jobNameDict[key] = index.toUInt()
        }
    }
    return EdiabasNet.JobInfos(jobInfoArray, jobNameDict)
}

// EdiabasNet.cs: GetJobList
@Suppress("UnusedReceiverParameter")
internal fun EdiabasNet.getJobList(fs: EdStream, usesInfo: EdiabasNet.UsesInfo?): MutableList<EdiabasNet.JobInfo> {
    var arraySize = readHeaderInt32(fs, 0x18, "GetJobList").toUInt()
    if (arraySize == 0u) {
        arraySize = 1024u
    }
    val jobListOffset = readHeaderInt32(fs, 0x88, "GetJobList")

    val jobList = ArrayList<EdiabasNet.JobInfo>()
    if (jobListOffset < 0) {
        return jobList
    }
    fs.position = jobListOffset.toLong()
    val numJobs = readInt32(fs)

    val jobBuffer = ByteArray(0x44)
    var jobStart = fs.position.toUInt()
    for (i in 0 until numJobs) {
        fs.position = jobStart.toLong()
        readAndDecryptBytes(fs, jobBuffer, 0, jobBuffer.size)
        val jobNameString = trimEndNul(Cp1252.decode(jobBuffer, 0, 0x40))
        val jobAddress = leUInt32(jobBuffer, 0x40)
        jobList.add(EdiabasNet.JobInfo(jobNameString, jobAddress, 0u, arraySize, usesInfo))
        jobStart += 0x44u
    }
    return jobList
}

// EdiabasNet.cs: ReadAllTables
@Suppress("UnusedReceiverParameter")
internal fun EdiabasNet.readAllTables(fs: EdStream): EdiabasNet.TableInfos {
    val tableNameDict = LinkedHashMap<String, UInt>()
    val tableOffset = readHeaderInt32(fs, 0x84, "ReadAllTables")
    if (tableOffset < 0) {
        return EdiabasNet.TableInfos(emptyArray(), tableNameDict)
    }
    fs.position = tableOffset.toLong()

    val tableCountBuffer = ByteArray(4)
    readAndDecryptBytes(fs, tableCountBuffer, 0, tableCountBuffer.size)
    val tableCount = leUInt32(tableCountBuffer, 0).toInt()

    var tableStart = fs.position.toUInt()
    val array = Array(tableCount) { i ->
        val tableInfo = readTable(fs, tableStart)
        val key = EdHelpers.upperInv(tableInfo.name)
        // C# Dictionary.Add throws on duplicate names
        if (tableNameDict.containsKey(key)) throw IllegalArgumentException("ReadAllTables: duplicate table $key")
        tableNameDict[key] = i.toUInt()
        tableStart += 0x50u
        tableInfo
    }
    return EdiabasNet.TableInfos(array, tableNameDict)
}

// EdiabasNet.cs: ReadTable
private fun readTable(fs: EdStream, tableOffset: UInt): EdiabasNet.TableInfo {
    fs.position = tableOffset.toLong()
    val tableBuffer = ByteArray(0x50)
    readAndDecryptBytes(fs, tableBuffer, 0, tableBuffer.size)
    val name = trimEndNul(Cp1252.decode(tableBuffer, 0, 0x40))
    val tableColumnOffset = leUInt32(tableBuffer, 0x40)
    val tableColumnCount = leUInt32(tableBuffer, 0x48)
    val tableRowCount = leUInt32(tableBuffer, 0x4C)
    return EdiabasNet.TableInfo(name, tableOffset, tableColumnOffset, tableColumnCount, tableRowCount)
}

// EdiabasNet.cs: IndexTable
internal fun EdiabasNet.indexTable(fs: EdStream, tableInfo: EdiabasNet.TableInfo) {
    if (tableInfo.tableEntries != null) {
        return
    }
    fs.position = tableInfo.tableColumnOffset.toLong()

    val columnNameDict = LinkedHashMap<String, UInt>()
    val rows = tableInfo.rows.toInt() + 1
    val columns = tableInfo.columns.toInt()
    val tableEntries = Array(rows) { UIntArray(columns) }
    for (j in 0 until rows) {
        val tableRow = tableEntries[j]
        for (k in 0 until columns) {
            tableRow[k] = fs.position.toUInt()
            var l = 0
            while (l < tableItemBuffer.size) {
                readAndDecryptBytes(fs, tableItemBuffer, l, 1)
                if (tableItemBuffer[l] == 0.toByte()) break
                l++
            }
            if (j == 0) {
                val columnName = EdHelpers.upperInv(Cp1252.decode(tableItemBuffer, 0, l))
                // C# Dictionary.Add throws on duplicate names
                if (columnNameDict.containsKey(columnName)) throw IllegalArgumentException("IndexTable: duplicate column $columnName")
                columnNameDict[columnName] = k.toUInt()
            }
        }
    }
    tableInfo.columnNameDict = columnNameDict
    tableInfo.tableEntries = tableEntries
}

// EdiabasNet.cs: GetTableString
internal fun EdiabasNet.getTableString(fs: EdStream, stringOffset: UInt): String {
    fs.position = stringOffset.toLong()
    var l = 0
    while (l < tableItemBuffer.size) {
        readAndDecryptBytes(fs, tableItemBuffer, l, 1)
        if (tableItemBuffer[l] == 0.toByte()) break
        ++l
    }
    return Cp1252.decode(tableItemBuffer, 0, l)
}

// EdiabasNet.cs: GetTableIndex, returns (index, found)
internal fun EdiabasNet.getTableIndex(fs: EdStream, tableName: String): Pair<Int, Boolean> {
    val tableInfosLocal = getTableInfos(fs) ?: return Pair(0, false)
    val tableIdx = tableInfosLocal.tableNameDict[EdHelpers.upperInv(tableName)]
    if (tableIdx != null) {
        indexTable(fs, tableInfosLocal.tableInfoArray[tableIdx.toInt()])
        return Pair(tableIdx.toInt(), true)
    }
    return Pair(tableInfosLocal.tableInfoArray.size - 1, false)
}

private fun EdiabasNet.tableAt(fs: EdStream, tableIdx: Int): EdiabasNet.TableInfo? {
    val tableArray = getTableInfos(fs)?.tableInfoArray ?: return null
    if (tableIdx < 0 || tableIdx >= tableArray.size) return null
    return tableArray[tableIdx]
}

// EdiabasNet.cs: GetTableColumns
internal fun EdiabasNet.getTableColumns(fs: EdStream, tableIdx: Int): UInt = tableAt(fs, tableIdx)?.columns ?: 0u

// EdiabasNet.cs: GetTableRows
internal fun EdiabasNet.getTableRows(fs: EdStream, tableIdx: Int): UInt = tableAt(fs, tableIdx)?.rows ?: 0u

// EdiabasNet.cs: GetTableLine, returns (row, found)
internal fun EdiabasNet.getTableLine(fs: EdStream, tableIdx: Int, line: EdValueType): Pair<Int, Boolean> {
    val table = tableAt(fs, tableIdx) ?: return Pair(-1, false)
    if (line >= table.rows) { // select last line
        return Pair((table.rows - 1u).toInt(), false)
    }
    return Pair(line.toInt(), true)
}

// EdiabasNet.cs: SeekTable(..., string valueName, out bool found)
internal fun EdiabasNet.seekTable(fs: EdStream, tableIdx: Int, columnName: String, valueName: String): Pair<Int, Boolean> {
    val table = tableAt(fs, tableIdx) ?: return Pair(-1, false)
    val columnIndex = getTableColumnIdx(table, columnName)
    if (columnIndex < 0) return Pair(-1, false)

    var dicts = table.seekColumnStringDicts
    if (dicts == null) {
        dicts = arrayOfNulls(table.columns.toInt())
        table.seekColumnStringDicts = dicts
    }
    var columnDict = dicts[columnIndex]
    if (columnDict == null) { // create new dict
        columnDict = LinkedHashMap()
        dicts[columnIndex] = columnDict
        val entries = table.tableEntries!!
        for (i in 1 until entries.size) {
            val rowStr = EdHelpers.upperInv(getTableString(fs, entries[i][columnIndex]))
            if (!columnDict.containsKey(rowStr)) {
                columnDict[rowStr] = (i - 1).toUInt()
            }
        }
    }
    val selectedRow = columnDict[EdHelpers.upperInv(valueName)]
    if (selectedRow != null) {
        return Pair(selectedRow.toInt(), true)
    }
    return Pair((table.rows - 1u).toInt(), false) // select last line
}

// EdiabasNet.cs: SeekTable(..., EdValueType value, out bool found)
internal fun EdiabasNet.seekTable(fs: EdStream, tableIdx: Int, columnName: String, value: EdValueType): Pair<Int, Boolean> {
    val table = tableAt(fs, tableIdx) ?: return Pair(-1, false)
    val columnIndex = getTableColumnIdx(table, columnName)
    if (columnIndex < 0) return Pair(-1, false)

    var dicts = table.seekColumnValueDicts
    if (dicts == null) {
        dicts = arrayOfNulls(table.columns.toInt())
        table.seekColumnValueDicts = dicts
    }
    var columnDict = dicts[columnIndex]
    if (columnDict == null) { // create new dict
        columnDict = LinkedHashMap()
        dicts[columnIndex] = columnDict
        val entries = table.tableEntries!!
        for (i in 1 until entries.size) {
            val rowStr = getTableString(fs, entries[i][columnIndex])
            val rowValue = EdiabasNet.stringToValue(rowStr).toUInt()
            if (!columnDict.containsKey(rowValue)) {
                columnDict[rowValue] = (i - 1).toUInt()
            }
        }
    }
    val selectedRow = columnDict[value]
    if (selectedRow != null) {
        return Pair(selectedRow.toInt(), true)
    }
    return Pair((table.rows - 1u).toInt(), false) // select last line
}

// EdiabasNet.cs: GetTableColumnIdx
private fun getTableColumnIdx(table: EdiabasNet.TableInfo?, columnName: String): Int {
    val column = table?.columnNameDict?.get(EdHelpers.upperInv(columnName)) ?: return -1
    return column.toInt()
}

// EdiabasNet.cs: GetTableEntry, returns (entry, columnInvalid)
internal fun EdiabasNet.getTableEntry(fs: EdStream, tableIdx: Int, rowIdx: Int, columnName: String): Pair<String?, Boolean> {
    logFormat(EdLogLevel.Info, "GetTableEntry: TabIdx={0}, RowIdx={1}, Col={2}", tableIdx, rowIdx, columnName)
    val table = tableAt(fs, tableIdx)
    if (table == null) {
        logString(EdLogLevel.Info, "GetTableEntry: Table invalid")
        return Pair(null, false)
    }
    val columnIndex = getTableColumnIdx(table, columnName)
    if (columnIndex < 0) {
        logString(EdLogLevel.Info, "GetTableEntry: Column invalid")
        return Pair(null, true)
    }
    if (rowIdx < 0 || rowIdx.toLong() >= table.rows.toLong()) {
        logString(EdLogLevel.Info, "GetTableEntry: Row invalid")
        return Pair(null, false)
    }
    val entry = getTableString(fs, table.tableEntries!![rowIdx + 1][columnIndex])
    logFormat(EdLogLevel.Info, "GetTableEntry: Entry={0}", entry)
    return Pair(entry, false)
}
