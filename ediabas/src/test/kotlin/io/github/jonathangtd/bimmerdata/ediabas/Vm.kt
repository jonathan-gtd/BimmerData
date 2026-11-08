/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.ops.OcList
import org.junit.jupiter.api.Assertions.assertEquals

/** Direct access to the interpreter state to execute single opcodes in unit tests. */
class Vm {
    val ed = EdiabasNet()

    internal fun reg(name: String): Register = ed.getRegister(Arg.regCode(name))

    internal fun r(name: String): Operand = Operand(ed).also { it.init(OpAddrMode.fromValue(Arg.reg(name).mode), reg(name)) }

    internal fun none(): Operand = Operand(ed)

    internal fun imm8(v: Int): Operand = Operand(ed).also { it.init(OpAddrMode.Imm8, (v and 0xFF).toUInt()) }

    internal fun imm16(v: Int): Operand = Operand(ed).also { it.init(OpAddrMode.Imm16, (v and 0xFFFF).toUInt()) }

    internal fun imm32(v: Long): Operand = Operand(ed).also { it.init(OpAddrMode.Imm32, v.toUInt()) }

    internal fun str(s: String, terminate: Boolean = true): Operand = Operand(ed).also { it.init(OpAddrMode.ImmStr, Arg.str(s, terminate).bytes) }

    internal fun bytes(vararg b: Int): Operand = Operand(ed).also { it.init(OpAddrMode.ImmStr, ByteArray(b.size) { i -> b[i].toByte() }) }

    internal fun idx(name: String, index: Int): Operand = Operand(ed).also { it.init(OpAddrMode.IdxImm, reg(name), index.toUInt()) }

    internal fun idxReg(name: String, indexReg: String): Operand = Operand(ed).also { it.init(OpAddrMode.IdxReg, reg(name), reg(indexReg)) }

    internal fun idxLen(name: String, index: Int, len: Int): Operand =
        Operand(ed).also { it.init(OpAddrMode.IdxImmLenImm, reg(name), index.toUInt(), len.toUInt()) }

    internal fun idxRegLenReg(name: String, indexReg: String, lenReg: String): Operand =
        Operand(ed).also { it.init(OpAddrMode.IdxRegLenReg, reg(name), reg(indexReg), reg(lenReg)) }

    internal fun exec(opcode: Int, a0: Operand = none(), a1: Operand = none()) {
        val oc = OcList[opcode]
        oc.opFunc!!(ed, oc, a0, a1)
    }

    operator fun set(name: String, value: Long) {
        reg(name).setValueData(value.toUInt())
    }

    operator fun get(name: String): Long = reg(name).getValueData().toLong()

    fun setS(name: String, vararg b: Int) = reg(name).setArrayData(ByteArray(b.size) { b[it].toByte() })

    fun setS(name: String, s: String) = reg(name).setArrayData(Arg.str(s).bytes)

    fun getS(name: String): ByteArray = reg(name).getArrayData()

    fun hexS(name: String): String = TestEcu.hex(getS(name))

    fun setF(name: String, v: Double) = reg(name).setFloatData(v)

    fun getF(name: String): Double = reg(name).getFloatData()

    /** Flags as "CZSV" bits, e.g. "1100" = carry and zero. */
    fun flags(): String = listOf(ed.flags.carry, ed.flags.zero, ed.flags.sign, ed.flags.overflow).joinToString("") { if (it) "1" else "0" }

    fun assertFlags(expected: String, message: String = "") = assertEquals(expected, flags(), "flags CZSV $message")
}
