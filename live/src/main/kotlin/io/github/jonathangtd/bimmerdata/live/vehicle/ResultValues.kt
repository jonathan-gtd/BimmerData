/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import java.util.Locale

/** Typed access to EDIABAS result sets. */
internal fun Map<String, ResultData>.str(name: String): String? = (this[name]?.opData as? String)?.trim()

internal fun Map<String, ResultData>.long(name: String): Long? = when (val v = this[name]?.opData) {
    is Long -> v
    is Double -> v.toLong()
    is String -> v.trim().toLongOrNull()
    else -> null
}

internal fun Map<String, ResultData>.double(name: String): Double? = when (val v = this[name]?.opData) {
    is Long -> v.toDouble()
    is Double -> v
    is String -> v.trim().replace(',', '.').toDoubleOrNull()
    else -> null
}

/** A result value as text: hex for binary, numbers without useless decimals. */
internal fun ResultData.formatted(): String = when (val v = opData) {
    is ByteArray -> v.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
    is Double -> formatNumber(v)
    else -> v.toString().trim()
}

internal fun formatNumber(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return v.toString()
    if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) return v.toLong().toString()
    return String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
}

/** Identification results worth showing, in a stable order (names of the IDENT / IDENT_FUNKTIONAL jobs). */
internal val IdentResultNames = listOf(
    "ID_BMW_NR", "ID_HW_NR", "ID_SW_NR_FSV", "ID_SW_NR_OSV", "ID_SW_NR_MCV", "ID_SW_NR_RES",
    "ID_COD_INDEX", "ID_DIAG_INDEX", "ID_VAR_INDEX", "ID_DATUM", "ID_LIEF_NR", "ID_LIEF_TEXT", "SERIENNUMMER",
)

internal fun Map<String, ResultData>.identValues(): List<NamedValue> = IdentResultNames.mapNotNull { name ->
    val r = this[name] ?: return@mapNotNull null
    val text = when {
        name == "ID_DIAG_INDEX" || name == "ID_VAR_INDEX" -> (r.opData as? Long)?.let { String.format("0x%04X", it) } ?: r.formatted()
        else -> r.formatted()
    }
    text.takeIf { it.isNotBlank() }?.let { NamedValue(name, it) }
}
