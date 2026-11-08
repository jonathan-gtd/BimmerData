/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdOperations.cs (move, clear, arithmetic, logic, shifts, flags, stack).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas.ops

import io.github.jonathangtd.bimmerdata.ediabas.EdDataType
import io.github.jonathangtd.bimmerdata.ediabas.EdValueType
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet.Companion.getArgsValueLength
import io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes
import io.github.jonathangtd.bimmerdata.ediabas.OpAddrMode
import io.github.jonathangtd.bimmerdata.ediabas.OpCode
import io.github.jonathangtd.bimmerdata.ediabas.Operand
import io.github.jonathangtd.bimmerdata.ediabas.Register

/** C#: `if (arg.OpData1.GetType() != typeof(Register)) throw ...` (a null OpData1 is a NullReferenceException). */
internal fun requireRegister(arg: Operand, message: String) {
    val data = arg.opData1 ?: throw NullPointerException(message)
    if (data !is Register) throw IllegalArgumentException(message)
}

/** C#: `arg.OpData1.GetType() == typeof(Register)` */
internal fun isRegister(arg: Operand): Boolean {
    val data = arg.opData1 ?: throw NullPointerException("OpData1 is null")
    return data is Register
}

internal fun opMove(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpMove: Invalid type")
    val data0Type = arg0.getDataType()
    val data1Type = arg1.getDataType()
    val flags = ediabas.flags
    if (data0Type == EdDataType.Value) {
        val len = getArgsValueLength(arg0, arg1)
        val value = arg1.getValueData(len)
        arg0.setRawData(value)
        flags.carry = false
        flags.overflow = false
        flags.updateFlags(value, len)
    } else if (data0Type == EdDataType.Bytes) {
        if (data1Type == EdDataType.Value) {
            val len = getArgsValueLength(arg0, arg1)
            val value = arg1.getValueData(len)
            arg0.setRawData(value)
            flags.carry = false
            flags.overflow = false
            flags.updateFlags(value, 1u)
        } else if (data1Type == EdDataType.Bytes) {
            val sourceData = arg1.getRawData()
            if (arg0.addrMode == OpAddrMode.RegS && sourceData is ByteArray) {
                var destArray = arg0.getArrayData()
                if (destArray.size < sourceData.size) {
                    destArray = destArray.copyOf(sourceData.size)
                }
                System.arraycopy(sourceData, 0, destArray, 0, sourceData.size)
                arg0.setRawData(destArray)
            } else {
                arg0.setRawData(arg1.getRawData())
            }
            flags.carry = false
            flags.zero = false
            flags.sign = false
            flags.overflow = false
        } else {
            throw IllegalArgumentException("OpMove: Invalid source data type")
        }
    } else {
        throw IllegalArgumentException("OpMove: Invalid target data type")
    }
}

internal fun opClear(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
    requireRegister(arg0, "OpClear: Invalid type")
    val arg0Data = arg0.opData1 as Register
    when (arg0Data.getDataType()) {
        EdDataType.Bytes -> arg0Data.clearData()
        EdDataType.Float -> arg0Data.setFloatData(0.0) // not supported in ediabas
        else -> arg0Data.setValueData(0u)
    }
    val flags = ediabas.flags
    flags.carry = false
    flags.zero = true
    flags.sign = false
    flags.overflow = false
}

internal fun opComp(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    val len = getArgsValueLength(arg0, arg1)
    val val0 = arg0.getValueData(len)
    val val1 = arg1.getValueData(len)

    val diff: ULong = val0.toULong() - val1.toULong()
    ediabas.flags.updateFlags(diff.toUInt(), len)
    ediabas.flags.setOverflow(val0, 0u - val1, diff.toUInt(), len)
    ediabas.flags.setCarry(diff, len)
}

internal fun opSubb(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpSubb: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    val val0 = arg0.getValueData(len)
    val val1 = arg1.getValueData(len)

    val diff: ULong = val0.toULong() - val1.toULong()
    arg0.setRawData(diff.toUInt())
    ediabas.flags.updateFlags(diff.toUInt(), len)
    ediabas.flags.setOverflow(val0, 0u - val1, diff.toUInt(), len)
    ediabas.flags.setCarry(diff, len)
}

internal fun opSubc(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpSubc: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    val val0 = arg0.getValueData(len)
    var val1 = arg1.getValueData(len)
    if (ediabas.flags.carry) {
        val1++
    }

    val diff: ULong = val0.toULong() - val1.toULong()
    arg0.setRawData(diff.toUInt())
    ediabas.flags.updateFlags(diff.toUInt(), len)
    ediabas.flags.setOverflow(val0, 0u - val1, diff.toUInt(), len)
    ediabas.flags.setCarry(diff, len)
}

internal fun opAdds(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpAdds: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    val val0 = arg0.getValueData(len)
    val val1 = arg1.getValueData(len)

    val sum: ULong = val0.toULong() + val1.toULong()
    arg0.setRawData(sum.toUInt())
    ediabas.flags.updateFlags(sum.toUInt(), len)
    ediabas.flags.setOverflow(val0, val1, sum.toUInt(), len)
    ediabas.flags.setCarry(sum, len)
}

