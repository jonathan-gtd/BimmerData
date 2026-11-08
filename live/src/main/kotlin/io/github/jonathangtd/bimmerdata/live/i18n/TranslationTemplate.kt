/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

import io.github.jonathangtd.bimmerdata.ediabas.PrgFile
import io.github.jonathangtd.bimmerdata.live.EdiabasMeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.GenericMeasurements
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.MeasurementText
import io.github.jonathangtd.bimmerdata.live.MesswerteTab
import io.github.jonathangtd.bimmerdata.live.SgbdDescription
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultService
import java.io.File
import java.util.Locale

/**
 * Builds the translation template of one SGBD from the user's own file: every translatable key
 * with its German text in `de` and an empty translation, in this order:
 *
 * | type | from |
 * |---|---|
 * | `ecu` | ECU / ECUCOMMENT lines of the description block |
 * | `mesure` | MESSWERTETAB: ID → INFO |
 * | `resultat` | job results the measurement catalog offers (read-only jobs without arguments, SG_FUNKTIONEN), label as shown in the app; one `*` entry when a result has the same text in every job |
 * | `job` | every documented job → first line of its comment |
 * | `defaut` | FORTTEXTE: ORT → ORTTEXT without the leading code |
 * | `symptome` | FARTTEXTEINDIVIDUELL, else FARTTEXTE: ARTNR → ARTTEXT |
 * | `table` | other tables whose first column is a key and that have a text column (UWTEXT, TEXT, ARTTEXT, BITTEXT): FUMWELTTEXTE, state tables (LAMBDASTATUS…), fault status texts… Rows whose text is an identifier (`PASSIVE`, `ERROR_ECU_…`) are left out. |
 */
object TranslationTemplate {
    private val textColumns = listOf("UWTEXT", "TEXT", "ARTTEXT", "BITTEXT", "ORTTEXT")
    private val symptomTables = listOf("FARTTEXTEINDIVIDUELL", "FARTTEXTE")
    private val identifier = Regex("^[A-Z0-9_ .:/+\\-]*$")
    private const val MaxTableRows = 3000

    private val technicalLine = Regex("^(0x[0-9A-Fa-f]+\\s*)?[A-Z0-9_]*$")

    /**
     * Translatable description of a job: its first comment line that is a sentence, i.e. not a
     * service id line ("0x301001 INNENTEMP_LESEN", "KWP2000: $2C...", "UDS..."). Empty if none.
     * Tool32 shows the same text.
     */
    fun jobText(comments: List<String>): String {
        val line = comments.map { it.replace('\t', ' ').trim() }.firstOrNull { c ->
            c.isNotEmpty() && !technicalLine.matches(c) && !c.contains("KWP2000", ignoreCase = true) && !c.startsWith("UDS", ignoreCase = true) &&
                !c.startsWith("$") && c.any { it.isLowerCase() }
        } ?: return ""
        return MeasurementText.jobTitle(listOf(line))
    }

    fun generate(sgbd: String, file: File): List<TranslationEntry> =
        generate(sgbd, PrgFile.open(file), SgbdDescription.read(file))

