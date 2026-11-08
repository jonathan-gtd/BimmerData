/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.MiniPrg.Op
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** String, conversion, float, result, parameter and trap opcodes. */
class OpcodeDataTest {

    @Test
    fun `string and array operations`() {
        Vm().apply {
            setS("S0", 0x41, 0x42, 0x00)
            exec(Op.SCAT, r("S0"), str("CD", terminate = false)); assertEquals("41 42 00 43 44", hexS("S0"))

            setS("S0", 1, 2)
            exec(Op.SCMP, r("S0"), bytes(1, 2)); assertTrue(ed.flags.zero)
            exec(Op.SCMP, r("S0"), bytes(1, 3)); assertFalse(ed.flags.zero)

            setS("S0", "AB")
            exec(Op.STRCAT, r("S0"), str("CD")); assertEquals("41 42 43 44 00", hexS("S0"))
            exec(Op.STRLEN, r("B0"), r("S0")); assertEquals(4, this["B0"])
            exec(Op.SLEN, r("L0"), r("S0")); assertEquals(5, this["L0"])

            // strcmp: zero flag is set when the strings differ (EdiabasLib)
            exec(Op.STRCMP, str("abc"), str("abc")); assertFalse(ed.flags.zero)
            exec(Op.STRCMP, str("abc"), str("abd")); assertTrue(ed.flags.zero)

            setS("S0", "ABCD")
            exec(Op.SCUT, r("S0"), imm8(2)); assertEquals("41 42 43", hexS("S0"))
            exec(Op.SCUT, r("S0"), imm8(10)); assertEquals(0, getS("S0").size)

            setS("S0", "ABCD")
            exec(Op.SPASTE, idx("S0", 1), str("XY", terminate = false)); assertEquals("41 58 59 42 43 44 00", hexS("S0"))
            setS("S0", "ABCD")
            exec(Op.SPASTE, idx("S0", 10), str("X")); assertEquals("41 42 43 44 00", hexS("S0"))

            setS("S0", "ABCD")
            exec(Op.SERASE, idx("S0", 1), imm8(2)); assertEquals("41 44 00", hexS("S0"))

            setS("S0", "ABCD")
            exec(Op.SWAP, idxLen("S0", 0, 3)); assertEquals("43 42 41 44 00", hexS("S0"))
            exec(Op.SREVRS, r("S0")); assertEquals("00 44 41 42 43", hexS("S0"))

            exec(Op.SETSPC, str(";"), imm8(2))
            exec(Op.STOKEN, r("S1"), str("a;b;c")); assertEquals("62 00", hexS("S1")); assertFalse(ed.flags.zero)
            exec(Op.SETSPC, str(";"), imm8(4))
            exec(Op.STOKEN, r("S1"), str("a;b;c")); assertTrue(ed.flags.zero)

            exec(Op.SSIZE, r("L0")); assertEquals(1024, this["L0"])
        }
    }

    @Test
    fun `hex and bcd conversions`() {
        Vm().apply {
            exec(Op.A2Y, r("S0"), str("01 02,03;;0A")); assertEquals("01 02 03 00 0A", hexS("S0"))
            exec(Op.A2Y, r("S0"), str("12 GH")); assertEquals("12", hexS("S0"))
            exec(Op.A2Y, r("S0"), str("1FF")); assertEquals(0, getS("S0").size)
            exec(Op.HEX2Y, r("S0"), str("0AFF")); assertEquals("0A FF", hexS("S0")); assertFalse(ed.flags.carry)
            exec(Op.HEX2Y, r("S0"), str("0AF")); assertEquals("0A", hexS("S0"))
            exec(Op.HEX2Y, r("S0"), str("ZZ")); assertEquals(0, getS("S0").size)
            exec(Op.Y2BCD, r("S0"), bytes(0x12, 0x3A)); assertEquals("31 32 33 2A 00", hexS("S0"))
            exec(Op.Y2HEX, r("S0"), bytes(0x0A, 0xFF)); assertEquals("30 41 46 46 00", hexS("S0"))
        }
    }

    private fun Vm.stringOf(reg: String): String {
        val b = getS(reg)
        return Cp1252.decode(b, 0, if (b.isNotEmpty() && b.last() == 0.toByte()) b.size - 1 else b.size)
    }

