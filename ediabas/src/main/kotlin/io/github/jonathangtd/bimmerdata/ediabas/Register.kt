/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs (classes StringData, Register, RegisterList).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

/**
 * EdiabasNet.cs: class StringData. Fixed size buffer (ArrayMaxBufSize) plus a logical length;
 * bytes beyond the length are kept (indexed reads see the complete buffer, like in C#).
 */
internal class StringData(private val ediabas: EdiabasNet, length: EdValueType) {
    private var length: EdValueType = 0u
    private var data: ByteArray = ByteArray(length.toInt())

    fun newArrayLength(length: EdValueType) {
        if (length.toLong() > data.size) {
            data = ByteArray(length.toInt())
        }
    }

    fun getData(complete: Boolean = false): ByteArray {
        if (complete) return data
        return data.copyOf(length.toInt())
    }

    fun setData(value: ByteArray, keepLength: Boolean) {
        if (value.size > data.size) {
            ediabas.setError(ErrorCodes.EDIABAS_BIP_0001)
            return
        }
        System.arraycopy(value, 0, data, 0, value.size)
        if (!keepLength) {
            length = value.size.toUInt()
        }
    }

    fun clearData() {
        data.fill(0)
        length = 0u
    }
}

/**
 * EdiabasNet.cs: class Register. In C# the register list is static and re-bound to the last created
 * EdiabasNet instance (SetEdiabas); here every EdiabasNet owns its own list, which is equivalent for a
 * single instance and avoids cross-instance interference.
 */
internal class Register(val opcode: Int, private val regType: RegisterType, private val index: Int, private val ediabas: EdiabasNet) {

    fun getName(): String = when (regType) {
        RegisterType.RegAb -> if (index > 15) "A" + (index - 16).toString(16).uppercase() else "B" + index.toString(16).uppercase()
        RegisterType.RegI -> "I" + index.toString(16).uppercase()
        RegisterType.RegL -> "L" + index.toString(16).uppercase()
        RegisterType.RegF -> "F" + index.toString(16).uppercase()
        RegisterType.RegS -> "S" + index.toString(16).uppercase()
    }

    fun getDataType(): EdDataType = when (regType) {
        RegisterType.RegF -> EdDataType.Float
        RegisterType.RegS -> EdDataType.Bytes
        else -> EdDataType.Value
    }

    fun getValueMask(): EdValueType = when (getDataLen()) {
        1u -> 0x000000FFu
        2u -> 0x0000FFFFu
        4u -> 0xFFFFFFFFu
        else -> throw IllegalArgumentException("Register.GetValueMask: Invalid length")
    }

    fun getDataLen(): EdValueType = when (regType) {
        RegisterType.RegAb -> 1u
        RegisterType.RegI -> 2u
        RegisterType.RegL -> 4u
        RegisterType.RegF -> 8u // sizeof(EdFloatType)
        RegisterType.RegS -> getArrayData().size.toUInt()
    }

    private fun byteReg(i: Int): EdValueType = (ediabas.byteRegisters[i].toInt() and 0xFF).toUInt()

    fun getValueData(): EdValueType = when (regType) {
        RegisterType.RegAb -> byteReg(index)
        RegisterType.RegI -> {
            val offset = index shl 1
            byteReg(offset) + (byteReg(offset + 1) shl 8)
        }
        RegisterType.RegL -> {
            val offset = index shl 2
            byteReg(offset) + (byteReg(offset + 1) shl 8) + (byteReg(offset + 2) shl 16) + (byteReg(offset + 3) shl 24)
        }
        else -> throw IllegalArgumentException("Register.GetValueData: Invalid data type")
    }

    fun getRawData(): Any = when (regType) {
        RegisterType.RegAb, RegisterType.RegI, RegisterType.RegL -> getValueData()
        RegisterType.RegF -> getFloatData()
        RegisterType.RegS -> getArrayData()
    }

    fun getFloatData(): EdFloatType {
        if (regType != RegisterType.RegF) throw IllegalArgumentException("Register.GetFloatData: Invalid data type")
        return ediabas.floatRegisters[index]
    }

    fun getArrayData(complete: Boolean = false): ByteArray {
        if (regType != RegisterType.RegS) throw IllegalArgumentException("Register.GetArrayData: Invalid data type")
        return ediabas.stringRegisters[index].getData(complete)
    }

    fun setRawData(value: Any) {
        when (regType) {
            RegisterType.RegS -> {
                if (value !is ByteArray) throw IllegalArgumentException("Register.SetRawData: Invalid type")
                setArrayData(value)
            }
            RegisterType.RegF -> {
                if (value !is Double) throw IllegalArgumentException("Register.SetRawData: Invalid type")
                setFloatData(value)
            }
            else -> {
                if (value !is UInt) throw IllegalArgumentException("Register.SetRawData: Invalid type")
                setValueData(value)
            }
        }
    }

