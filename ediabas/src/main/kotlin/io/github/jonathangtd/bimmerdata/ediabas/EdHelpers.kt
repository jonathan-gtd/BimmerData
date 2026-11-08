/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs (static helpers), plus emulation of the .NET runtime behaviour
 * the C# code relies on (Convert.ToInt64/ToByte, double.Parse, double.ToString, custom numeric formats,
 * code page 1252, unchecked double-to-integer casts).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** C# EdValueType (UInt32) and EdFloatType (double). */
typealias EdValueType = UInt
typealias EdFloatType = Double

/**
 * Windows code page 1252 exactly as .NET Encoding.GetEncoding(1252) decodes it: the five undefined
 * bytes 0x81, 0x8D, 0x8F, 0x90, 0x9D map to U+0081.. (java's windows-1252 would give U+FFFD), so a
 * byte -> string -> byte round trip is lossless for all 256 values. Characters outside the code page
 * encode to '?' (the .NET best-fit mapping, e.g. 'Ā' -> 'A', is not emulated).
 */
object Cp1252 {
    private val HIGH = charArrayOf(
        '€', '\u0081', '‚', 'ƒ', '„', '…', '†', '‡',
        'ˆ', '‰', 'Š', '‹', 'Œ', '\u008D', 'Ž', '\u008F',
        '\u0090', '‘', '’', '“', '”', '•', '–', '—',
        '˜', '™', 'š', '›', 'œ', '\u009D', 'ž', 'Ÿ',
    )
    private val REVERSE: Map<Char, Byte> = HIGH.withIndex().associate { (i, c) -> c to (0x80 + i).toByte() }

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        val chars = CharArray(length)
        for (i in 0 until length) {
            val b = bytes[offset + i].toInt() and 0xFF
            chars[i] = if (b in 0x80..0x9F) HIGH[b - 0x80] else b.toChar()
        }
        return String(chars)
    }

    fun encode(s: String): ByteArray {
        val out = ByteArray(s.length)
        for (i in s.indices) {
            val c = s[i]
            out[i] = when {
                c.code < 0x80 || c.code in 0xA0..0xFF -> c.code.toByte()
                else -> REVERSE[c] ?: '?'.code.toByte()
            }
        }
        return out
    }
}

/** Helpers that mirror .NET semantics. Kept internal; the public EDIABAS ones are re-exported by EdiabasNet. */
internal object EdHelpers {

    // ---- culture invariant case mapping (char by char, like .NET ToUpper(InvariantCulture)) ----

    fun upperInv(s: String): String {
        val chars = s.toCharArray()
        for (i in chars.indices) chars[i] = Character.toUpperCase(chars[i])
        return String(chars)
    }

    fun lowerInv(s: String): String {
        val chars = s.toCharArray()
        for (i in chars.indices) chars[i] = Character.toLowerCase(chars[i])
        return String(chars)
    }

    // ---- System.ParseNumbers (Convert.ToInt64(string, base), Convert.ToByte(string, base)) ----

    private const val IS_TIGHT = 0x1000
    private const val TREAT_AS_UNSIGNED = 0x0200

    /** ParseNumbers.StringToLong: throws NumberFormatException (FormatException), ArithmeticException (Overflow) or IllegalArgumentException. */
    private fun parseNumbersStringToLong(s: String, radix: Int, flags: Int): Long {
        var i = 0
        var r = radix
        var sign = 1
        val length = s.length
        if (length == 0) throw IllegalArgumentException("Index out of range")
        if (s[i] == '-') {
            if (r != 10) throw IllegalArgumentException("Cannot have negative value")
            if ((flags and TREAT_AS_UNSIGNED) != 0) throw ArithmeticException("Overflow negative unsigned")
            sign = -1
            i++
        } else if (s[i] == '+') {
            i++
        }
        if (radix == 16 && i + 1 < length && s[i] == '0') {
            if (s[i + 1] == 'x' || s[i + 1] == 'X') {
                r = 16
                i += 2
            }
        }
        val start = i
        // GrabLongs
        var result = 0UL
        val unsigned = (flags and TREAT_AS_UNSIGNED) != 0
        if (r == 10 && !unsigned) {
            val maxVal = 0x7FFFFFFFFFFFFFFFUL / 10UL
            while (i < length) {
                val v = digitValue(s[i], r) ?: break
                if (result > maxVal || result.toLong() < 0) throw ArithmeticException("Overflow")
                result = result * r.toULong() + v.toULong()
                i++
            }
            if (result.toLong() < 0 && result != 0x8000000000000000UL) throw ArithmeticException("Overflow")
        } else {
            val maxVal = ULong.MAX_VALUE / r.toULong()
            while (i < length) {
                val v = digitValue(s[i], r) ?: break
                if (result > maxVal) throw ArithmeticException("Overflow")
                val temp = result * r.toULong() + v.toULong()
                if (temp < result) throw ArithmeticException("Overflow")
                result = temp
                i++
            }
        }
        if (i == start) throw NumberFormatException("No parsable digits")
        if ((flags and IS_TIGHT) != 0 && i < length) throw NumberFormatException("Extra junk at end")
        if (result == 0x8000000000000000UL && sign == 1 && r == 10 && !unsigned) throw ArithmeticException("Overflow")
        var value = result.toLong()
        if (r == 10) value *= sign
        return value
    }