internal fun opAddc(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpAddc: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    val val0 = arg0.getValueData(len)
    var val1 = arg1.getValueData(len)
    if (ediabas.flags.carry) {
        val1++
    }

    val sum: ULong = val0.toULong() + val1.toULong()
    arg0.setRawData(sum.toUInt())
    ediabas.flags.updateFlags(sum.toUInt(), len)
    ediabas.flags.setOverflow(val0, val1, sum.toUInt(), len)
    ediabas.flags.setCarry(sum, len)
}

internal fun opMult(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpMult: Invalid type")
    val len = getArgsValueLength(arg0, arg1)

    val value1 = arg0.getValueData(len)
    val value2 = arg1.getValueData(len)
    val result: EdValueType
    try {
        result = when (len) {
            1u -> (value1.toByte().toInt() * value2.toByte().toInt()).toUInt()
            2u -> (value1.toShort().toInt() * value2.toShort().toInt()).toUInt()
            4u -> (value1.toInt() * value2.toInt()).toUInt()
            else -> throw IllegalArgumentException("OpMult: Invalid length")
        }
    } catch (ex: Exception) {
        throw RuntimeException("OpMult mult failure", ex)
    }
    arg0.setRawData(result)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(result, len)

    if (isRegister(arg1)) {
        val resultHigh = (result.toULong() shr (len shl 3).toInt()).toUInt()
        // If values are negative the result is unpredictiable.
        // We don't emulate the original behaviour here in this case.
        arg1.setRawData(resultHigh, len)
    }
}

internal fun opDivs(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpDivs: Invalid type")
    val len = getArgsValueLength(arg0, arg1)

    val value1 = arg0.getValueData(len)
    val value2 = arg1.getValueData(len)
    var result: EdValueType = 0u
    var remainder: EdValueType = 0u
    var error = false
    try {
        when (len) {
            // DIVS failure in ediabas! (1 and 2 byte values are divided as Int32 too)
            1u, 2u, 4u -> {
                val a = value1.toInt()
                val b = value2.toInt()
                // .NET throws DivideByZeroException and, for Int32.MinValue / -1, OverflowException
                if (b == 0) throw ArithmeticException("Division by zero")
                if (a == Int.MIN_VALUE && b == -1) throw ArithmeticException("Overflow")
                result = (a / b).toUInt()
                remainder = (a % b).toUInt()
            }
            else -> throw IllegalArgumentException("OpDivs: Invalid length")
        }
    } catch (ex: Exception) {
        if (EdiabasNet.IsMinVersion760) {
            ediabas.raiseError(ErrorCodes.EDIABAS_BIP_0007)
        } else {
            ediabas.setError(ErrorCodes.EDIABAS_BIP_0007)
        }
        error = true
    }
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(result, len)
    if (error) { // DIVS failure in ediabas!
        result = arg0.getValueData(len)
    }
    arg0.setRawData(result)

    if (isRegister(arg1)) {
        arg1.setRawData(remainder, len)
    }
}

internal fun opAnd(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpAnd: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    val value = arg0.getValueData(len) and arg1.getValueData(len)
    arg0.setRawData(value)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(value, len)
}

internal fun opOr(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpOr: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    val value = arg0.getValueData(len) or arg1.getValueData(len)
    arg0.setRawData(value)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(value, len)
}

internal fun opXor(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXor: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    val value = arg0.getValueData(len) xor arg1.getValueData(len)
    arg0.setRawData(value)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(value, len)
}

internal fun opTest(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    val len = getArgsValueLength(arg0, arg1)
    val value = arg0.getValueData(len) and arg1.getValueData(len)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(value, len)
}

internal fun opNot(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
    requireRegister(arg0, "OpNot: Invalid type")
    val value = arg0.getValueData().inv()
    arg0.setRawData(value)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(arg0.getValueData(), arg0.getDataLen())
}