    @Test
    fun `integer text conversions`() {
        Vm().apply {
            val cases = mapOf(
                "0x1F" to 0x1FL, "-5" to 0xFFFFFFFBL, "99999999999" to 0xFFFFFFFFL, "-99999999999" to 0x80000000L,
                "abc" to 0L, "12.7" to 12L, "0y101" to 5L, " 42" to 42L, "3;" to 0L,
            )
            for ((text, expected) in cases) {
                exec(Op.A2FIX, r("L0"), str(text)); assertEquals(expected, this["L0"], "a2fix '$text'")
            }
            exec(Op.A2FIX, r("B0"), str("0x1FF")); assertEquals(0xFF, this["B0"])

            this["B0"] = 0xFF; exec(Op.FIX2DEZ, r("S0"), r("B0")); assertEquals("-1", stringOf("S0"))
            this["I0"] = 0x8000; exec(Op.FIX2DEZ, r("S0"), r("I0")); assertEquals("-32768", stringOf("S0"))
            this["L0"] = 0x80000000; exec(Op.FIX2DEZ, r("S0"), r("L0")); assertEquals("-2147483648", stringOf("S0"))
            exec(Op.FIX2DEZ, r("S0"), imm32(0xFFFFFFFF)); assertEquals("-1", stringOf("S0"))
            setS("S1", 0xFE); exec(Op.FIX2DEZ, r("S0"), r("S1")); assertEquals("-2", stringOf("S0"))
            this["B0"] = 0xFF; exec(Op.UFIX2DEZ, r("S0"), r("B0")); assertEquals("255", stringOf("S0"))
            this["L0"] = 0xFFFFFFFF; exec(Op.UFIX2DEZ, r("S0"), r("L0")); assertEquals("4294967295", stringOf("S0"))
            this["I0"] = 0xAB; exec(Op.FIX2HEX, r("S0"), r("I0")); assertEquals("0x00AB", stringOf("S0"))
            this["L0"] = 0x1234ABCD; exec(Op.FIX2HEX, r("S0"), r("L0")); assertEquals("0x1234ABCD", stringOf("S0"))
            this["B0"] = 5; exec(Op.FIX2HEX, r("S0"), r("B0")); assertEquals("0x05", stringOf("S0"))
        }
    }

    @Test
    fun `float conversions`() {
        Vm().apply {
            exec(Op.A2FLT, r("F0"), str("3,5")); assertEquals(3.5, getF("F0"))
            exec(Op.A2FLT, r("F0"), str("1e3")); assertEquals(1000.0, getF("F0"))
            exec(Op.A2FLT, r("F0"), str("abc")); assertEquals(0.0, getF("F0")) // CompatMode=1: no error
            ed.setConfigProperty("CompatMode", "0")
            val ex = assertThrows(EdiabasNetException::class.java) { exec(Op.A2FLT, r("F0"), str("abc")) }
            assertEquals(ErrorCodes.EDIABAS_BIP_0011, ex.errorCode)

            this["B0"] = 0xFF; exec(Op.FIX2FLT, r("F1"), r("B0")); assertEquals(-1.0, getF("F1"))
            this["I0"] = 0x8000; exec(Op.FIX2FLT, r("F1"), r("I0")); assertEquals(-32768.0, getF("F1"))
            this["L0"] = 0x80000000; exec(Op.FIX2FLT, r("F1"), r("L0")); assertEquals(-2147483648.0, getF("F1"))

            setF("F0", -40.0); exec(Op.FLT2FIX, r("L0"), r("F0")); assertEquals(0xFFFFFFD8, this["L0"]); assertTrue(ed.flags.sign)
            setF("F0", 3.99); exec(Op.FLT2FIX, r("L0"), r("F0")); assertEquals(3, this["L0"])
            setF("F0", 4294967295.0); exec(Op.FLT2FIX, r("L0"), r("F0")); assertEquals(0xFFFFFFFF, this["L0"])
            setF("F0", 1e10); exec(Op.FLT2FIX, r("L0"), r("F0")); assertEquals(0x540BE400, this["L0"])

            // rtoa with the default precision of 4 significant digits
            setF("F0", 3.14159); exec(Op.FLT2A, r("S0"), r("F0")); assertEquals("3.142", stringOf("S0"))
            setF("F0", 1234567.0); exec(Op.FLT2A, r("S0"), r("F0")); assertEquals("1235", stringOf("S0"))
            setF("F0", -2.5); exec(Op.FLT2A, r("S0"), r("F0")); assertEquals("-2.5", stringOf("S0"))
            exec(Op.SETFLT, imm8(2))
            setF("F0", 0.5); exec(Op.FLT2A, r("S0"), r("F0")); assertEquals("0.5", stringOf("S0"))

            setF("F0", 1.0); exec(Op.FLT2Y4, idx("S0", 0), r("F0")); assertEquals("00 00 80 3F", hexS("S0"))
            exec(Op.Y42FLT, r("F1"), r("S0")); assertEquals(1.0, getF("F1"))
            setS("S1")
            setF("F0", 1.5); exec(Op.FLT2Y8, idx("S1", 0), r("F0")); assertEquals("00 00 00 00 00 00 F8 3F", hexS("S1"))
            exec(Op.Y82FLT, r("F2"), r("S1")); assertEquals(1.5, getF("F2"))
        }
    }

