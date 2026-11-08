/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdOperations.cs (string / data array operations).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
@file:Suppress("UNUSED_PARAMETER")

package io.github.jonathangtd.bimmerdata.ediabas.ops

import io.github.jonathangtd.bimmerdata.ediabas.EdHelpers
import io.github.jonathangtd.bimmerdata.ediabas.EdValueType
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes
import io.github.jonathangtd.bimmerdata.ediabas.OpAddrMode
import io.github.jonathangtd.bimmerdata.ediabas.OpCode
import io.github.jonathangtd.bimmerdata.ediabas.Operand
import io.github.jonathangtd.bimmerdata.ediabas.Register

// BEST2: datacat
internal fun opScat(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpScat: Invalid type")
    val data1 = arg0.getArrayData()
    val data2 = arg1.getArrayData()
    val resultArray = data1 + data2
    if (resultArray.size.toLong() > ediabas.arrayMaxSize.toLong()) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0001)
        return
    }
    arg0.setRawData(resultArray)
}

// BEST2: datacmp
internal fun opScmp(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val data1 = arg0.getArrayData()
    val data2 = arg1.getArrayData()
    ediabas.flags.zero = data1.size == data2.size && data1.contentEquals(data2)
}

private fun startIndexOf(arg0: Operand, name: String): EdValueType = when (arg0.addrMode) {
    OpAddrMode.IdxImm -> arg0.opData2 as UInt
    OpAddrMode.IdxReg -> (arg0.opData2 as Register).getValueData()
    else -> throw IllegalArgumentException("$name: Invalid mode")
}

// dataerase
internal fun opSerase(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpSerase: Invalid type")
    val startIdx = startIndexOf(arg0, "OpSerase")
    val arg0Data = arg0.opData1 as Register
    val dataArray = arg0Data.getArrayData()
    val len = arg1.getValueData()
    val end = (startIdx + len).toLong() // uint addition (wraps like C#)

    val resultByteList = ArrayList<Byte>()
    for (i in dataArray.indices) {
        if (i < startIdx.toLong() || i >= end) {
            resultByteList.add(dataArray[i])
        }
    }
    arg0Data.setArrayData(resultByteList.toByteArray())
}

// datainsert
internal fun opSpaste(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpSpaste: Invalid type")
    val startIdx = startIndexOf(arg0, "OpSpaste")
    val arg0Data = arg0.opData1 as Register
    val dataArrayDest = arg0Data.getArrayData()
    val dataArraySource = arg1.getArrayData()

    if (startIdx >= ediabas.arrayMaxSize) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0001)
        return
    }
    if (startIdx.toLong() < dataArrayDest.size) {
        val resultByteList = ArrayList<Byte>()
        for (i in 0 until startIdx.toInt()) {
            resultByteList.add(dataArrayDest[i])
        }
        for (data in dataArraySource) {
            resultByteList.add(data)
        }
        for (i in startIdx.toInt() until dataArrayDest.size) {
            resultByteList.add(dataArrayDest[i])
        }
        if (resultByteList.size.toLong() > ediabas.arrayMaxSize.toLong()) {
            ediabas.setError(ErrorCodes.EDIABAS_BIP_0001)
            return
        }
        arg0Data.setArrayData(resultByteList.toByteArray())
    }
}

// BEST2: strrevers
internal fun opSrevrs(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpSpaste: Invalid type")
    val arrayData = arg0.getArrayData()
    arrayData.reverse()
    arg0.setArrayData(arrayData)
}

// BEST2: get_token (store separator)
internal fun opSetspc(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.tokenSeparator = arg0.getStringData()
    ediabas.tokenIndex = arg1.getValueData()
}

// BEST2: get_token (store token)
internal fun opStoken(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpStoken Invalid type")
    if (ediabas.tokenSeparator.isEmpty()) {
        ediabas.flags.zero = true
    } else {
        val splitString = arg1.getStringData()
        val words = splitString.split(*ediabas.tokenSeparator.toCharArray())
        if (ediabas.tokenIndex < 1u || ediabas.tokenIndex.toLong() > words.size) {
            ediabas.flags.zero = true
        } else {
            arg0.setStringData(words[(ediabas.tokenIndex - 1u).toInt()])
            ediabas.flags.zero = false
        }
    }
}

// BEST2: strcat
internal fun opStrcat(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpStrcat: Invalid type")
    val len1 = arg0.getDataLen()
    val string1 = arg0.getStringData()
    var string2 = arg1.getStringData()

    if (len1.toLong() + string2.length > ediabas.arrayMaxSize.toLong()) {
        string2 = string2.substring(0, (ediabas.arrayMaxSize - len1).toInt())
    }
    arg0.setStringData(string1 + string2)
}

