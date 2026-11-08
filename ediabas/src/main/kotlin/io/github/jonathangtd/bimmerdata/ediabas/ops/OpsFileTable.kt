/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdOperations.cs (user files and table access).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
@file:Suppress("UNUSED_PARAMETER")

package io.github.jonathangtd.bimmerdata.ediabas.ops

import io.github.jonathangtd.bimmerdata.ediabas.Cp1252
import io.github.jonathangtd.bimmerdata.ediabas.EdFiles
import io.github.jonathangtd.bimmerdata.ediabas.EdLogLevel
import io.github.jonathangtd.bimmerdata.ediabas.EdStream
import io.github.jonathangtd.bimmerdata.ediabas.EdValueType
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes
import io.github.jonathangtd.bimmerdata.ediabas.OpCode
import io.github.jonathangtd.bimmerdata.ediabas.Operand
import io.github.jonathangtd.bimmerdata.ediabas.getTableColumns
import io.github.jonathangtd.bimmerdata.ediabas.getTableEntry
import io.github.jonathangtd.bimmerdata.ediabas.getTableIndex
import io.github.jonathangtd.bimmerdata.ediabas.getTableLine
import io.github.jonathangtd.bimmerdata.ediabas.getTableRows
import io.github.jonathangtd.bimmerdata.ediabas.seekTable

// ---- user files ----

// BEST2: fclose
internal fun opFclose(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val handle = arg0.getValueData(1u)
    if (!ediabas.closeUserFile(handle.toInt())) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
    }
}

// BEST2: fopen
internal fun opFopen(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFopen: Invalid type")
    var handle = -1
    val fileName = arg1.getStringData()
    if (!EdFiles.exists(fileName)) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
    } else {
        try {
            val fs = EdFiles.openRead(fileName)
            handle = ediabas.storeUserFile(fs)
            if (handle < 0) {
                fs.close()
                ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
            }
        } catch (ex: Exception) {
            ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
        }
    }
    arg0.setRawData(handle.toUInt())
    ediabas.flags.updateFlags(handle.toUInt(), 1u)
}

// BEST2: fread
internal fun opFread(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFread: Invalid type")
    var value = -1
    val handle = arg1.getValueData(1u)
    val fs = ediabas.getUserFile(handle.toInt())
    if (fs == null) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
    } else {
        try {
            value = fs.readByte()
        } catch (ex: Exception) {
            value = -1
            ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
        }
    }
    if (value < 0) {
        value = 0
        ediabas.flags.carry = true
    } else {
        ediabas.flags.carry = false
    }
    arg0.setRawData(value.toUInt())
}

// BEST2: freadln
internal fun opFreadln(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFreadln: Invalid type")
    var lineString: String? = null
    val handle = arg1.getValueData(1u)
    val fs = ediabas.getUserFile(handle.toInt())
    if (fs == null) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
    } else {
        try {
            lineString = ediabas.readFileLine(fs)
        } catch (ex: Exception) {
            lineString = null
            ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
        }
    }
    if (lineString == null) {
        lineString = ""
        ediabas.flags.carry = true
    } else {
        ediabas.flags.carry = false
    }
    arg0.setArrayData(Cp1252.encode(lineString))
}

// BEST2: fseek
internal fun opFseek(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val handle = arg0.getValueData(1u)
    val position = arg1.getValueData()
    val fs = ediabas.getUserFile(handle.toInt())
    if (fs == null) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
    } else {
        fs.position = position.toLong()
    }
}

// BEST2: fseekln
internal fun opFseekln(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val handle = arg0.getValueData(1u)
    val line = arg1.getValueData()
    val fs = ediabas.getUserFile(handle.toInt())
    if (fs == null) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
    } else {
        try {
            fs.position = 0
            var i = 0L
            while (i < line.toLong()) {
                if (ediabas.readFileLineLength(fs) < 0) break
                i++
            }
        } catch (ex: Exception) {
            ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
        }
    }
}

// BEST2: ftell
internal fun opFtell(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFtell: Invalid type")
    var position = 0
    val handle = arg1.getValueData(1u)
    val fs = ediabas.getUserFile(handle.toInt())
    if (fs == null) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
    } else {
        position = fs.position.toInt()
    }
    arg0.setRawData(position.toUInt())
    ediabas.flags.updateFlags(position.toUInt(), 4u)
}

// BEST2: ftellln
internal fun opFtellln(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFtellln: Invalid type")
    var line: EdValueType = 0u
    val handle = arg1.getValueData(1u)
    val fs: EdStream? = ediabas.getUserFile(handle.toInt())
    if (fs == null) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
    } else {
        try {
            val currentPos = fs.position
            fs.position = 0
            while (true) {
                if (ediabas.readFileLineLength(fs) < 0) break
                if (fs.position >= currentPos) {
                    if (fs.position == currentPos) line++
                    break
                }
                line++
            }
            fs.position = currentPos
        } catch (ex: Exception) {
            line = 0u
            ediabas.setError(ErrorCodes.EDIABAS_BIP_0006)
        }
    }
    arg0.setRawData(line)
    ediabas.flags.updateFlags(line, 4u)
}

