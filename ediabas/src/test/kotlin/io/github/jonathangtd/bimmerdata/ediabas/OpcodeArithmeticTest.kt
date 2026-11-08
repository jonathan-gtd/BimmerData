/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.MiniPrg.Op
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Single opcode semantics: arithmetic, logic, shifts and their flags (CZSV = carry, zero, sign,
 * overflow) for 1/2/4 byte operands and edge values. Expected values follow EdOperations.cs,
 * including its known deviations from a real CPU (documented per test).
 */
class OpcodeArithmeticTest {

    private fun vm(carry: Boolean = false) = Vm().also { it.ed.flags.init(); it.ed.flags.carry = carry }

    @Test
    fun `adds 8 bit`() {
        vm().apply { this["B0"] = 0xFF; exec(Op.ADDS, r("B0"), imm8(1)); assertEquals(0x00, this["B0"]); assertFlags("1100") }
        vm().apply { this["B0"] = 0x7F; exec(Op.ADDS, r("B0"), imm8(1)); assertEquals(0x80, this["B0"]); assertFlags("0011") }
        vm().apply { this["B0"] = 0x80; this["B1"] = 0x80; exec(Op.ADDS, r("B0"), r("B1")); assertEquals(0, this["B0"]); assertFlags("1101") }
        // the source is not masked to the destination size: carry comes from bit 8 of 0x101
        vm().apply { this["B0"] = 0; exec(Op.ADDS, r("B0"), imm32(0x101)); assertEquals(0x01, this["B0"]); assertFlags("1000") }
    }

    @Test
    fun `adds 16 and 32 bit`() {
        vm().apply { this["I0"] = 0xFFFF; exec(Op.ADDS, r("I0"), imm16(1)); assertEquals(0, this["I0"]); assertFlags("1100") }
        vm().apply { this["I0"] = 0x7FFF; exec(Op.ADDS, r("I0"), imm16(1)); assertEquals(0x8000, this["I0"]); assertFlags("0011") }
        vm().apply { this["L0"] = 0xFFFFFFFF; exec(Op.ADDS, r("L0"), imm32(1)); assertEquals(0, this["L0"]); assertFlags("1100") }
        vm().apply { this["L0"] = 0x7FFFFFFF; exec(Op.ADDS, r("L0"), imm32(1)); assertEquals(0x80000000, this["L0"]); assertFlags("0011") }
        vm().apply {
            this["L0"] = 0x80000000; this["L1"] = 0x80000000
            exec(Op.ADDS, r("L0"), r("L1")); assertEquals(0, this["L0"]); assertFlags("1101")
        }
        // high registers A0 / I8 / L4 share the byte register file layout
        vm().apply { this["L4"] = 0x12345678; assertEquals(0x78, this["A0"]); assertEquals(0x5678, this["I8"]) }
    }

    @Test
    fun `addc uses and produces carry`() {
        vm(carry = true).apply { this["B0"] = 0xFE; exec(Op.ADDC, r("B0"), imm8(1)); assertEquals(0, this["B0"]); assertFlags("1100") }
        vm(carry = false).apply { this["L0"] = 1; exec(Op.ADDC, r("L0"), imm32(1)); assertEquals(2, this["L0"]); assertFlags("0000") }
        // EdiabasLib: val1++ wraps 0xFFFFFFFF to 0 before the addition, so no carry is produced
        vm(carry = true).apply {
            this["L0"] = 0xFFFFFFFF
            exec(Op.ADDC, r("L0"), imm32(0xFFFFFFFF)); assertEquals(0xFFFFFFFF, this["L0"]); assertFlags("0010")
        }
    }