/** asl and lsl share the same implementation in EdOperations.cs. */
private fun shiftLeft(ediabas: EdiabasNet, arg0: Operand, arg1: Operand, name: String) {
    requireRegister(arg0, "$name: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    var value = arg0.getValueData(len)
    val shift = arg1.getValueData(len).toInt()
    val bits = (len * 8u).toLong()
    if (shift < 0) {
        // don't touch carry here!
    } else if (shift == 0) {
        ediabas.flags.carry = false
    } else {
        if (shift > bits) {
            ediabas.flags.carry = false
        } else {
            val carryShift = (len shl 3).toLong() - shift
            val carryMask = (1 shl carryShift.toInt()).toUInt()
            ediabas.flags.carry = (value and carryMask) != 0u
        }

        value = if (shift >= bits) 0u else value shl shift
    }

    arg0.setRawData(value)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(value, len)
}

internal fun opAsl(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) =
    shiftLeft(ediabas, arg0, arg1, "OpAsl")

internal fun opLsl(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) =
    shiftLeft(ediabas, arg0, arg1, "OpLsl")

internal fun opAsr(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpAsr: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    var value = arg0.getValueData(len)
    val shift = arg1.getValueData(len).toInt()
    val bits = (len * 8u).toLong()
    if (shift < 0) {
        // don't touch carry here!
    } else if (shift == 0) {
        ediabas.flags.carry = false
    } else {
        if (shift > bits) {
            val signMask = (1 shl (len * 8u - 1u).toInt()).toUInt()
            ediabas.flags.carry = (value and signMask) != 0u
        } else {
            val carryShift = shift.toLong() - 1
            val carryMask = (1 shl carryShift.toInt()).toUInt()
            ediabas.flags.carry = (value and carryMask) != 0u
        }

        if (shift >= bits) {
            val signMask = (1 shl (len * 8u - 1u).toInt()).toUInt()
            value = if ((value and signMask) != 0u) 0xFFFFFFFFu else 0u
        } else {
            value = when (len) {
                1u -> (value.toByte().toInt() shr shift).toUInt()
                2u -> (value.toShort().toInt() shr shift).toUInt()
                4u -> (value.toInt() shr shift).toUInt()
                else -> throw IllegalArgumentException("OpAsr: Invalid length")
            }
        }
    }

    arg0.setRawData(value)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(value, len)
}

internal fun opLsr(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpLsr: Invalid type")
    val len = getArgsValueLength(arg0, arg1)
    var value = arg0.getValueData(len)
    val shift = arg1.getValueData(len).toInt()
    val bits = (len * 8u).toLong()
    if (shift < 0) {
        // don't touch carry here!
    } else if (shift == 0) {
        ediabas.flags.carry = false
    } else {
        if (shift > bits) {
            ediabas.flags.carry = false
        } else {
            val carryShift = shift.toLong() - 1
            val carryMask = (1 shl carryShift.toInt()).toUInt()
            ediabas.flags.carry = (value and carryMask) != 0u
        }

        value = if (shift >= bits) 0u else value shr shift
    }

    arg0.setRawData(value)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(value, len)
}

// clear carry
internal fun opClrc(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, @Suppress("UNUSED_PARAMETER") arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
    ediabas.flags.carry = false
}

// set carry
internal fun opSetc(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, @Suppress("UNUSED_PARAMETER") arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
    ediabas.flags.carry = true
}

// clear overflow
internal fun opClrv(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, @Suppress("UNUSED_PARAMETER") arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
    ediabas.flags.overflow = false
}

internal fun opNop(@Suppress("UNUSED_PARAMETER") ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, @Suppress("UNUSED_PARAMETER") arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
}

internal fun opPush(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
    var value = arg0.getValueData()
    val length = arg0.getDataLen()
    for (i in 0 until length.toInt()) {
        ediabas.stackList.addFirst(value.toByte())
        value = value shr 8
    }
}

internal fun opPushf(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, @Suppress("UNUSED_PARAMETER") arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
    var value = ediabas.flags.toValue()
    for (i in 0 until 4) { // sizeof(EdValueType)
        ediabas.stackList.addFirst(value.toByte())
        value = value shr 8
    }
}

internal fun opPop(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
    requireRegister(arg0, "OpPop: Invalid type")
    if (arg0.getDataType() != EdDataType.Value) {
        throw IllegalArgumentException("OpPop: Invalid data type")
    }
    var value: EdValueType = 0u
    val length = arg0.getDataLen()
    if (ediabas.stackList.size.toLong() < length.toLong()) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0005)
    } else {
        for (i in 0 until length.toInt()) {
            value = value shl 8
            value = value or (ediabas.stackList.removeFirst().toInt() and 0xFF).toUInt()
        }
    }

    arg0.setRawData(value)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(value, length)
}

internal fun opPopf(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, @Suppress("UNUSED_PARAMETER") arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand) {
    var value: EdValueType = 0u
    if (ediabas.stackList.size < 4) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0005)
    } else {
        for (i in 0 until 4) {
            value = value shl 8
            value = value or (ediabas.stackList.removeFirst().toInt() and 0xFF).toUInt()
        }
    }
    ediabas.flags.fromValue(value)
}

internal fun opAtsp(ediabas: EdiabasNet, @Suppress("UNUSED_PARAMETER") oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpAtsp: Invalid type")
    if (arg0.getDataType() != EdDataType.Value) {
        throw IllegalArgumentException("OpAtsp: Invalid data type")
    }

    var value: EdValueType = 0u
    val length = arg0.getDataLen()
    val pos = arg1.getValueData()
    if (ediabas.stackList.size.toLong() < length.toLong()) {
        ediabas.setError(ErrorCodes.EDIABAS_BIP_0005)
    } else {
        val stackArray = ediabas.stackList.toList() // C# Stack.ToArray(): top of stack first
        var index: Long = (pos - length).toLong() // uint subtraction, never negative
        if (index < 0) {
            throw IllegalArgumentException("OpAtsp: Invalid stack index")
        }
        for (i in 0 until length.toInt()) {
            if (index >= stackArray.size) throw IndexOutOfBoundsException("OpAtsp: stack index $index")
            value = value shl 8
            value = value or (stackArray[(index++).toInt()].toInt() and 0xFF).toUInt()
        }
    }

    arg0.setRawData(value)
    ediabas.flags.updateFlags(value, length)
}