// ---- tables ----

// BEST2: tabcolumns
internal fun opTabcols(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.tableIndex < 0) {
        arg0.setRawData(0u)
    } else {
        arg0.setRawData(ediabas.getTableColumns(ediabas.getTableFs(), ediabas.tableIndex))
    }
}

// BEST2: tabrows
internal fun opTabrows(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.tableIndex < 0) {
        arg0.setRawData(0u)
    } else {
        val rows = ediabas.getTableRows(ediabas.getTableFs(), ediabas.tableIndex) + 1u // including header
        arg0.setRawData(rows)
    }
}

// BEST2: tabget
internal fun opTabget(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.tableIndex < 0) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
        return
    }
    val (entry, columnInvalid) = ediabas.getTableEntry(ediabas.getTableFs(), ediabas.tableIndex, ediabas.tableRowIndex, arg1.getStringData())
    if (entry == null) {
        if (ediabas.tableRowIndex < 0 && !columnInvalid) { // EDIABAS returns garbage if the table has changed, but no errors is raised
            arg0.setStringData("")
            return
        }
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
        return
    }
    arg0.setStringData(entry)
}

// BEST2: tabline
internal fun opTabline(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.tableIndex < 0) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
        return
    }
    val (rowIndex, found) = ediabas.getTableLine(ediabas.getTableFs(), ediabas.tableIndex, arg0.getValueData())
    if (rowIndex < 0) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
    }
    ediabas.tableRowIndex = rowIndex
    ediabas.flags.zero = !found
}

// BEST2: tabseek
internal fun opTabseek(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.tableIndex < 0) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
        return
    }
    val (rowIndex, found) = ediabas.seekTable(ediabas.getTableFs(), ediabas.tableIndex, arg0.getStringData(), arg1.getStringData())
    if (rowIndex < 0) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
        return
    }
    ediabas.tableRowIndex = rowIndex
    ediabas.flags.zero = !found
}

// BEST2: tabseeku
internal fun opTabseeku(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.tableIndex < 0) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
        return
    }
    val (rowIndex, found) = ediabas.seekTable(ediabas.getTableFs(), ediabas.tableIndex, arg0.getStringData(), arg1.getValueData())
    if (rowIndex < 0) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
        return
    }
    ediabas.tableRowIndex = rowIndex
    ediabas.flags.zero = !found
}

// BEST2: tabset
internal fun opTabset(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val tableIndexLast = ediabas.tableIndex
    val tableRowIndexLast = ediabas.tableRowIndex

    ediabas.closeTableFs()
    val baseFs = ediabas.sgbdBaseFs
    if (baseFs != null) {
        ediabas.setTableFs(baseFs)
    }

    var (tableAddr, found) = ediabas.getTableIndex(ediabas.getTableFs(), arg0.getStringData())
    if (!found && ediabas.sgbdBaseFs != null) {
        ediabas.closeTableFs()
        val r = ediabas.getTableIndex(ediabas.getTableFs(), arg0.getStringData())
        tableAddr = r.first
        found = r.second
    }

    if (!found) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
    }

    ediabas.tableIndex = tableAddr
    ediabas.tableRowIndex = -1
    if (ediabas.tableIndex == tableIndexLast) {
        ediabas.tableRowIndex = tableRowIndexLast
    }
}

// BEST2: tabsetext
internal fun opTabsetex(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val tableIndexLast = ediabas.tableIndex
    val tableRowIndexLast = ediabas.tableRowIndex

    val baseFileName = arg1.getStringData()
    if (baseFileName.isNotEmpty()) {
        val prgFileName = EdFiles.combine(ediabas.ecuPath, baseFileName + EdiabasNet.PrgFileExt)
        val grpFileName = EdFiles.combine(ediabas.ecuPath, baseFileName + EdiabasNet.GroupFileExt)
        // EdFiles.exists is already case-insensitive (covers the C# "case sensitive file systems" retry)
        val fullFileName = when {
            EdFiles.exists(prgFileName) -> prgFileName
            EdFiles.exists(grpFileName) -> grpFileName
            else -> ""
        }
        if (fullFileName.isEmpty()) {
            ediabas.logFormat(EdLogLevel.Error, "OpTabsetex: File not found {0}", baseFileName)
            ediabas.setError(ErrorCodes.EDIABAS_SYS_0002)
            return
        }

        try {
            ediabas.closeTableFs()
            val fs = EdFiles.openRead(fullFileName)
            ediabas.setTableFs(fs)
        } catch (ex: Exception) {
            ediabas.logFormat(EdLogLevel.Error, "OpTabsetex: File not found {0}", baseFileName)
            ediabas.setError(ErrorCodes.EDIABAS_SYS_0002)
            return
        }
    }

    val (tableAddr, found) = ediabas.getTableIndex(ediabas.getTableFs(), arg0.getStringData())
    if (!found) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0010)
    }
    ediabas.tableIndex = tableAddr
    ediabas.tableRowIndex = -1
    if (ediabas.tableIndex == tableIndexLast) {
        ediabas.tableRowIndex = tableRowIndexLast
    }
}