    private fun digitValue(c: Char, radix: Int): Int? {
        val v = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'Z' -> c - 'A' + 10
            in 'a'..'z' -> c - 'a' + 10
            else -> return null
        }
        return if (v < radix) v else null
    }

    /** Convert.ToInt64(string, fromBase). */
    fun convertToInt64(s: String, fromBase: Int): Long = parseNumbersStringToLong(s, fromBase, IS_TIGHT)

    /** Convert.ToInt32(string, fromBase). */
    fun convertToInt32(s: String, fromBase: Int): Int {
        val v = parseNumbersStringToLong(s, fromBase, IS_TIGHT)
        if (fromBase == 10) {
            if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw ArithmeticException("Overflow")
            return v.toInt()
        }
        if (v.toULong() > 0xFFFFFFFFUL) throw ArithmeticException("Overflow")
        return v.toInt()
    }

    /** Convert.ToByte(string, fromBase). */
    fun convertToByte(s: String, fromBase: Int): Int {
        val v = parseNumbersStringToLong(s, fromBase, IS_TIGHT or TREAT_AS_UNSIGNED)
        if (v.toULong() > 0xFFUL) throw ArithmeticException("Overflow")
        return v.toInt()
    }

    // ---- EdiabasNet.cs static helpers ----

    /** EdiabasNet.cs: StringToValue(string number, out bool valid) */
    fun stringToValue(number: String): Pair<Long, Boolean> {
        var value = 0L
        var valid = false
        val numberLocal = trimEndNet(number)
        if (numberLocal.isEmpty()) return Pair(value, valid)
        val numberLower = lowerInv(numberLocal)
        try {
            if (numberLower.startsWith("0x")) { // hex
                if (numberLower.length > 2) {
                    val firstChar = numberLower[2]
                    if (Character.isDigit(firstChar) || (firstChar in 'a'..'f')) {
                        value = convertToInt64(numberLocal.substring(2), 16)
                        valid = true
                    }
                }
            } else if (numberLower.startsWith("0y")) { // binary
                value = convertToInt64(numberLocal.substring(2), 2)
                valid = true
            } else { // dec
                if (numberLower != "-" && numberLower != "--") {
                    if (!Character.isLetter(numberLower[0])) {
                        var numberConv = trimStartNet(numberLocal)
                        val index = numberConv.indexOfAny(charArrayOf('.', ','))
                        if (index >= 0) numberConv = numberConv.substring(0, index)
                        value = convertToInt64(numberConv, 10)
                        valid = true
                    }
                }
            }
        } catch (e: Exception) {
            value = 0
        }
        return Pair(value, valid)
    }

    /** .NET String.TrimStart() / TrimEnd() use Char.IsWhiteSpace. */
    private fun isNetWhiteSpace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085'

    fun trimStartNet(s: String): String = s.trimStart { isNetWhiteSpace(it) }

    fun trimEndNet(s: String): String = s.trimEnd { isNetWhiteSpace(it) }

    /** EdiabasNet.cs: StringToFloat(string number, out bool valid) */
    fun stringToFloat(number: String): Pair<Double, Boolean> {
        var result = 0.0
        var valid = false
        try {
            // Encoding.ASCII round trip check: only pure ASCII strings are accepted
            if (number.all { it.code < 0x80 }) {
                val n = number.replace(",", ".")
                result = doubleParseInvariant(n)
                valid = true
            }
        } catch (e: Exception) {
            result = 0.0
        }
        return Pair(result, valid)
    }

    private val FLOAT_REGEX = Regex("^[\\t\\n\\u000B\\f\\r ]*([+-]?)([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?[\\t\\n\\u000B\\f\\r \\u0000]*$")

    /** double.Parse(s, CultureInfo.InvariantCulture) with NumberStyles.Float | AllowThousands (.NET Core 3.0+ semantics: overflow gives infinity). */
    fun doubleParseInvariant(s: String): Double {
        if (FLOAT_REGEX.matches(s)) {
            val trimmed = s.trim { it == ' ' || it in '\t'..'\r' || it == '\u0000' }
            return trimmed.toDouble()
        }
        val t = s.trim { it == ' ' || it in '\t'..'\r' }
        return when {
            t.equals("Infinity", true) || t.equals("+Infinity", true) -> Double.POSITIVE_INFINITY
            t.equals("-Infinity", true) -> Double.NEGATIVE_INFINITY
            t.equals("NaN", true) || t.equals("+NaN", true) || t.equals("-NaN", true) -> Double.NaN
            else -> throw NumberFormatException("Invalid double: $s")
        }
    }

    /** EdiabasNet.cs: HexToByteArray */
    fun hexToByteArray(valueStr: String): ByteArray {
        return try {
            val length = valueStr.length - valueStr.length % 2
            ByteArray(length / 2) { convertToByte(valueStr.substring(it * 2, it * 2 + 2), 16).toByte() }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /** EdiabasNet.cs: ValueToBcd */
    fun valueToBcd(value: Int): String {
        val part1 = (value shr 4) and 0x0F
        val part2 = value and 0x0F
        val sb = StringBuilder()
        sb.append(if (part1 > 9) "*" else part1.toString(16).uppercase())
        sb.append(if (part2 > 9) "*" else part2.toString(16).uppercase())
        return sb.toString()
    }

    /** EdiabasNet.cs: RoundToSignificantDigits (Math.Round(double, int) = MidpointRounding.ToEven). */
    fun roundToSignificantDigits(value: Double, digits: UInt): Double {
        if (value == 0.0) return 0.0
        val scale = 10.0.pow(floor(log10(abs(value))) + 1)
        return scale * mathRound(value / scale, digits.toInt())
    }

    private val ROUND_POWER10 = DoubleArray(16) { 10.0.pow(it) }

    /** System.Math.Round(double value, int digits) */
    fun mathRound(value: Double, digits: Int): Double {
        if (digits < 0 || digits > 15) throw IllegalArgumentException("Math.Round: digits out of range")
        var v = value
        if (abs(v) < 1e16) {
            val power10 = ROUND_POWER10[digits]
            v *= power10
            v = Math.rint(v)
            v /= power10
        }
        return v
    }

    // ---- unchecked double -> integer casts (x86/x64 .NET Framework / .NET <= 8 behaviour) ----

    /** (Int32)doubleValue: cvttsd2si, NaN and out-of-range give Int32.MinValue. */
    fun doubleToInt32(d: Double): Int {
        if (d.isNaN() || d >= 2147483648.0 || d <= -2147483649.0) return Int.MIN_VALUE
        return d.toInt()
    }

    /** (UInt32)doubleValue: converted through a 64-bit cvttsd2si, then truncated (negative values wrap). */
    fun doubleToUInt32(d: Double): UInt {
        if (d.isNaN() || d >= 9.223372036854775807E18 || d < -9.223372036854775808E18) return 0u
        return d.toLong().toUInt()
    }

    // ---- .NET number formatting ----

    /**
     * double.ToString() / string.Format("{0}", double) with InvariantCulture, .NET Core 3.0+ semantics:
     * shortest round-trippable digits, scientific notation when the exponent is >= 15 or < -5.
     */
    fun doubleToStringNet(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == Double.POSITIVE_INFINITY) return "Infinity"
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
        val negative = d < 0 || (d == 0.0 && 1.0 / d < 0)
        if (d == 0.0) return if (negative) "-0" else "0"
        val x = abs(d)
        var bd = BigDecimal(x)
        for (p in 1..17) {
            val r = BigDecimal(x).round(MathContext(p, RoundingMode.HALF_EVEN))
            if (r.toDouble() == x) {
                bd = r
                break
            }
        }
        bd = bd.stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val scale = digits.length - bd.scale()
        val nMaxDigits = maxOf(digits.length, 15)
        val sb = StringBuilder()
        if (negative) sb.append('-')
        formatGeneral(sb, digits, scale, nMaxDigits)
        return sb.toString()
    }

    /** Number.Formatting.cs: FormatGeneral (expChar 'E', min 2 exponent digits). */
    private fun formatGeneral(sb: StringBuilder, digits: String, scale: Int, nMaxDigits: Int) {
        var digPos = scale
        var scientific = false
        if (digPos > nMaxDigits || digPos < -3) {
            digPos = 1
            scientific = true
        }
        var dig = 0
        if (digPos > 0) {
            do {
                sb.append(if (dig < digits.length) digits[dig++] else '0')
            } while (--digPos > 0)
        } else {
            sb.append('0')
        }
        if (dig < digits.length || digPos < 0) {
            sb.append('.')
            while (digPos < 0) {
                sb.append('0')
                digPos++
            }
            while (dig < digits.length) sb.append(digits[dig++])
        }
        if (scientific) {
            val e = scale - 1
            sb.append('E')
            sb.append(if (e < 0) '-' else '+')
            sb.append(abs(e).toString().padStart(2, '0'))
        }
    }

    /**
     * Custom numeric format "0", "0.000", "0.000E+000" (one integer digit, [decimals] fraction digits)
     * applied to a double: the value is first reduced to 15 significant digits, then rounded half away
     * from zero, like .NET's custom format path. A result that rounds to zero carries no minus sign.
     */
    fun formatDoubleCustom(value: Double, decimals: Int, exponentChar: Char?): String {
        if (value.isNaN()) return "NaN"
        if (value == Double.POSITIVE_INFINITY) return "Infinity"
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
        val negative = value < 0
        val bd15 = if (value == 0.0) BigDecimal.ZERO else BigDecimal(abs(value)).round(MathContext(15, RoundingMode.HALF_EVEN))
        if (exponentChar == null) {
            val r = bd15.setScale(decimals, RoundingMode.HALF_UP)
            val zero = r.signum() == 0
            return (if (negative && !zero) "-" else "") + r.toPlainString()
        }
        var exp = 0
        var mant = BigDecimal.ZERO.setScale(decimals)
        if (bd15.signum() != 0) {
            exp = bd15.precision() - bd15.scale() - 1
            mant = bd15.movePointLeft(exp).setScale(decimals, RoundingMode.HALF_UP)
            if (mant.compareTo(BigDecimal.TEN) >= 0) {
                exp++
                mant = bd15.movePointLeft(exp).setScale(decimals, RoundingMode.HALF_UP)
            }
        }
        val zero = mant.signum() == 0
        val expStr = (if (exp < 0) "-" else "+") + abs(exp).toString().padStart(3, '0')
        return (if (negative && !zero) "-" else "") + mant.toPlainString() + exponentChar + expStr
    }

    /** Composite format alignment {0,width}: negative width = left aligned. */
    fun align(s: String, width: Int): String =
        when {
            width > 0 && s.length < width -> s.padStart(width)
            width < 0 && s.length < -width -> s.padEnd(-width)
            else -> s
        }

    /** Format "X" / "Xn" for Int64. */
    fun formatHex(value: Long, minDigits: Int): String = java.lang.Long.toHexString(value).uppercase().padStart(minDigits, '0')

    /** Custom format "000" (n zeros) for Int64: zero padded absolute value. */
    fun formatZeroPadded(value: Long, zeros: Int): String {
        val abs = if (value < 0) value.toULong().let { (0UL - it) }.toString() else value.toString()
        return (if (value < 0) "-" else "") + abs.padStart(zeros, '0')
    }

    /** Hex dump "{0:X02} " per byte, as used by the C# log helpers. */
    fun hexDump(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String {
        val sb = StringBuilder(length * 3)
        for (i in 0 until length) sb.append(String.format("%02X ", data[offset + i].toInt() and 0xFF))
        return sb.toString()
    }
}
