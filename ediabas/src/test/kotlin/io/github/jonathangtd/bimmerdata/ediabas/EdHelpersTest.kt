/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** .NET semantics emulation used by the interpreter (parsing, formatting, code page). */
class EdHelpersTest {

    @Test
    fun `StringToValue like Convert ToInt64`() {
        val cases = listOf(
            "0x10" to (16L to true), "0X1f" to (31L to true), "0x" to (0L to false), "0xG1" to (0L to false),
            "0y101" to (5L to true), "  12  " to (12L to true), "-12" to (-12L to true), "+12" to (12L to true),
            "12abc" to (0L to false), "1.5" to (1L to true), "1,9" to (1L to true), "abc" to (0L to false),
            "-" to (0L to false), "" to (0L to false), "0xFFFFFFFFFFFFFFFF" to (-1L to true),
            "0x1FFFFFFFFFFFFFFFF" to (0L to false), "9223372036854775807" to (Long.MAX_VALUE to true),
            "9223372036854775808" to (0L to false), "-9223372036854775808" to (Long.MIN_VALUE to true),
        )
        for ((text, expected) in cases) {
            assertEquals(expected, EdiabasNet.stringToValueValid(text), "StringToValue('$text')")
        }
    }

    @Test
    fun `StringToFloat like double Parse invariant`() {
        val cases = listOf(
            "1,5" to (1.5 to true), " 2.5 " to (2.5 to true), "-1e-3" to (-0.001 to true), ".5" to (0.5 to true),
            "1e400" to (Double.POSITIVE_INFINITY to true), "abc" to (0.0 to false), "Ö1" to (0.0 to false),
            "Infinity" to (Double.POSITIVE_INFINITY to true), "1.2.3" to (0.0 to false), "0x10" to (0.0 to false),
        )
        for ((text, expected) in cases) {
            assertEquals(expected, EdiabasNet.stringToFloatValid(text), "StringToFloat('$text')")
        }
    }

    @Test
    fun `HexToByteArray`() {
        assertEquals("01 02 AB", TestEcu.hex(EdiabasNet.hexToByteArray("0102AB")))
        assertEquals("", TestEcu.hex(EdiabasNet.hexToByteArray("0")))
        assertEquals("0F", TestEcu.hex(EdiabasNet.hexToByteArray("+F"))) // Convert.ToByte accepts a sign
        assertEquals("", TestEcu.hex(EdiabasNet.hexToByteArray("01GG")))
    }

    @Test
    fun `double ToString like dotnet core`() {
        val cases = listOf(
            0.1 to "0.1", 1e15 to "1E+15", 1e14 to "100000000000000", 0.0001 to "0.0001", 0.00001 to "1E-05",
            -60.0 to "-60", 1.0 / 3 to "0.3333333333333333", 123456789012345680000.0 to "1.2345678901234568E+20",
            5e-324 to "5E-324", Double.MAX_VALUE to "1.7976931348623157E+308", 0.0 to "0", -0.0 to "-0",
            Double.NaN to "NaN", 2.5 to "2.5", 100.0 to "100",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, EdHelpers.doubleToStringNet(value), "ToString($value)")
        }
    }

    @Test
    fun `round to significant digits uses banker's rounding`() {
        assertEquals(3.142, EdHelpers.roundToSignificantDigits(3.14159, 4u), 1e-12)
        assertEquals(0.0, EdHelpers.roundToSignificantDigits(0.0, 4u))
        assertEquals(2.0, EdHelpers.mathRound(2.5, 0))
        assertEquals(4.0, EdHelpers.mathRound(3.5, 0))
    }

    @Test
    fun `double to integer casts`() {
        assertEquals(0xFFFFFFD8u, EdHelpers.doubleToUInt32(-40.0))
        assertEquals(0u, EdHelpers.doubleToUInt32(Double.NaN))
        assertEquals(Int.MIN_VALUE, EdHelpers.doubleToInt32(3e9))
        assertEquals(-3, EdHelpers.doubleToInt32(-3.9))
    }

    @Test
    fun `cp1252 round trip of all bytes`() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(TestEcu.hex(all), TestEcu.hex(Cp1252.encode(Cp1252.decode(all))))
        assertEquals("€Öß", Cp1252.decode(byteArrayOf(0x80.toByte(), 0xD6.toByte(), 0xDF.toByte())))
        assertEquals("3F", TestEcu.hex(Cp1252.encode("Ā")))
    }

    private fun fmt(value: Any, format: String): String? =
        EdiabasNet.formatResult(ResultData(ResultType.TypeS, "X", value), format)

    @Test
    fun `FormatResult`() {
        assertEquals("FF", fmt(255L, "X"))
        assertEquals("  FF", fmt(255L, "4X"))
        assertEquals("00FF", fmt(255L, "04X"))
        assertEquals("FF  ", fmt(255L, "-4X"))
        assertEquals("255", fmt(-1L, "B"))
        assertEquals("44", fmt(300L, "C"))
        assertEquals("-1", fmt(65535L, "I"))
        assertEquals("    0042", fmt(42L, "8.4L"))
        assertEquals("42      ", fmt(42L, "-8L"))
        assertEquals("1234.000", fmt(1234L, "8.3R"))
        assertEquals("3.141590", fmt(3.14159, "R"))
        assertEquals("3.14", fmt(3.14159, ".2R"))
        assertEquals("3.142E+000", fmt(3.14159, ".3ER"))
        assertEquals("-1.235e+003", fmt(-1234.5, ".3eR"))
        assertEquals("3", fmt(2.5, "1.0R")) // custom format rounds half away from zero
        assertEquals("3", fmt(2.5, ".0R"))
        assertEquals("  abc", fmt("abc", "5T"))
        assertEquals("abc  ", fmt("abc", "-5T"))
        assertEquals("ab", fmt("abc", ".2T"))
        assertEquals("12", fmt("12", "D"))
        assertNull(fmt("abc", "D"))
        assertEquals("2", fmt(2.5, "L"))
        assertEquals("5", fmt(5L, ""))
        assertEquals("1.500000E+000", fmt(1.5, ""))
        assertNull(fmt(1L, "Q"))
        assertNull(fmt(1L, "-"))
    }
}