    fun setValueData(value: EdValueType) {
        val regs = ediabas.byteRegisters
        when (regType) {
            RegisterType.RegAb -> regs[index] = value.toByte()
            RegisterType.RegI -> {
                val offset = index shl 1
                regs[offset + 0] = value.toByte()
                regs[offset + 1] = (value shr 8).toByte()
            }
            RegisterType.RegL -> {
                val offset = index shl 2
                regs[offset + 0] = value.toByte()
                regs[offset + 1] = (value shr 8).toByte()
                regs[offset + 2] = (value shr 16).toByte()
                regs[offset + 3] = (value shr 24).toByte()
            }
            else -> throw IllegalArgumentException("Register.SetValueData: Invalid data type")
        }
    }

    fun setFloatData(value: EdFloatType) {
        if (regType != RegisterType.RegF) throw IllegalArgumentException("Register.SetFloatData: Invalid data type")
        ediabas.floatRegisters[index] = value
    }

    fun setArrayData(value: ByteArray, keepLength: Boolean = false) {
        if (regType != RegisterType.RegS) throw IllegalArgumentException("Register.SetArrayData: Invalid data type")
        ediabas.stringRegisters[index].setData(value, keepLength)
    }

    fun clearData() {
        if (regType != RegisterType.RegS) throw IllegalArgumentException("Register.SetArrayData: Invalid data type")
        ediabas.stringRegisters[index].clearData()
    }

    companion object {
        /** EdiabasNet.cs: RegisterList (opcode, type, index). */
        fun createRegisterList(ediabas: EdiabasNet): Array<Register> {
            val list = ArrayList<Register>()
            for (i in 0..15) list.add(Register(0x00 + i, RegisterType.RegAb, i, ediabas))
            for (i in 0..7) list.add(Register(0x10 + i, RegisterType.RegI, i, ediabas))
            for (i in 0..3) list.add(Register(0x18 + i, RegisterType.RegL, i, ediabas))
            for (i in 0..7) list.add(Register(0x1C + i, RegisterType.RegS, i, ediabas))
            for (i in 0..7) list.add(Register(0x24 + i, RegisterType.RegF, i, ediabas))
            for (i in 0..7) list.add(Register(0x2C + i, RegisterType.RegS, 8 + i, ediabas))
            for (i in 0..15) list.add(Register(0x80 + i, RegisterType.RegAb, 16 + i, ediabas))
            for (i in 0..7) list.add(Register(0x90 + i, RegisterType.RegI, 8 + i, ediabas))
            for (i in 0..3) list.add(Register(0x98 + i, RegisterType.RegL, 4 + i, ediabas))
            return list.toTypedArray()
        }
    }
}

/** EdiabasNet.cs: class Flags */
internal class Flags {
    var carry = false
    var zero = false
    var sign = false
    var overflow = false

    fun init() {
        carry = false
        zero = false
        sign = false
        overflow = false
    }

    fun updateFlags(value: EdValueType, length: EdValueType) {
        val valueMask: EdValueType
        val signMask: EdValueType
        when (length) {
            1u -> {
                valueMask = 0x000000FFu
                signMask = 0x00000080u
            }
            2u -> {
                valueMask = 0x0000FFFFu
                signMask = 0x00008000u
            }
            4u -> {
                valueMask = 0xFFFFFFFFu
                signMask = 0x80000000u
            }
            else -> throw IllegalArgumentException("Flags.UpdateFlags: Invalid length")
        }
        zero = (value and valueMask) == 0u
        sign = (value and signMask) != 0u
    }

    fun setOverflow(value1: UInt, value2: UInt, result: UInt, length: EdValueType) {
        val signMask: UInt = when (length) {
            1u -> 0x00000080u
            2u -> 0x00008000u
            4u -> 0x80000000u
            else -> throw IllegalArgumentException("Flags.SetOverflow: Invalid length")
        }
        overflow = if ((value1 and signMask) != (value2 and signMask)) {
            false
        } else (value1 and signMask) != (result and signMask)
    }

    fun setCarry(value: ULong, length: EdValueType) {
        val carryMask: ULong = when (length) {
            1u -> 0x000000100UL
            2u -> 0x000010000UL
            4u -> 0x100000000UL
            else -> throw IllegalArgumentException("Flags.SetCarry: Invalid length")
        }
        carry = (value and carryMask) != 0UL
    }

    fun toValue(): EdValueType {
        var value = 0u
        value = value or (if (carry) 0x01u else 0x00u)
        value = value or (if (zero) 0x02u else 0x00u)
        value = value or (if (sign) 0x04u else 0x00u)
        value = value or (if (overflow) 0x08u else 0x00u)
        return value
    }

    fun fromValue(value: EdValueType) {
        carry = (value and 0x01u) != 0u
        zero = (value and 0x02u) != 0u
        sign = (value and 0x04u) != 0u
        overflow = (value and 0x08u) != 0u
    }
}