    fun generate(sgbd: String, prg: PrgFile, desc: SgbdDescription?): List<TranslationEntry> {
        val key = EdiabasMeasurementCatalog.normalize(sgbd)
        val out = ArrayList<TranslationEntry>()
        val seen = HashSet<Pair<TranslationType, String>>()
        fun add(type: TranslationType, k: String, de: String) {
            val text = de.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return
            val nk = TranslationKeys.normalize(type, k)
            if (seen.add(type to nk)) out += TranslationEntry(type, nk, text, "")
        }

        desc?.summary?.let { add(TranslationType.Ecu, "", it) }

        val tab = runCatching { MesswerteTab.load(key, prg) }.getOrNull()
        tab?.rows?.forEach { row -> add(TranslationType.Measurement, TranslationKeys.hex(row.id.toLong()), row.info.ifBlank { row.resultName }) }

        // job results, as the catalog labels them
        val exclude = tab?.rows?.map { it.resultName.uppercase(Locale.ROOT) }?.toSet().orEmpty()
        val results = ArrayList<Triple<String, String, String>>() // job, result, label
        if (desc != null) {
            GenericMeasurements.fromJobs(key, desc, exclude).forEach { m ->
                val s = m.source as MeasurementSource.JobResult
                results += Triple(s.job, s.resultName, m.label)
            }
        }
        runCatching { GenericMeasurements.fromSgFunktionen(key, prg, exclude) }.getOrDefault(emptyList()).forEach { m ->
            val s = m.source as MeasurementSource.JobResult
            results += Triple(s.job, s.resultName, m.label)
        }
        val byResult = results.groupBy { it.second.uppercase(Locale.ROOT) }
        for ((name, list) in byResult) {
            val texts = list.map { GermanText.normalize(it.third) }.distinct()
            if (list.size > 1 && texts.size == 1) add(TranslationType.Result, "*/$name", list.first().third)
            else list.forEach { (job, result, label) -> add(TranslationType.Result, "$job/$result", label) }
        }

        desc?.jobs?.forEach { j ->
            val title = jobText(j.comments)
            if (title.isNotBlank()) add(TranslationType.Job, j.name, title)
        }

        prg.table("FORTTEXTE")?.let { t ->
            val cKey = t.column("ORT") ?: 0
            val cText = t.column("ORTTEXT") ?: 1
            for (r in t.rows) {
                val code = MesswerteTab.parseInt(r[cKey]) ?: continue
                add(TranslationType.Fault, TranslationKeys.hex(code.toLong()), FaultService.stripCodePrefix(r[cText], code))
            }
        }

        val symptomTable = symptomTables.firstOrNull { name -> prg.tables.any { it.name.equals(name, ignoreCase = true) } }
        symptomTable?.let { prg.table(it) }?.let { t ->
            val cKey = t.column("ARTNR") ?: 0
            val cText = t.column("ARTTEXT") ?: 1
            for (r in t.rows) add(TranslationType.Symptom, r[cKey], r[cText])
        }

        val skip = setOf("MESSWERTETAB", "FORTTEXTE", symptomTable?.uppercase(Locale.ROOT))
        for (t in prg.tables.map { it.name }.distinctBy { it.uppercase(Locale.ROOT) }) {
            val upper = t.uppercase(Locale.ROOT)
            if (upper in skip || upper.startsWith("MESSWERTETAB")) continue
            if (prg.tables.first { it.name == t }.rowCount > MaxTableRows) continue
            val data = prg.table(t) ?: continue
            if (data.header.size < 2) continue
            val cText = textColumns.firstNotNullOfOrNull { data.column(it) }?.takeIf { it != 0 } ?: continue
            for (r in data.rows) {
                val text = r[cText].trim()
                if (text.isEmpty() || text == "--" || text == "-" || identifier.matches(text) || text.none { it.isLetter() }) continue
                add(TranslationType.Table, "$t/${r[0].trim()}", text)
            }
        }
        return out
    }

    /**
     * [template] with the translations and statuses of [existing] (same type and key). Entries of
     * [existing] that the template does not know (another version of the SGBD) are kept at the end.
     * The `de` column always comes from the template, i.e. from the user's file.
     */
    fun merge(template: List<TranslationEntry>, existing: List<TranslationEntry>): List<TranslationEntry> {
        val old = existing.associateBy { it.type to it.key }
        val used = HashSet<Pair<TranslationType, String>>()
        val merged = template.map { e ->
            val o = old[e.type to e.key] ?: return@map e
            used += e.type to e.key
            e.copy(text = o.text, status = o.status)
        }
        return merged + existing.filter { (it.type to it.key) !in used && it.translated }
    }

    /** Coverage of [translations] against the user's [template]: per type, translated / total and stale. */
    fun coverage(template: List<TranslationEntry>, translations: EcuTranslations?): Coverage {
        val total = HashMap<TranslationType, Int>()
        val done = HashMap<TranslationType, Int>()
        var stale = 0
        for (e in template) {
            total.merge(e.type, 1, Int::plus)
            val l = translations?.lookup(e.type, e.key, e.de) ?: continue
            if (l.stale) stale++ else done.merge(e.type, 1, Int::plus)
        }
        return Coverage(total, done, stale)
    }

    data class Coverage(val total: Map<TranslationType, Int>, val translated: Map<TranslationType, Int>, val stale: Int) {
        fun total(t: TranslationType) = total[t] ?: 0
        fun translated(t: TranslationType) = translated[t] ?: 0
        val allTotal: Int get() = total.values.sum()
        val allTranslated: Int get() = translated.values.sum()
    }
}