    @Test
    fun `subb and subc`() {
        vm().apply { this["B0"] = 0; exec(Op.SUBB, r("B0"), imm8(1)); assertEquals(0xFF, this["B0"]); assertFlags("1010") }
        vm().apply { this["B0"] = 0x80; exec(Op.SUBB, r("B0"), imm8(1)); assertEquals(0x7F, this["B0"]); assertFlags("0001") }
        vm().apply { this["I0"] = 5; exec(Op.SUBB, r("I0"), imm16(5)); assertEquals(0, this["I0"]); assertFlags("0100") }
        vm().apply { this["L0"] = 0x80000000; exec(Op.SUBB, r("L0"), imm32(1)); assertEquals(0x7FFFFFFF, this["L0"]); assertFlags("0001") }
        // EdiabasLib overflow formula uses -val1: 0 - 0x80000000 gives no overflow (a CPU would set V)
        vm().apply {
            this["L0"] = 0; this["L1"] = 0x80000000
            exec(Op.SUBB, r("L0"), r("L1")); assertEquals(0x80000000, this["L0"]); assertFlags("1010")
        }
        vm(carry = true).apply { this["B0"] = 5; exec(Op.SUBC, r("B0"), imm8(2)); assertEquals(2, this["B0"]); assertFlags("0000") }
        // carry increments the unmasked source: 0 - 0x100 -> 0x00 with borrow
        vm(carry = true).apply { this["B0"] = 0; exec(Op.SUBC, r("B0"), imm8(0xFF)); assertEquals(0, this["B0"]); assertFlags("1100") }
    }

    @Test
    fun `comp sets flags without storing`() {
        vm().apply { this["L0"] = 3; exec(Op.COMP, r("L0"), imm32(5)); assertEquals(3, this["L0"]); assertFlags("1010") }
        vm().apply { this["I0"] = 0x8000; exec(Op.COMP, r("I0"), imm16(1)); assertFlags("0001") }
        vm().apply { this["B0"] = 7; exec(Op.COMP, r("B0"), imm8(7)); assertFlags("0100") }
    }

    @Test
    fun `mult signed with high part in a register operand`() {
        vm().apply {
            this["B0"] = 0xFF; this["B1"] = 0x02
            exec(Op.MULT, r("B0"), r("B1")); assertEquals(0xFE, this["B0"]); assertEquals(0xFF, this["B1"]); assertFlags("0010")
        }
        vm().apply {
            this["I0"] = 0x0100; this["I1"] = 0x0100
            exec(Op.MULT, r("I0"), r("I1")); assertEquals(0, this["I0"]); assertEquals(1, this["I1"]); assertFlags("0100")
        }
        vm().apply {
            this["L0"] = 0x10000; this["L1"] = 0x10000
            exec(Op.MULT, r("L0"), r("L1")); assertEquals(0, this["L0"]); assertEquals(0, this["L1"]); assertFlags("0100")
        }
        vm().apply { this["L0"] = 0xFFFFFFFD; exec(Op.MULT, r("L0"), imm32(7)); assertEquals(0xFFFFFFEB, this["L0"]); assertFlags("0010") }
    }

    @Test
    fun `divs signed 32 bit, unsigned 8 bit quirk, division errors`() {
        vm().apply { this["L0"] = 7; this["L1"] = 2; exec(Op.DIVS, r("L0"), r("L1")); assertEquals(3, this["L0"]); assertEquals(1, this["L1"]) }
        vm().apply {
            this["L0"] = 0xFFFFFFF9; this["L1"] = 2
            exec(Op.DIVS, r("L0"), r("L1")); assertEquals(0xFFFFFFFD, this["L0"]); assertEquals(0xFFFFFFFF, this["L1"]); assertFlags("0010")
        }
        // "DIVS failure in ediabas": 8/16 bit values are divided as positive Int32
        vm().apply { this["B0"] = 0xF9; this["B1"] = 2; exec(Op.DIVS, r("B0"), r("B1")); assertEquals(0x7C, this["B0"]); assertEquals(1, this["B1"]) }
        vm().apply {
            this["L0"] = 1
            val ex = assertThrows(EdiabasNetException::class.java) { exec(Op.DIVS, r("L0"), imm32(0)) }
            assertEquals(ErrorCodes.EDIABAS_BIP_0007, ex.errorCode)
        }
        vm().apply {
            this["L0"] = 0x80000000; this["L1"] = 0xFFFFFFFF
            val ex = assertThrows(EdiabasNetException::class.java) { exec(Op.DIVS, r("L0"), r("L1")) }
            assertEquals(ErrorCodes.EDIABAS_BIP_0007, ex.errorCode)
        }
    }

