/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs (classes OpCode, Operand).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

/** EdiabasNet.cs: delegate OperationDelegate */
internal typealias OperationDelegate = (ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) -> Unit

/** EdiabasNet.cs: class OpCode */
internal class OpCode(
    @Suppress("unused") val oc: Int,
    val pneumonic: String,
    val opFunc: OperationDelegate?,
    val arg0IsNearAddress: Boolean = false,
)

/**
 * EdiabasNet.cs: class Operand.
 * opData1..3 hold a [Register], an immediate value ([UInt], C# boxed EdValueType) or an immediate
 * string ([ByteArray]), depending on [addrMode].
 */
internal class Operand(private val ediabas: EdiabasNet) {
    var addrMode: OpAddrMode = OpAddrMode.None
        private set
    var opData1: Any? = null
    var opData2: Any? = null
    var opData3: Any? = null

    fun init(opAddrMode: OpAddrMode, opData1: Any? = null, opData2: Any? = null, opData3: Any? = null) {
        addrMode = opAddrMode
        this.opData1 = opData1
        this.opData2 = opData2
        this.opData3 = opData3
    }

    fun getDataType(): EdDataType = when (addrMode) {
        OpAddrMode.RegS, OpAddrMode.ImmStr, OpAddrMode.IdxImm, OpAddrMode.IdxReg, OpAddrMode.IdxRegImm,
        OpAddrMode.IdxImmLenImm, OpAddrMode.IdxImmLenReg, OpAddrMode.IdxRegLenImm, OpAddrMode.IdxRegLenReg -> EdDataType.Bytes
        else -> EdDataType.Value
    }

    fun getValueMask(dataLen: EdValueType = 0u): EdValueType {
        val len = if (dataLen == 0u) getDataLen() else dataLen
        return when (len) {
            1u -> 0x000000FFu
            2u -> 0x0000FFFFu
            4u -> 0xFFFFFFFFu
            else -> throw IllegalArgumentException("Operand.GetValueMask: Invalid length")
        }
    }

    fun getDataLen(write: Boolean = false): EdValueType = when (addrMode) {
        OpAddrMode.RegS, OpAddrMode.ImmStr -> getArrayData().size.toUInt()
        OpAddrMode.RegAb -> 1u
        OpAddrMode.RegI -> 2u
        OpAddrMode.RegL -> 4u
        OpAddrMode.Imm8 -> 1u
        OpAddrMode.Imm16 -> 2u
        OpAddrMode.Imm32 -> 4u
        OpAddrMode.IdxImm, OpAddrMode.IdxReg, OpAddrMode.IdxRegImm ->
            if (write) 1u else getArrayData().size.toUInt()
        OpAddrMode.IdxImmLenImm, OpAddrMode.IdxImmLenReg, OpAddrMode.IdxRegLenImm, OpAddrMode.IdxRegLenReg ->
            getArrayData().size.toUInt()
        else -> 0u
    }

    private fun reg1(msg: String): Register = opData1 as? Register ?: throw IllegalArgumentException(msg)

    // EdiabasNet.cs: Operand.GetRawData
    fun getRawData(): Any {
        when (addrMode) {
            OpAddrMode.RegS, OpAddrMode.RegAb, OpAddrMode.RegI, OpAddrMode.RegL -> {
                return reg1("Operand.GetRawData RegX: Invalid data type").getRawData()
            }

            OpAddrMode.Imm8, OpAddrMode.Imm16, OpAddrMode.Imm32, OpAddrMode.ImmStr -> {
                return opData1 ?: throw NullPointerException("Operand.GetRawData: no data")
            }

            OpAddrMode.IdxImm, OpAddrMode.IdxReg, OpAddrMode.IdxRegImm -> {
                val arg1Data = reg1("Operand.GetRawData IdxX: Invalid data type")
                val dataArray = arg1Data.getArrayData(true)

                var index: EdValueType = if (addrMode == OpAddrMode.IdxImm) {
                    opData2 as? UInt ?: throw IllegalArgumentException("Operand.GetRawData IdxX: Invalid data type2")
                } else {
                    (opData2 as? Register ?: throw IllegalArgumentException("Operand.GetRawData IdxX: Invalid data type2")).getValueData()
                }

                if (addrMode == OpAddrMode.IdxRegImm) {
                    index += opData3 as? UInt ?: throw IllegalArgumentException("Operand.GetRawData IdxX: Invalid data type3")
                }

                val requiredLength = index.toLong() + 1
                if (requiredLength > ediabas.arrayMaxSize.toLong()) {
                    ediabas.setError(ErrorCodes.EDIABAS_BIP_0001)
                    return ByteArray(0)
                }
                if (dataArray.size < requiredLength) {
                    return ByteArray(0)
                }
                return dataArray.copyOfRange(index.toInt(), dataArray.size)
            }

            OpAddrMode.IdxImmLenImm, OpAddrMode.IdxImmLenReg, OpAddrMode.IdxRegLenImm, OpAddrMode.IdxRegLenReg -> {
                val arg1Data = reg1("Operand.GetRawData IdxXLenX: Invalid data type")
                val dataArray = arg1Data.getArrayData(true)

                val index: EdValueType = if (addrMode == OpAddrMode.IdxImmLenImm || addrMode == OpAddrMode.IdxImmLenReg) {
                    opData2 as? UInt ?: throw IllegalArgumentException("Operand.GetRawData IdxXLenX: Invalid data type")
                } else {
                    (opData2 as? Register ?: throw IllegalArgumentException("Operand.GetRawData IdxXLenX: Invalid data type")).getValueData()
                }

                var len: EdValueType = if (addrMode == OpAddrMode.IdxImmLenImm || addrMode == OpAddrMode.IdxRegLenImm) {
                    opData3 as? UInt ?: throw IllegalArgumentException("Operand.GetRawData IdxXLenX: Invalid data type")
                } else {
                    (opData3 as? Register ?: throw IllegalArgumentException("Operand.GetRawData IdxXLenX: Invalid data type")).getValueData()
                }

                val requiredLength: ULong = index.toULong() + len.toULong()
                if (requiredLength > ediabas.arrayMaxSize.toULong()) {
                    ediabas.setError(ErrorCodes.EDIABAS_BIP_0001)
                    return ByteArray(0)
                }
                if (dataArray.size.toLong() < requiredLength.toLong()) {
                    if (dataArray.size.toLong() <= index.toLong()) {
                        return ByteArray(0)
                    }
                    len = (dataArray.size.toLong() - index.toLong()).toUInt()
                }

                val resultArray = ByteArray(len.toInt())
                System.arraycopy(dataArray, index.toInt(), resultArray, 0, len.toInt())
                return resultArray
            }

            else -> throw IllegalArgumentException("Operand.GetRawData: Invalid address mode")
        }
    }

