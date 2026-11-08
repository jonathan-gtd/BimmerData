/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

import java.text.Normalizer
import java.util.Locale

/*
 * Translations of the texts of the ECU description files (SGBDs): one CSV file per SGBD and per
 * language, `translations/<lang>/<sgbd>.csv`, columns `type;cle;de;traduction;statut`.
 * See translations/README.md in the repository for the format.
 */

/**
 * What a translation entry translates. [id] is the value of the `type` column (French words, as
 * decided for the file format); English spellings are accepted when reading.
 */
enum class TranslationType(val id: String, vararg val aliases: String) {
    /** MESSWERTETAB row, key = its ID ("0x4402"), text = INFO. */
    Measurement("mesure", "measurement", "measure"),

    /** Job result, key = "JOB/RESULT" (job "*" = every job of the SGBD), text = the label shown for it. */
    Result("resultat", "result", "résultat"),

    /** Fault location, key = FORTTEXTE.ORT ("0x2F08"), text = ORTTEXT without the code. */
    Fault("defaut", "fault", "défaut"),

    /** Fault symptom / type, key = ARTNR of FARTTEXTEINDIVIDUELL or FARTTEXTE ("0x0A"). */
    Symptom("symptome", "symptom", "symptôme"),

    /** Job, key = job name, text = first line of the job comment. */
    Job("job"),

    /** Row of a small text table, key = "TABLE/value" ("LAMBDASTATUS/0x02"). */
    Table("table"),

    /** The ECU itself, empty key, text = ECU description of the SGBD. */
    Ecu("ecu");

    companion object {
        private val byName: Map<String, TranslationType> = entries.flatMap { t -> (listOf(t.id) + t.aliases).map { it to t } }.toMap()

        fun fromId(text: String): TranslationType? = byName[text.trim().lowercase(Locale.ROOT)]
    }
}

/** `statut` column: [Auto] = machine or unchecked translation, [Reviewed] = read by a human. */
enum class TranslationStatus(val id: String, vararg val aliases: String) {
    Auto("auto"),
    Reviewed("relu", "reviewed", "ok");

    companion object {
        /** Unknown or empty status counts as [Auto]. */
        fun fromId(text: String): TranslationStatus {
            val t = text.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { it.id == t || t in it.aliases } ?: Auto
        }
    }
}

/** One line of a translation file. [key] is normalized ([TranslationKeys.normalize]). */
data class TranslationEntry(
    val type: TranslationType,
    val key: String,
    /** Original German text, as in the SGBD the file was made from (guard, see [GermanText]). */
    val de: String,
    /** Translated text; empty = not translated yet. */
    val text: String,
    val status: TranslationStatus = TranslationStatus.Auto,
) {
    val translated: Boolean get() = text.isNotBlank()
}

/**
 * Canonical spelling of the `cle` column, so that "0x2f08", "0X2F08", "2F08" and "12040" all match.
 * Measurement ids and fault codes of 1 to 4 characters without "0x" are hex (they are always
 * shown that way, "2F08"); longer plain digits are decimal. Other numbers: "0x" = hex, else decimal.
 */
object TranslationKeys {
    private val bareHex = Regex("^[0-9A-Fa-f]{1,4}$")

    /** [type] + [key] as written in a file (or built by the app) -> canonical key. */
    fun normalize(type: TranslationType, key: String): String {
        val k = key.trim()
        return when (type) {
            TranslationType.Measurement, TranslationType.Fault ->
                (if (bareHex.matches(k)) hex(k.toLong(16), 4) else number(k, 4)) ?: k.uppercase(Locale.ROOT)
            TranslationType.Symptom -> number(k, 2) ?: k.uppercase(Locale.ROOT)
            TranslationType.Job -> k.uppercase(Locale.ROOT)
            TranslationType.Result -> {
                val slash = k.lastIndexOf('/')
                if (slash < 0) "*/" + k.uppercase(Locale.ROOT) else k.substring(0, slash).trim().uppercase(Locale.ROOT).ifEmpty { "*" } + "/" + k.substring(slash + 1).trim().uppercase(Locale.ROOT)
            }
            TranslationType.Table -> {
                val slash = k.indexOf('/')
                if (slash < 0) {
                    k.uppercase(Locale.ROOT)
                } else {
                    val v = k.substring(slash + 1).trim()
                    k.substring(0, slash).trim().uppercase(Locale.ROOT) + "/" + (number(v, 2) ?: v.uppercase(Locale.ROOT))
                }
            }
            TranslationType.Ecu -> ""
        }
    }

    /** Key of a MESSWERTETAB id or a fault code: "0x4402". */
    fun hex(value: Long, minDigits: Int = 4): String = "0x" + java.lang.Long.toHexString(value).uppercase(Locale.ROOT).padStart(minDigits, '0')

    /** "0x2F08" / "12040" -> canonical hex, null if [text] is not a number. */
    private fun number(text: String, minDigits: Int): String? {
        val t = text.trim()
        val v = when {
            t.startsWith("0x", ignoreCase = true) -> t.substring(2).toLongOrNull(16)
            t.isNotEmpty() && t.all { it.isDigit() } -> t.toLongOrNull()
            else -> null
        } ?: return null
        if (v < 0) return null
        return hex(v, minDigits)
    }

    fun result(job: String, result: String): String = normalize(TranslationType.Result, "$job/$result")

    fun anyJobResult(result: String): String = normalize(TranslationType.Result, "*/$result")
}

/**
 * Comparison of German texts for the `de` guard: the translation of a key is only used when the
 * German text of the user's SGBD is still the one it was made from. Case, spacing, punctuation and
 * the spelling of umlauts ("ueber" / "über") are ignored.
 */
object GermanText {
    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        val folded = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val sb = StringBuilder(folded.length)
        for (c in folded) if (c in 'a'..'z' || c in '0'..'9') sb.append(c)
        return sb.toString()
    }

    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)
}