    @Test
    fun `logic operations`() {
        vm(carry = true).apply { this["B0"] = 0xF0; exec(Op.AND, r("B0"), imm8(0x0F)); assertEquals(0, this["B0"]); assertFlags("1100") }
        vm().apply { this["L0"] = 0x80000000; exec(Op.OR, r("L0"), imm32(1)); assertEquals(0x80000001, this["L0"]); assertFlags("0010") }
        vm().apply { this["I0"] = 0xFFFF; exec(Op.XOR, r("I0"), imm16(0xFFFF)); assertEquals(0, this["I0"]); assertFlags("0100") }
        vm().apply { this["B0"] = 0x80; exec(Op.TEST, r("B0"), imm8(0x80)); assertEquals(0x80, this["B0"]); assertFlags("0010") }
        vm().apply { this["B0"] = 0x0F; exec(Op.NOT, r("B0")); assertEquals(0xF0, this["B0"]); assertFlags("0010") }
        vm().apply { this["L0"] = 0; exec(Op.NOT, r("L0")); assertEquals(0xFFFFFFFF, this["L0"]); assertFlags("0010") }
    }

    @Test
    fun `shift left`() {
        vm().apply { this["B0"] = 0x81; exec(Op.ASL, r("B0"), imm8(1)); assertEquals(0x02, this["B0"]); assertFlags("1000") }
        // shift == width: EdiabasLib tests bit 0 for the carry (carryShift = 0)
        vm().apply { this["B0"] = 0x01; exec(Op.ASL, r("B0"), imm8(8)); assertEquals(0, this["B0"]); assertFlags("1100") }
        vm().apply { this["B0"] = 0xFF; exec(Op.ASL, r("B0"), imm8(9)); assertEquals(0, this["B0"]); assertFlags("0100") }
        vm().apply { this["L0"] = 0x80000001; exec(Op.ASL, r("L0"), imm32(1)); assertEquals(2, this["L0"]); assertFlags("1000") }
        vm(carry = true).apply { this["L0"] = 5; exec(Op.LSL, r("L0"), imm32(0)); assertEquals(5, this["L0"]); assertFlags("0000") }
        vm().apply { this["I0"] = 0x4000; exec(Op.LSL, r("I0"), imm16(1)); assertEquals(0x8000, this["I0"]); assertFlags("0010") }
        // negative 32 bit shift count: nothing shifted, carry untouched
        vm(carry = true).apply {
            this["L0"] = 5; this["L1"] = 0xFFFFFFFF
            exec(Op.ASL, r("L0"), r("L1")); assertEquals(5, this["L0"]); assertFlags("1000")
        }
    }

    @Test
    fun `shift right logical and arithmetic`() {
        vm().apply { this["B0"] = 0x81; exec(Op.LSR, r("B0"), imm8(1)); assertEquals(0x40, this["B0"]); assertFlags("1000") }
        vm().apply { this["L0"] = 0x80000000; exec(Op.LSR, r("L0"), imm32(31)); assertEquals(1, this["L0"]); assertFlags("0000") }
        vm().apply { this["B0"] = 0xFF; exec(Op.LSR, r("B0"), imm8(8)); assertEquals(0, this["B0"]); assertFlags("1100") }
        vm().apply { this["B0"] = 0x80; exec(Op.ASR, r("B0"), imm8(1)); assertEquals(0xC0, this["B0"]); assertFlags("0010") }
        vm().apply { this["B0"] = 0x80; exec(Op.ASR, r("B0"), imm8(9)); assertEquals(0xFF, this["B0"]); assertFlags("1010") }
        vm().apply { this["I0"] = 0x8000; exec(Op.ASR, r("I0"), imm16(15)); assertEquals(0xFFFF, this["I0"]); assertFlags("0010") }
        vm().apply { this["L0"] = 0xFFFFFFFE; exec(Op.ASR, r("L0"), imm32(1)); assertEquals(0xFFFFFFFF, this["L0"]); assertFlags("0010") }
        vm().apply { this["L0"] = 0x40000000; exec(Op.ASR, r("L0"), imm32(30)); assertEquals(1, this["L0"]); assertFlags("0000") }
    }

