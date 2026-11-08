/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Allocation-light number formatting for live values: locale decimal separator (comma in French),
 * a real minus sign, no grouping (2350, not 2 350, so digits never jump around).
 */
class GaugeFormat(val locale: Locale) {
    val decimalSeparator: Char = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    val french: Boolean = locale.language == "fr"
    private val sb = StringBuilder(16)

    /** [signed] prints "+" for positive values (fuel trims). */
    fun format(value: Double, decimals: Int, signed: Boolean = false): String {
        if (value.isNaN() || value.isInfinite()) return NO_DATA
        val d = decimals.coerceIn(0, 4)
        val pow = POW10[d]
        val scaled = (abs(value) * pow).roundToLong()
        sb.setLength(0)
        if (scaled != 0L) {
            if (value < 0) sb.append(MINUS) else if (signed) sb.append('+')
        }
        sb.append(scaled / pow)
        if (d > 0) {
            sb.append(decimalSeparator)
            val frac = (scaled % pow).toString()
            repeat(d - frac.length) { sb.append('0') }
            sb.append(frac)
        }
        return sb.toString()
    }

    /** "104", "+2,3 %": value then unit with a (narrow no-break) space, as in French typography. */
    fun withUnit(value: String, unit: String): String = when {
        unit.isEmpty() -> value
        unit == "°" -> value + unit
        else -> "$value $unit"
    }

    /** Unit as shown on screen: SGBD units are terse or German ("rpm", "°CRK", "-"). */
    fun displayUnit(unit: String, label: String = ""): String = when (unit.trim()) {
        "rpm", "1/min", "U/min" -> if (french) "tr/min" else "rpm"
        "°CRK", "°KW", "°TPS" -> "°"
        "-", "" -> if (label.contains("lambda", ignoreCase = true)) "λ" else ""
        "0-n" -> ""
        else -> unit.trim()
    }

    companion object {
        const val NO_DATA = "—"
        const val MINUS = '−'
        private val POW10 = longArrayOf(1, 10, 100, 1000, 10000)

        /** Sensible default number of decimals for a unit. */
        fun defaultDecimals(unit: String, label: String = ""): Int = when (unit.trim()) {
            "rpm", "1/min", "U/min", "°C", "hPa", "kPa", "km/h", "km", "s", "mg/stk", "l" -> 0
            "%", "°CRK", "°KW", "°TPS", "V", "kg/h", "g/s", "A" -> 1
            "-", "" -> if (label.contains("lambda", ignoreCase = true)) 2 else 1
            else -> 1
        }

        /** Fallback gauge scale when neither the card nor the SGBD gives one. */
        fun defaultRange(unit: String): Pair<Double, Double> = when (unit.trim()) {
            "°C" -> 0.0 to 150.0
            "rpm", "1/min", "U/min" -> 0.0 to 7000.0
            "%" -> 0.0 to 100.0
            "V" -> 8.0 to 16.0
            "km/h" -> 0.0 to 260.0
            "hPa" -> 0.0 to 1200.0
            "-" -> 0.8 to 1.2
            else -> 0.0 to 100.0
        }
    }
}
