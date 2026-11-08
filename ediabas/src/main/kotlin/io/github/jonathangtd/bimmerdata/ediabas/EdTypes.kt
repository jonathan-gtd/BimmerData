/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

/** EdiabasNet.cs: enum ResultType */
enum class ResultType {
    TypeB,  // 8 bit
    TypeW,  // 16 bit
    TypeD,  // 32 bit
    TypeQ,  // 64 bit
    TypeC,  // 8 bit char
    TypeI,  // 16 bit signed
    TypeL,  // 32 bit signed
    TypeLL, // 64 bit signed
    TypeR,  // float
    TypeS,  // string
    TypeY,  // array
}

/**
 * EdiabasNet.cs: class ResultData. [opData] is a Long (C# Int64) for integer types, a Double for TypeR,
 * a String for TypeS and a ByteArray for TypeY.
 */
class ResultData(val resType: ResultType, val name: String, val opData: Any) {
    override fun toString(): String = when (opData) {
        is ByteArray -> "$name=[${EdHelpers.hexDump(opData).trimEnd()}]"
        else -> "$name=$opData"
    }
}

/** EdiabasNet.cs: enum EdLogLevel */
enum class EdLogLevel(val level: Int) {
    Off(0),
    Ifh(1),
    Error(2),
    Info(3),
}

/** EdiabasNet.cs: enum CallSource */
enum class CallSource {
    Default,
    EdiabasOperation,
}

/** EdiabasNet.cs: enum IfhStatusCodes (kept for API completeness). */
enum class IfhStatusCodes(val code: Int) {
    IFHREADY(0),
    IFHBUSY(1),
    IFHERROR(2),
}

/** EdiabasNet.cs: enum RegisterType */
internal enum class RegisterType {
    RegAb,  // 8 bit
    RegI,   // 16 bit
    RegL,   // 32 bit
    RegF,   // float
    RegS,   // string
}

/** EdiabasNet.cs: enum OpAddrMode (ordinal = encoded value). */
internal enum class OpAddrMode {
    None,
    RegS,
    RegAb,
    RegI,
    RegL,
    Imm8,
    Imm16,
    Imm32,
    ImmStr,
    IdxImm,
    IdxReg,
    IdxRegImm,
    IdxImmLenImm,
    IdxImmLenReg,
    IdxRegLenImm,
    IdxRegLenReg;

    companion object {
        private val VALUES = entries.toTypedArray()

        fun fromValue(value: Int): OpAddrMode = VALUES[value]
    }
}

/**
 * Replaces the C# `Type` checks (typeof(EdValueType), typeof(byte[]), typeof(EdFloatType)) done on
 * operands and registers.
 */
internal enum class EdDataType {
    Value,
    Bytes,
    Float,
}

/** EdiabasNet.cs: class EdiabasNetException */
class EdiabasNetException(message: String, val errorCode: ErrorCodes) : RuntimeException(message)