    @Test
    fun `move between registers, immediates and indexed strings`() {
        vm().apply { exec(Op.MOVE, r("L0"), imm8(0xFF)); assertEquals(0xFF, this["L0"]); assertFlags("0000") }
        vm().apply { exec(Op.MOVE, r("B0"), imm32(0x1234)); assertEquals(0x34, this["B0"]); assertFlags("0000") }
        vm(carry = true).apply { exec(Op.MOVE, r("B0"), imm8(0x80)); assertEquals(0x80, this["B0"]); assertFlags("0010") }
        vm().apply {
            exec(Op.MOVE, r("S0"), str("ABC"))
            assertEquals("41 42 43 00", hexS("S0"))
            exec(Op.MOVE, r("B0"), idx("S0", 1)); assertEquals(0x42, this["B0"])
            exec(Op.MOVE, r("I0"), idx("S0", 0)); assertEquals(0x4241, this["I0"])
            exec(Op.MOVE, idx("S0", 2), imm8(0x55)); assertEquals("41 42 55 00", hexS("S0"))
            exec(Op.MOVE, idx("S0", 5), imm8(0x55)); assertEquals("41 42 55 00 00 55", hexS("S0"))
            exec(Op.MOVE, r("S2"), idxLen("S0", 1, 2)); assertEquals("42 55", hexS("S2"))
            this["I1"] = 0; this["I2"] = 2
            exec(Op.MOVE, r("S3"), idxRegLenReg("S0", "I1", "I2")); assertEquals("41 42", hexS("S3"))
            this["B3"] = 2
            exec(Op.MOVE, r("B4"), idxReg("S0", "B3")); assertEquals(0x55, this["B4"])
            // an indexed source without length is the rest of the complete register buffer (1024 bytes)
            exec(Op.MOVE, r("S1"), idx("S0", 1))
            assertEquals(1023, getS("S1").size)
            assertEquals("42 55 00", TestEcu.hex(getS("S1").copyOf(3)))
        }
    }

    @Test
    fun `clear and flag opcodes`() {
        vm(carry = true).apply {
            setS("S0", "ABC"); exec(Op.CLEAR, r("S0")); assertEquals(0, getS("S0").size); assertFlags("0100")
            this["L0"] = 0x1234; exec(Op.CLEAR, r("L0")); assertEquals(0, this["L0"])
            setF("F0", 1.5); exec(Op.CLEAR, r("F0")); assertEquals(0.0, getF("F0"))
            exec(Op.SETC); assertFlags("1100")
            exec(Op.CLRC); assertFlags("0100")
            ed.flags.overflow = true
            exec(Op.CLRV); assertFlags("0100")
        }
    }

    @Test
    fun `stack push pop atsp pushf popf`() {
        vm().apply {
            this["L0"] = 0x11223344
            exec(Op.PUSH, r("L0"))
            exec(Op.ATSP, r("B0"), imm8(1)); assertEquals(0x11, this["B0"])
            exec(Op.ATSP, r("I0"), imm8(4)); assertEquals(0x3344, this["I0"])
            exec(Op.POP, r("L1")); assertEquals(0x11223344, this["L1"])
            assertEquals(0, ed.stackList.size)

            ed.flags.carry = true; ed.flags.zero = false; ed.flags.sign = true; ed.flags.overflow = true
            exec(Op.PUSHF)
            ed.flags.init()
            exec(Op.POPF); assertFlags("1011")

            this["B0"] = 0xAB; this["I0"] = 0x1234
            exec(Op.PUSH, r("B0")); exec(Op.PUSH, r("I0"))
            val ex = assertThrows(EdiabasNetException::class.java) { exec(Op.POP, r("L0")) }
            assertEquals(ErrorCodes.EDIABAS_BIP_0005, ex.errorCode)
        }
    }

    @Test
    fun `errors are not raised when masked by the trap mask`() {
        vm().apply {
            // BIP-0007 is not in the trap bit table: bit 0
            exec(Op.SETTMR, imm32(0x00000001))
            this["L0"] = 0x80000000; this["L1"] = 0xFFFFFFFF
            // C# raises BIP_0007 unconditionally for divs (IsMinVersion760), the mask does not help here
            assertThrows(EdiabasNetException::class.java) { exec(Op.DIVS, r("L0"), r("L1")) }
            // but SetError based errors are masked
            exec(Op.POP, r("L2")) // BIP_0005 -> bit 0, masked: value 0, no exception
            assertEquals(0, this["L2"])
            assertEquals(0, ed.errorTrapBitNr)
        }
    }
}
