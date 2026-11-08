/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdOperations.cs (number conversions and floating point operations).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
@file:Suppress("UNUSED_PARAMETER")

package io.github.jonathangtd.bimmerdata.ediabas.ops

import io.github.jonathangtd.bimmerdata.ediabas.CallSource
import io.github.jonathangtd.bimmerdata.ediabas.EdDataType
import io.github.jonathangtd.bimmerdata.ediabas.EdFloatType
import io.github.jonathangtd.bimmerdata.ediabas.EdHelpers
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes
import io.github.jonathangtd.bimmerdata.ediabas.OpCode
import io.github.jonathangtd.bimmerdata.ediabas.Operand
import io.github.jonathangtd.bimmerdata.ediabas.Register

// BEST2: atoi
internal fun opA2Fix(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpA2fix: Invalid type")
    var value = EdiabasNet.stringToValue(arg1.getStringData())
    if (value < Int.MIN_VALUE) {
        value = Int.MIN_VALUE.toLong()
    }
    if (value > Int.MAX_VALUE) {
        value = 0xFFFFFFFFL
    }
    arg0.setRawData(value.toUInt())
    ediabas.flags.zero = false
    ediabas.flags.sign = false
    ediabas.flags.overflow = false
}

// BEST2: ator
internal fun opA2Flt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpA2flt: Invalid type")
    val valueStr = arg1.getStringData()
    val (result, valid) = EdiabasNet.stringToFloatValid(valueStr)
    if (EdiabasNet.IsMinVersion760) {
        if (!valid) {
            var compatMode = 0
            val compatModeProp = ediabas.getConfigProperty("CompatMode", CallSource.EdiabasOperation)
            if (compatModeProp != null) {
                compatMode = EdiabasNet.stringToValue(compatModeProp).toInt()
            }
            if (compatMode == 0) {
                ediabas.raiseError(ErrorCodes.EDIABAS_BIP_0011)
            }
        }
    }
    arg0.setRawData(result)
}

// BEST2: itoad
internal fun opFix2Dez(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFix2dez: Invalid type")
    val len = if (arg1.getDataType() != EdDataType.Value) 1u else arg1.getDataLen()
    val value = arg1.getValueData(len)
    val result = when (len) {
        1u -> value.toByte().toString()
        2u -> value.toShort().toString()
        4u -> value.toInt().toString()
        else -> throw IllegalArgumentException("OpFix2dez: Invalid length")
    }
    arg0.setStringData(result)
}

// BEST2: uitoad
internal fun opUfix2Dez(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpUfix2dez: Invalid type")
    val len = if (arg1.getDataType() != EdDataType.Value) 1u else arg1.getDataLen()
    val value = arg1.getValueData(len)
    val result = when (len) {
        1u -> value.toUByte().toString()
        2u -> value.toUShort().toString()
        4u -> value.toString()
        else -> throw IllegalArgumentException("OpUfix2dez: Invalid length")
    }
    arg0.setStringData(result)
}

// BEST2: itor
internal fun opFix2Flt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFix2flt: Invalid type")
    val value = arg1.getValueData()
    val len = arg1.getDataLen()
    val result: EdFloatType = when (len) {
        1u -> value.toByte().toDouble()
        2u -> value.toShort().toDouble()
        4u -> value.toInt().toDouble()
        else -> throw IllegalArgumentException("OpFix2flt: Invalid length")
    }
    arg0.setRawData(result)
}

// BEST2: itoax
internal fun opFix2Hex(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFix2hex: Invalid type")
    val len = if (arg1.getDataType() != EdDataType.Value) 1u else arg1.getDataLen()
    val value = arg1.getValueData(len)
    val hex = value.toString(16).uppercase()
    val result = when (len) {
        1u -> "0x" + hex.padStart(2, '0')
        2u -> "0x" + hex.padStart(4, '0')
        4u -> "0x" + hex.padStart(8, '0')
        else -> throw IllegalArgumentException("OpFix2hex: Invalid length")
    }
    arg0.setStringData(result)
}

// BEST2: rtoa
internal fun opFlt2A(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFlt2a: Invalid type")
    val value = arg1.getFloatData()
    val valueConv = EdHelpers.roundToSignificantDigits(value, ediabas.floatPrecision)

    var result = EdHelpers.doubleToStringNet(valueConv)
    var digitCount = 0
    var pos = 0
    for (singleChar in result) {
        if (Character.isDigit(singleChar)) {
            digitCount++
            if (digitCount.toLong() >= ediabas.floatPrecision.toLong()) {
                result = result.substring(0, pos + 1)
                break
            }
        }
        pos++
    }
    arg0.setStringData(result)
}

// rtoa (set float precision)
internal fun opSetflt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.floatPrecision = arg0.getValueData()
}