    @Test
    fun `float arithmetic and compare`() {
        Vm().apply {
            setF("F0", 1.5); setF("F1", 2.25); exec(Op.FADD, r("F0"), r("F1")); assertEquals(3.75, getF("F0"))
            setF("F0", 1.5); exec(Op.FSUB, r("F0"), r("F1")); assertEquals(-0.75, getF("F0"))
            setF("F0", 1.5); exec(Op.FMUL, r("F0"), r("F1")); assertEquals(3.375, getF("F0"))
            setF("F0", 4.5); exec(Op.FDIV, r("F0"), r("F1")); assertEquals(2.0, getF("F0"))

            ed.flags.init()
            setF("F0", 1.0); setF("F1", 2.0); exec(Op.FCOMP, r("F0"), r("F1")); assertFlags("0010")
            setF("F1", 1.0); exec(Op.FCOMP, r("F0"), r("F1")); assertFlags("0100")

            setF("F0", 1.0); setF("F1", 0.0)
            val ex = assertThrows(EdiabasNetException::class.java) { exec(Op.FDIV, r("F0"), r("F1")) }
            assertEquals(ErrorCodes.EDIABAS_BIP_0011, ex.errorCode)
            // with trap bit 8 (BIP-0011) masked the job continues and can test the trap
            exec(Op.SETTMR, imm32(1L shl 8))
            exec(Op.FDIV, r("F0"), r("F1"))
            assertEquals(Double.POSITIVE_INFINITY, getF("F0"))
            assertEquals(8, ed.errorTrapBitNr)
        }
    }

    @Test
    fun `results`() {
        Vm().apply {
            exec(Op.ERGB, str("RB"), imm16(0x1FF))
            this["B0"] = 0xFF; exec(Op.ERGC, str("RC"), r("B0"))
            this["I0"] = 0x8000; exec(Op.ERGI, str("RI"), r("I0")); exec(Op.ERGW, str("RW"), r("I0"))
            this["L0"] = 0xFFFFFFFF; exec(Op.ERGL, str("RL"), r("L0")); exec(Op.ERGD, str("RD"), r("L0"))
            setF("F0", 2.5); exec(Op.ERGR, str("RR"), r("F0"))
            setS("S0", "text"); exec(Op.ERGS, str("RS"), r("S0"))
            setS("S1", 1, 2, 3); exec(Op.ERGY, str("ry"), r("S1"))
            val d = ed.resultDict
            assertEquals(255L, d["RB"]!!.opData); assertEquals(ResultType.TypeB, d["RB"]!!.resType)
            assertEquals(-1L, d["RC"]!!.opData)
            assertEquals(-32768L, d["RI"]!!.opData)
            assertEquals(32768L, d["RW"]!!.opData)
            assertEquals(-1L, d["RL"]!!.opData)
            assertEquals(4294967295L, d["RD"]!!.opData)
            assertEquals(2.5, d["RR"]!!.opData)
            assertEquals("text", d["RS"]!!.opData)
            assertArrayEquals(byteArrayOf(1, 2, 3), d["RY"]!!.opData as ByteArray)
            assertEquals("ry", d["RY"]!!.name)

            exec(Op.ERGSYSI, str("MYSYS"), imm16(5))
            assertEquals(5L, ed.resultSysDict["MYSYS"]!!.opData)
            ed.requestInit = false
            exec(Op.ERGSYSI, str("!INITIALISIERUNG"), imm16(1))
            assertTrue(ed.requestInit)
        }
    }

