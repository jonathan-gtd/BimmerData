/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs (FormatResult).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

internal object EdFormat {

    /**
     * EdiabasNet.cs: FormatResult. Format = [-][width][.precision][E|e]type with type in
     * C B I W L D R T X (apiResultText format). The C# implementation builds .NET composite format
     * strings ("{0,-8:0.000E+000}", "{0,5:X05}"...); the same layouts are produced directly here.
     */
    fun formatResult(resultData: ResultData, format: String): String? {
        var leftAlign = false
        var zeroPrexif = false
        var length1 = -1
        var length2 = -1
        val convertType: Char
        var exponent = '\u0000'

        // remove whitespace
        val formatBare = format.filterNot { Character.isWhitespace(it) || Character.isSpaceChar(it) }
        // parse format
        if (formatBare.isEmpty()) { // string
            convertType = 'T'
        } else {
            var parseString = formatBare
            if (parseString[0] == '-') {
                leftAlign = true
                parseString = parseString.substring(1)
                if (parseString.isEmpty()) return null
            }
            convertType = parseString[parseString.length - 1]
            parseString = parseString.substring(0, parseString.length - 1)

            if (parseString.isNotEmpty()) {
                if (convertType == 'R') {
                    val lastChar = parseString[parseString.length - 1]
                    if (lastChar == 'E' || lastChar == 'e') {
                        exponent = lastChar
                        parseString = parseString.substring(0, parseString.length - 1)
                    }
                }
            }

            if (parseString.isNotEmpty()) {
                val words = parseString.split('.')
                if (words.isEmpty() || words.size > 2) return null
                try {
                    if (words[0].isNotEmpty()) {
                        length1 = EdHelpers.convertToInt32(words[0], 10)
                        if (words[0][0] == '0') zeroPrexif = true
                    }
                    if (words.size > 1) {
                        if (words[1].isNotEmpty()) {
                            length2 = EdHelpers.convertToInt32(words[1], 10)
                        }
                    }
                } catch (e: Exception) {
                    return null
                }
            }
        }

        // convert format
        var valueIsDouble = false
        var valueInt64 = 0L
        var valueDouble = 0.0
        var valueString: String? = null

        when (val opData = resultData.opData) {
            is Long -> valueInt64 = opData
            is Double -> {
                valueDouble = opData
                valueIsDouble = true
            }
            is String -> valueString = opData
            else -> return null
        }

        var convIsDouble = false
        var hexValue = false
        var convInt64 = 0L
        var convDouble = 0.0
        var convString: String? = null
        var validInt = true

        if (valueString != null && convertType != 'T') {
            valueDouble = EdHelpers.stringToFloat(valueString).first
            valueIsDouble = true
            validInt = EdHelpers.stringToValue(valueString).second
        }

        val d2i = { d: Double -> EdHelpers.doubleToInt32(d) }
        when (convertType) {
            'C' -> {
                if (!validInt) return null
                convInt64 = (if (valueIsDouble) d2i(valueDouble).toByte() else valueInt64.toByte()).toLong()
            }
            'B' -> {
                if (!validInt) return null
                convInt64 = ((if (valueIsDouble) d2i(valueDouble).toLong() else valueInt64) and 0xFF)
            }
            'I' -> {
                if (!validInt) return null
                convInt64 = (if (valueIsDouble) d2i(valueDouble).toShort() else valueInt64.toShort()).toLong()
            }
            'W' -> {
                if (!validInt) return null
                convInt64 = ((if (valueIsDouble) d2i(valueDouble).toLong() else valueInt64) and 0xFFFF)
            }
            'L' -> {
                if (!validInt) return null
                convInt64 = (if (valueIsDouble) d2i(valueDouble) else valueInt64.toInt()).toLong()
            }
            'D' -> {
                if (!validInt) return null
                convInt64 = (if (valueIsDouble) EdHelpers.doubleToUInt32(valueDouble) else valueInt64.toUInt()).toLong()
            }
            'R' -> {
                convDouble = if (valueIsDouble) valueDouble else valueInt64.toDouble()
                convIsDouble = true
            }
            'T' -> {
                if (valueString != null) {
                    convString = valueString
                } else if (valueIsDouble) {
                    convDouble = valueDouble
                    exponent = 'E'
                    convIsDouble = true
                } else {
                    convInt64 = valueInt64
                }
            }
            'X' -> {
                if (!validInt) return null
                hexValue = true
                convInt64 = (if (valueIsDouble) EdHelpers.doubleToUInt32(valueDouble) else valueInt64.toUInt()).toLong()
            }
            else -> return null
        }

        // format result
        try {
            if (convString == null) {
                if (convIsDouble) {
                    val decimals = if (length2 > 0) length2 else if (length2 == 0 || length1 >= 0) 0 else 6
                    var resultString = EdHelpers.formatDoubleCustom(convDouble, decimals, if (exponent != '\u0000') exponent else null)
                    if (length1 >= 0 && length1 > resultString.length) {
                        val fillString = " ".repeat(length1 - resultString.length)
                        resultString = if (leftAlign) resultString + fillString else fillString + resultString
                    }
                    return resultString
                }

                if (!hexValue) {
                    val text = if (length2 > 0) EdHelpers.formatZeroPadded(convInt64, length2) else convInt64.toString()
                    return if (length1 >= 0) EdHelpers.align(text, if (leftAlign) -length1 else length1) else text
                }
                // hex value
                val text = EdHelpers.formatHex(convInt64, if (length1 >= 0 && zeroPrexif) length1 else 0)
                return if (length1 >= 0) EdHelpers.align(text, if (leftAlign) -length1 else length1) else text
            } else {
                var resultString: String = convString
                if (length2 >= 0 && length2 < resultString.length) {
                    resultString = resultString.substring(0, length2)
                }
                if (length1 >= 0 && length1 > resultString.length) {
                    val fillString = " ".repeat(length1 - resultString.length)
                    resultString = if (leftAlign) resultString + fillString else fillString + resultString
                }
                return resultString
            }
        } catch (e: Exception) {
            // ignored
        }
        return null
    }
}

/**
 * Tiny subset of .NET composite formatting ("{0}", "{1,-5}", "{0:X04}", "{0:X}") used for log
 * messages only.
 */
internal object NetFormat {
    private val ITEM = Regex("\\{(\\d+)(,(-?\\d+))?(:([^}]*))?}")

    fun format(format: String, args: Array<out Any?>): String =
        ITEM.replace(format) { m ->
            val index = m.groupValues[1].toInt()
            val width = m.groupValues[3].toIntOrNull() ?: 0
            val spec = m.groupValues[5]
            val arg = args.getOrNull(index)
            EdHelpers.align(formatArg(arg, spec), width)
        }

    private fun formatArg(arg: Any?, spec: String): String {
        if (arg == null) return ""
        if (spec.startsWith("X") || spec.startsWith("x")) {
            val digits = spec.substring(1).toIntOrNull() ?: 0
            val hex = when (arg) {
                is Byte -> Integer.toHexString(arg.toInt() and 0xFF)
                is Short -> Integer.toHexString(arg.toInt() and 0xFFFF)
                is Int -> Integer.toHexString(arg)
                is Long -> java.lang.Long.toHexString(arg)
                is UInt -> arg.toString(16)
                is ULong -> arg.toString(16)
                else -> return arg.toString()
            }
            val padded = hex.padStart(digits, '0')
            return if (spec[0] == 'X') padded.uppercase() else padded
        }
        return when (arg) {
            is Double -> EdHelpers.doubleToStringNet(arg)
            else -> arg.toString()
        }
    }
}