// BEST2: rtoi
internal fun opFlt2Fix(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpFlt2fix: Invalid type")
    val value = arg1.getFloatData()
    // C# unchecked (EdValueType)double: emulated with x86/x64 semantics (negative values wrap)
    val result = EdHelpers.doubleToUInt32(value)
    arg0.setRawData(result)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(result, 4u)
}

private fun leBytes(bits: Long, count: Int): ByteArray = ByteArray(count) { (bits shr (8 * it)).toByte() }

/** Common body of flt2y4 / flt2y8 (EdOperations.cs: OpFlt2Y4, OpFlt2Y8), same statement order as C#. */
private fun floatToData(ediabas: EdiabasNet, arg0: Operand, arg1: Operand, dataLen: UInt, name: String) {
    requireRegister(arg0, "$name: Invalid type")
    val arg0Data = arg0.opData1 as Register
    var dataArrayDest = arg0Data.getArrayData()
    val startIdx = arg0.opData2 as UInt

    if (startIdx + dataLen > ediabas.arrayMaxSize) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0001)
        return
    }
    if (dataArrayDest.size.toLong() < (startIdx + dataLen).toLong()) {
        dataArrayDest = dataArrayDest.copyOf((startIdx + dataLen).toInt())
    }

    val value = arg1.getFloatData()
    val resultArray = if (dataLen == 4u) {
        val singleValue: Float = value.toFloat() // (Single)value
        leBytes(java.lang.Float.floatToRawIntBits(singleValue).toLong(), 4)
    } else {
        leBytes(java.lang.Double.doubleToRawLongBits(value), 8)
    }
    System.arraycopy(resultArray, 0, dataArrayDest, startIdx.toInt(), resultArray.size)
    arg0Data.setArrayData(dataArrayDest)
}

// BEST2: real_to_data (intel byte order)
internal fun opFlt2Y4(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) = floatToData(ediabas, arg0, arg1, 4u, "OpFlt2y4")

// BEST2: real_to_data (intel byte order)
internal fun opFlt2Y8(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) = floatToData(ediabas, arg0, arg1, 8u, "OpFlt2y8")

// BEST2: data_to_real (intel byte order)
internal fun opY42Flt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "Opy42flt: Invalid type")
    val dataArray = arg1.getArrayData()
    if (dataArray.size < 4) throw IllegalArgumentException("BitConverter.ToSingle: array too short")
    var bits = 0
    for (i in 3 downTo 0) bits = (bits shl 8) or (dataArray[i].toInt() and 0xFF)
    val value: EdFloatType = java.lang.Float.intBitsToFloat(bits).toDouble()
    arg0.setRawData(value)
}

// BEST2: data_to_real (intel byte order)
internal fun opY82Flt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpY82flt: Invalid type")
    val dataArray = arg1.getArrayData()
    if (dataArray.size < 8) throw IllegalArgumentException("BitConverter.ToDouble: array too short")
    var bits = 0L
    for (i in 7 downTo 0) bits = (bits shl 8) or (dataArray[i].toLong() and 0xFF)
    val value: EdFloatType = java.lang.Double.longBitsToDouble(bits)
    arg0.setRawData(value)
}

/** fadd/fsub/fmul/fdiv: same try/catch structure as EdOperations.cs (SetError may raise twice). */
private inline fun floatOp(ediabas: EdiabasNet, arg0: Operand, arg1: Operand, name: String, op: (Double, Double) -> Double) {
    requireRegister(arg0, "$name: Invalid type")
    val val0 = arg0.getFloatData()
    val val1 = arg1.getFloatData()
    var result: EdFloatType = 0.0
    try {
        result = op(val0, val1)
        if (result.isInfinite() || result.isNaN()) {
            ediabas.setError(ErrorCodes.EDIABAS_BIP_0011)
        }
    } catch (ex: Exception) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0011)
    }
    arg0.setRawData(result)
}

// BEST2: realadd
internal fun opFadd(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) = floatOp(ediabas, arg0, arg1, "OpFadd") { a, b -> a + b }

// BEST2: realsub
internal fun opFsub(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) = floatOp(ediabas, arg0, arg1, "OpFsub") { a, b -> a - b }

// BEST2: realmul
internal fun opFmul(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) = floatOp(ediabas, arg0, arg1, "OpFmul") { a, b -> a * b }

// BEST2: realdiv
internal fun opFdiv(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) = floatOp(ediabas, arg0, arg1, "OpFdiv") { a, b -> a / b }

internal fun opFcomp(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val val0 = arg0.getFloatData()
    val val1 = arg1.getFloatData()
    val diff = val0 - val1
    if (diff.isInfinite() || diff.isNaN()) {
        ediabas.flags.carry = true
    }
    ediabas.flags.zero = val0 == val1
    ediabas.flags.sign = val0 < val1
    ediabas.flags.overflow = false
}
