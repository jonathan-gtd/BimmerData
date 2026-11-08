/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import java.util.Locale

/**
 * Labels, units and ranges from the free-form SGBD documentation (result comments, `_EINH`
 * results, SG_FUNKTIONEN columns). Everything here is a heuristic: when nothing reliable is
 * found the unit stays empty and the range null (the UI then picks one).
 */
object MeasurementText {

    /** Units we recognize (lower-case spelling as found in SGBDs) and how we show them. */
    private val units: Map<String, String> = buildMap {
        fun unit(display: String, vararg spellings: String) {
            this[display.lowercase(Locale.ROOT)] = display
            spellings.forEach { this[it] = display }
        }
        unit("°C", "grad celsius", "grad c", "gradc", "degc", "deg c", "° c", "celsius", "°celsius", "grad_c")
        unit("K", "kelvin")
        unit("rpm", "1/min", "u/min", "upm", "min-1", "1/min.")
        unit("km/h", "kmh", "km/std")
        unit("km", "kilometer")
        unit("m", "meter")
        unit("cm")
        unit("mm", "millimeter")
        unit("l", "liter", "ltr", "litre")
        unit("ml", "milliliter")
        unit("l/h")
        unit("l/100km", "l/100 km")
        unit("kg/h")
        unit("g/s")
        unit("mg/hub", "mg/stk", "mg/hub.")
        unit("V", "volt")
        unit("mV", "millivolt")
        unit("A", "ampere")
        unit("mA", "milliampere")
        unit("%", "prozent", "proz", "percent")
        unit("‰", "promille")
        unit("bar")
        unit("mbar")
        unit("hPa")
        unit("kPa")
        unit("Pa")
        unit("psi")
        unit("Nm")
        unit("kW")
        unit("W", "watt")
        unit("Hz", "hertz")
        unit("kHz")
        unit("s", "sek", "sec", "sekunden", "sekunde")
        unit("ms", "msek", "millisekunden")
        unit("µs", "us", "mikrosekunden")
        unit("min", "minuten", "minute")
        unit("h", "std", "stunden", "stunde")
        unit("d", "tage", "tag")
        unit("°", "grad", "deg")
        unit("°KW", "grad kw", "°crk", "gradkw")
        unit("Ω", "ohm")
        unit("kΩ", "kohm")
        unit("lx", "lux")
        unit("m/s²", "m/s^2", "m/s2", "m/s/s")
        unit("°/s", "grad/s")
        unit("km/l")
    }

    /** SGBD unit fields that are not units ("-", "0/1", "0-n", "Text", "hex", "keine"...). */
    private val notUnits = setOf("", "-", "--", "0/1", "0-n", "text", "hex", "keine", "none", "n/a", "na", "bit", "bits", "byte", "bytes", "string", "ascii", "dec", "0/1/2", "1")