    @Test
    fun `job parameters`() {
        Vm().apply {
            ed.argString = "3;0x4402;;-7;1,5"
            exec(Op.PARL, r("L0"), imm8(2)); assertEquals(0x4402, this["L0"]); assertFalse(ed.flags.zero)
            exec(Op.PARB, r("B0"), imm8(1)); assertEquals(3, this["B0"])
            exec(Op.PARL, r("L0"), imm8(3)); assertEquals(0, this["L0"]); assertTrue(ed.flags.zero)
            exec(Op.PARL, r("L0"), imm8(4)); assertEquals(0xFFFFFFF9, this["L0"])
            exec(Op.PARL, r("L0"), imm8(9)); assertTrue(ed.flags.zero)
            exec(Op.PARL, r("L0"), imm8(0)); assertTrue(ed.flags.zero) // pos 0 - 1 wraps to 0xFFFFFFFF
            exec(Op.PARS, r("S0"), imm8(2)); assertEquals("0x4402", stringOf("S0"))
            exec(Op.PARR, r("F0"), imm8(5)); assertEquals(1.5, getF("F0"))
            exec(Op.PARN, r("L1")); assertEquals(5, this["L1"])

            ed.argBinary = byteArrayOf(1, 2, 3)
            exec(Op.PARY, r("S1")); assertEquals("01 02 03", hexS("S1")); assertFalse(ed.flags.zero)
        }
    }

    @Test
    fun `configuration and shared memory`() {
        Vm().apply {
            exec(Op.CFGIS, str("MyValue"), imm8(42))
            assertEquals("42", ed.getConfigProperty("MYVALUE"))
            exec(Op.CFGIG, r("L0"), str("myvalue")); assertEquals(42, this["L0"])
            exec(Op.CFGSG, r("S0"), str("MyValue")); assertEquals("34 32", hexS("S0")) // no terminating zero
            exec(Op.CFGIG, r("L1"), str("EdiabasVersion")); assertEquals(7, this["L1"]) // "7.7.3" -> 7

            setS("S0", 9, 8)
            exec(Op.SHMSET, str("Key"), r("S0"))
            exec(Op.SHMGET, r("S1"), str("KEY")); assertEquals("09 08", hexS("S1")); assertFalse(ed.flags.carry)
            exec(Op.SHMGET, r("S1"), str("missing")); assertEquals(0, getS("S1").size); assertTrue(ed.flags.carry)
        }
    }

    @Test
    fun `traps and errors`() {
        Vm().apply {
            exec(Op.SETTMR, imm32(0x400)); exec(Op.GETTMR, r("L0")); assertEquals(0x400, this["L0"])
            exec(Op.SETT, imm8(5)); assertEquals(5, ed.errorTrapBitNr)
            exec(Op.CLRT); assertEquals(-1, ed.errorTrapBitNr)
            exec(Op.SETT, imm8(0)); assertEquals(0x40000000, ed.errorTrapBitNr)

            exec(Op.SETT, imm8(10))
            var ex = assertThrows(EdiabasNetException::class.java) { exec(Op.EERR) }
            assertEquals(ErrorCodes.EDIABAS_BIP_0010, ex.errorCode)
            exec(Op.SETT, imm8(3))
            ex = assertThrows(EdiabasNetException::class.java) { exec(Op.EERR) }
            assertEquals(ErrorCodes.EDIABAS_BIP_0000, ex.errorCode)

            ex = assertThrows(EdiabasNetException::class.java) { exec(Op.GENERR, imm16(251)) }
            assertEquals(ErrorCodes.EDIABAS_RUN_0001, ex.errorCode)
            ex = assertThrows(EdiabasNetException::class.java) { exec(Op.GENERR, imm16(5)) }
            assertEquals(ErrorCodes.EDIABAS_BIP_0001, ex.errorCode)
            ex = assertThrows(EdiabasNetException::class.java) { exec(Op.BREAK) }
            assertEquals(ErrorCodes.EDIABAS_BIP_0008, ex.errorCode)
            assertEquals(ErrorCodes.EDIABAS_BIP_0008, ed.errorCodeLast)
        }
    }
}