    // EdiabasNet.cs: Operand.GetValueData
    fun getValueData(dataLen: EdValueType = 0u): EdValueType {
        val rawData = getRawData()
        if (rawData is UInt) {
            return rawData and getValueMask()
        }
        if (rawData !is ByteArray) {
            throw IllegalArgumentException("Operand.GetValueData: Invalid data type")
        }
        if (dataLen == 0u) {
            throw IllegalArgumentException("Operand.GetValueData: Invalid data length")
        }
        var arrayData: ByteArray = rawData
        if (arrayData.size.toLong() < dataLen.toLong()) {
            arrayData = arrayData.copyOf(dataLen.toInt())
        }
        var value: EdValueType = 0u
        for (i in (dataLen.toInt() - 1) downTo 0) {
            value = value shl 8
            value = value or (arrayData[i].toInt() and 0xFF).toUInt()
        }
        return value
    }

    fun getFloatData(): EdFloatType {
        val rawData = getRawData()
        if (rawData !is Double) {
            throw IllegalArgumentException("Operand.GetFloatData: Invalid data type")
        }
        return rawData
    }

    fun getArrayData(): ByteArray {
        val rawData = getRawData()
        if (rawData !is ByteArray) {
            throw IllegalArgumentException("Operand.GetArrayData: Invalid data type")
        }
        return rawData
    }

    fun getStringData(): String {
        val data = getArrayData()
        var length = 0
        while (length < data.size) {
            if (data[length] == 0.toByte()) break
            length++
        }
        return Cp1252.decode(data, 0, length)
    }

    // EdiabasNet.cs: Operand.SetRawData
    fun setRawData(data: Any, dataLen: EdValueType = 1u) {
        when (addrMode) {
            OpAddrMode.RegS -> {
                reg1("Operand.SetRawData RegS: Invalid data type").setRawData(data)
                return
            }

            OpAddrMode.RegAb, OpAddrMode.RegI, OpAddrMode.RegL -> {
                reg1("Operand.SetRawData RegX: Invalid data type").setRawData(data)
                return
            }

            OpAddrMode.IdxImm, OpAddrMode.IdxReg, OpAddrMode.IdxRegImm -> {
                if (data !is UInt && data !is ByteArray) {
                    throw IllegalArgumentException("Operand.SetRawData IdxX: Invalid input data type")
                }
                val arg1Data = reg1("Operand.SetRawData IdxX: Invalid data type")
                var dataArray = arg1Data.getArrayData()

                var index: EdValueType = if (addrMode == OpAddrMode.IdxImm) {
                    opData2 as? UInt ?: throw IllegalArgumentException("Operand.SetRawData IdxX: Invalid data type")
                } else {
                    (opData2 as? Register ?: throw IllegalArgumentException("Operand.SetRawData IdxX: Invalid data type")).getValueData()
                }

                if (addrMode == OpAddrMode.IdxRegImm) {
                    index += opData3 as? UInt ?: throw IllegalArgumentException("Operand.SetRawData IdxX: Invalid data type")
                }

                val len: EdValueType
                val sourceArray: ByteArray
                if (data is UInt) {
                    len = dataLen
                    sourceArray = ByteArray(len.toInt())
                    for (i in 0 until len.toInt()) {
                        sourceArray[i] = (data shr (i shl 3)).toByte()
                    }
                } else {
                    sourceArray = data as ByteArray
                    len = sourceArray.size.toUInt()
                }

                val requiredLength: EdValueType = index + len
                if (requiredLength > ediabas.arrayMaxSize) {
                    ediabas.setError(ErrorCodes.EDIABAS_BIP_0001)
                    return
                }
                if (dataArray.size.toLong() < requiredLength.toLong()) {
                    dataArray = dataArray.copyOf(requiredLength.toInt())
                }

                System.arraycopy(sourceArray, 0, dataArray, index.toInt(), len.toInt())
                arg1Data.setRawData(dataArray)
                return
            }

            else -> throw IllegalArgumentException("Operand.SetRawData: Invalid address mode")
        }
    }

    fun setArrayData(data: ByteArray) {
        val arg1Data = opData1 as? Register ?: throw IllegalArgumentException("Operand.SetArrayData: Invalid data type")
        if (addrMode != OpAddrMode.RegS) {
            throw IllegalArgumentException("Operand.SetArrayData: Invalid address mode")
        }
        arg1Data.setRawData(data)
    }

    fun setStringData(data: String) {
        var dataArray = Cp1252.encode(data)
        val length = dataArray.size
        if (length > 0 && dataArray[length - 1] != 0.toByte()) {
            dataArray = dataArray.copyOf(length + 1)
            dataArray[length] = 0
        }
        setArrayData(dataArray)
    }
}