    /** "Grad C" → "°C", "U/min" → "rpm", "Liter" → "l"; null if [text] is not a unit we know. */
    fun knownUnit(text: String): String? {
        val t = text.trim().trim('"', '\'', '[', ']', '(', ')', '.', ',', ':', ';').trim()
        if (t.isEmpty()) return null
        return units[t.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")]
    }

    /**
     * Unit column of a table (MESSWERTETAB EINHEIT, SG_FUNKTIONEN EINHEIT): known units are
     * normalized, placeholders ("-", "0-n", "0/1", "Text") become "", anything else is kept as is.
     */
    fun tableUnit(text: String): String {
        val t = text.trim()
        if (t.lowercase(Locale.ROOT) in notUnits) return ""
        return knownUnit(t) ?: t
    }

    private val quoted = Regex("\"([^\"]{1,16})\"")
    private val bracketed = Regex("\\[([^\\]]{1,12})]")
    private val parenthesized = Regex("\\(([^)]{1,12})\\)")
    private val einheit = Regex("Einheit\\s*:?\\s*\"?([^\"\\s,;]{1,12}(?:\\s[A-Za-z]{1,8})?)", RegexOption.IGNORE_CASE)
    private val inUnit = Regex("\\bin\\s+\\[?\"?([^\\s\"\\],;]{1,10}(?:\\s(?:Celsius|C|KW))?)", RegexOption.IGNORE_CASE)
    private val number = "0x[0-9A-Fa-f]+|[-+]?\\d+(?:[.,]\\d+)?"
    private val range = Regex(
        "(?:Wertebereich|Bereich|Range|Werte)\\s*:?\\s*($number)\\s*([^\\s\\d]{0,10}?)\\s*(?:bis|\\.\\.|-|to|…)\\s*($number)\\s*([^\\s,;]{0,10})",
        RegexOption.IGNORE_CASE,
    )
    private val bracketRange = Regex("[\\[(]($number)\\s*(?:-|\\.\\.|bis)\\s*($number)[\\])]")

    /**
     * Unit from the documentation of a value: its own comments ("Bereich: 0 V bis 5 V",
     * 'in "Grad Celsius"', "[km]", "Einheit: %") and the comments of its `_EINH` sibling result
     * ('Einheit "Liter"', "Grad C", "U/min"). Empty if nothing reliable is found.
     */
    fun unitFromComments(valueComments: List<String>, unitComments: List<String>): String {
        for (c in unitComments) {
            knownUnit(c)?.let { return it }
            quoted.findAll(c).forEach { m -> knownUnit(m.groupValues[1])?.let { return it } }
            bracketed.findAll(c).forEach { m -> knownUnit(m.groupValues[1])?.let { return it } }
            einheit.find(c)?.let { m -> knownUnit(m.groupValues[1])?.let { return it } }
            inUnit.find(c)?.let { m -> knownUnit(m.groupValues[1])?.let { return it } }
            c.split(Regex("[\\s:,;]+")).forEach { w -> knownUnit(w)?.takeIf { w.length >= 2 || it == "%" }?.let { return it } }
        }
        for (c in valueComments) {
            range.find(c)?.let { m -> (knownUnit(m.groupValues[4]) ?: knownUnit(m.groupValues[2]))?.let { return it } }
            einheit.find(c)?.let { m -> knownUnit(m.groupValues[1])?.let { return it } }
            quoted.findAll(c).forEach { m -> knownUnit(m.groupValues[1])?.let { return it } }
            bracketed.findAll(c).forEach { m -> knownUnit(m.groupValues[1])?.let { return it } }
            inUnit.findAll(c).forEach { m -> knownUnit(m.groupValues[1])?.let { return it } }
            parenthesized.findAll(c).forEach { m -> knownUnit(m.groupValues[1])?.takeIf { it != "°" && it != "d" && it != "m" }?.let { return it } }
        }
        return ""
    }

    /** Range written in the comments ("Bereich: -40 bis 215", "[1-12]", "0x00 bis 0xFF"), else null. */
    fun rangeFromComments(comments: List<String>): Pair<Double, Double>? {
        for (c in comments) {
            range.find(c)?.let { m -> pair(m.groupValues[1], m.groupValues[3])?.let { return it } }
        }
        for (c in comments) {
            bracketRange.find(c)?.let { m -> pair(m.groupValues[1], m.groupValues[2])?.let { return it } }
        }
        return null
    }

    private fun pair(a: String, b: String): Pair<Double, Double>? {
        val lo = parseNumber(a) ?: return null
        val hi = parseNumber(b) ?: return null
        return if (hi > lo) lo to hi else null
    }

    private fun parseNumber(s: String): Double? {
        val t = s.trim()
        return if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toLongOrNull(16)?.toDouble() else t.replace(',', '.').toDoubleOrNull()
    }

    /**
     * Gauge range for well-known units, as for MESSWERTETAB rows ([EdiabasMeasurementCatalog.plausibleRange]);
     * null for other units.
     */
    fun rangeForUnit(unit: String, resultName: String): Pair<Double, Double>? {
        val name = resultName.uppercase(Locale.ROOT)
        return when (unit) {
            "°C" -> -40.0 to 150.0
            "rpm" -> if ("GRADIENT" in name) null else 0.0 to 7000.0
            "%" -> if ("INTEGRATOR" in name || "ADAPTION" in name || "GEMISCH" in name) -25.0 to 25.0 else 0.0 to 100.0
            "V" -> 0.0 to 16.0
            "km/h" -> 0.0 to 260.0
            "hPa" -> 0.0 to 1100.0
            "kg/h" -> 0.0 to 800.0
            else -> null
        }
    }

    private val noise = listOf(
        Regex("^(Job)?beschreibung\\s*:\\s*", RegexOption.IGNORE_CASE),
        Regex("\\s*in\\s+\\[[^\\]]*]", RegexOption.IGNORE_CASE),
        Regex("\\s*in\\s+\"[^\"]*\"", RegexOption.IGNORE_CASE),
        Regex("\\s*\\[[^\\]]{1,12}]"),
        Regex("\\s*[\\[(]\\s*[-+]?\\d+\\s*(?:-|\\.\\.|bis)\\s*[-+]?\\d+\\s*[\\])]"),
        Regex("\\s*[,;]?\\s*(Aufl(oe|ö)sung|Bereich|Wertebereich|Einheit|Range|Resolution)\\b.*$", RegexOption.IGNORE_CASE),
    )
    private val junkLabel = Regex(
        "^(-+|\\?|wert|value|ergebnis|result|status|text|siehe\\b.*|see\\b.*|table\\b.*|tabelle\\b.*|okay\\b.*|ok\\b.*|0x.*|.*=.*|.*:)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Display label of a job result: the first meaningful comment line, without unit / range /
     * resolution notes ("Aussentemperatur in "Grad Celsius" (gefiltert)" → "Aussentemperatur
     * (gefiltert)"); else the result name made readable ("STAT_SPANNUNG_KLEMME_30A_WERT" →
     * "Spannung Klemme 30A").
     */
    fun label(resultName: String, comments: List<String>): String {
        for (raw in comments) {
            var t = raw.replace('\t', ' ').trim()
            for (r in noise) t = t.replace(r, "")
            t = t.replace(inWord) { m -> if (knownUnit(m.groupValues[1]) != null) "" else m.value }
            t = t.replace(Regex("\\s+"), " ").trim().trimEnd(',', ';', ':', '.').trim()
            // a unit alone ("Liter") or a unit statement is not a label
            if (t.length < 3 || t.startsWith("\"") || knownUnit(t) != null || junkLabel.matches(t)) continue
            if (inUnitAlone(t)) continue
            return if (t.length > 80) t.take(77).trimEnd() + "…" else t
        }
        return humanize(resultName)
    }

    private val inWord = Regex("\\s+in\\s+([^\\s,;()]+)", RegexOption.IGNORE_CASE)

    private fun inUnitAlone(t: String): Boolean = t.startsWith("in ", ignoreCase = true) && knownUnit(t.substring(3)) != null

    private val statPrefix = Regex("^(STAT_|STATUS_|ID_|IDENT_)", RegexOption.IGNORE_CASE)
    private val valueSuffix = Regex("(_WERT|_TEXT|_NR|_INFO|_EIN)$", RegexOption.IGNORE_CASE)

    /** "STAT_SPANNUNG_KLEMME_30A_WERT" → "Spannung Klemme 30A". */
    fun humanize(name: String): String {
        val core = name.trim().replace(statPrefix, "").replace(valueSuffix, "").trim('_')
        val words = core.split('_').filter { it.isNotEmpty() }
        if (words.isEmpty()) return name
        return words.joinToString(" ") { w ->
            if (w.any { it.isDigit() } || w.length <= 3) w else w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    /** First line of a job's comments, cleaned like a label (empty if none). */
    fun jobTitle(comments: List<String>): String {
        val first = comments.firstOrNull { it.isNotBlank() && !it.contains("KWP2000", ignoreCase = true) && !it.startsWith("UDS", ignoreCase = true) } ?: return ""
        return first.replace('\t', ' ').replace(noise[0], "").replace(Regex("\\s+"), " ").trim()
    }
}