// BEST2: strcmp (zero flag set if the strings differ, as in EdiabasLib)
internal fun opStrcmp(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val string1 = arg0.getStringData()
    val string2 = arg1.getStringData()
    ediabas.flags.zero = string1.compareTo(string2) != 0
}

// BEST2: strcut
internal fun opScut(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpScut: Invalid type")
    val dataArray = arg0.getArrayData()
    val len = arg1.getValueData()
    // len includes terminating 0
    if (len.toLong() > dataArray.size) {
        arg0.setArrayData(ByteArray(0))
    } else {
        arg0.setArrayData(dataArray.copyOf(dataArray.size - len.toInt()))
    }
}

// string length
internal fun opSlen(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpSlen: Invalid type")
    arg0.setRawData(arg1.getDataLen())
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(arg0.getValueData(), arg0.getDataLen())
}

internal fun opSsize(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpSsize: Invalid type")
    arg0.setRawData(ediabas.arrayMaxBufSize)
}

// BEST2: strlen
internal fun opStrlen(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpStrlen:Invalid type")
    val result: EdValueType = arg1.getStringData().length.toUInt()
    arg0.setRawData(result)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(result, arg0.getDataLen())
}

// swap array
internal fun opSwap(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpSwap: Invalid type")
    val arg0Data = arg0.opData1 as Register
    val dataArrayDest = arg0Data.getArrayData(true) // the complete register buffer, reversed in place
    val startIdx = arg0.opData2 as UInt
    val dataLen = arg0.opData3 as UInt

    if (startIdx + dataLen > ediabas.arrayMaxSize) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0001)
        return
    }
    // Array.Reverse(array, index, length)
    val start = startIdx.toInt()
    val length = dataLen.toInt()
    if (start < 0 || length < 0 || start + length > dataArrayDest.size) throw IllegalArgumentException("OpSwap: range")
    var i = start
    var j = start + length - 1
    while (i < j) {
        val t = dataArrayDest[i]
        dataArrayDest[i] = dataArrayDest[j]
        dataArrayDest[j] = t
        i++
        j--
    }
    arg0Data.setArrayData(dataArrayDest, true)
}

// BEST2: atoy
internal fun opA2Y(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpA2y: Invalid type")
    val resultList = ArrayList<Byte>()
    var stringData = arg1.getStringData()
    if (stringData.isNotEmpty()) {
        var exitLoop = false
        val stringLower = EdHelpers.lowerInv(stringData)
        for (i in stringLower.indices) {
            val currentChar = stringLower[i]
            if (!(Character.isDigit(currentChar) || (currentChar in 'a'..'f') ||
                    currentChar == ' ' || currentChar == ';' || currentChar == ',')
            ) {
                stringData = stringData.substring(0, i)
                break
            }
        }

        val stringArray = stringData.split(',', ';')
        for (stringValue in stringArray) {
            val trimmed = EdHelpers.trimEndNet(EdHelpers.trimStartNet(stringValue))
            if (trimmed.isEmpty()) {
                for (i in 0 until stringValue.length + 1) {
                    resultList.add(0)
                }
            } else {
                val stringSubArray = trimmed.split(' ')
                for (stringSubValue in stringSubArray) {
                    if (stringSubValue.isNotEmpty()) {
                        try {
                            resultList.add(EdHelpers.convertToByte(stringSubValue, 16).toByte())
                        } catch (e: Exception) {
                            exitLoop = true
                            break
                        }
                    }
                }
            }
            if (exitLoop) break
        }
    }
    arg0.setArrayData(resultList.toByteArray())
}

// BEST2: ascii2hex
internal fun opHex2Y(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpHex2y: Invalid type")
    val valueStr = arg1.getStringData()
    val result: ByteArray = try {
        val r = EdHelpers.hexToByteArray(valueStr)
        ediabas.flags.carry = false
        r
    } catch (e: Exception) {
        ediabas.flags.carry = true
        ByteArray(0)
    }
    arg0.setArrayData(result)
}

// BEST2: bcd2ascii
internal fun opY2Bcd(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpY2bcd: Invalid type")
    val sb = StringBuilder()
    for (data in arg1.getArrayData()) {
        sb.append(EdHelpers.valueToBcd(data.toInt() and 0xFF))
    }
    arg0.setStringData(sb.toString())
}

// BEST2: hex2ascii
internal fun opY2Hex(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpY2hex: Invalid type")
    val sb = StringBuilder()
    for (data in arg1.getArrayData()) {
        sb.append(String.format("%02X", data.toInt() and 0xFF))
    }
    arg0.setStringData(sb.toString())
}
