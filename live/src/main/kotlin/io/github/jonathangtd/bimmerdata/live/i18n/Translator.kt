/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

import io.github.jonathangtd.bimmerdata.live.EdiabasMeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.SgbdInfo
import io.github.jonathangtd.bimmerdata.live.vehicle.Fault
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultDetail
import io.github.jonathangtd.bimmerdata.live.vehicle.NamedValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Display-time translation of SGBD texts. Thread-safe, lookups are plain hash map reads.
 *
 * Everything the app shows from an SGBD goes through here (card labels, picker, ECU tree, faults,
 * Tool32, Android Auto): the caches of the catalog and the vehicle keep the original German
 * texts, so changing the language or updating the files never needs a rescan.
 *
 * Without translations for an SGBD, or for language "de", every call returns the original text.
 */
class Translator {
    @Volatile var language: String = ""
        private set

    private val tables = ConcurrentHashMap<String, EcuTranslations>()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    /** Called once per SGBD (per language) when a text of an SGBD without translations is asked for. */
    @Volatile var onMissing: ((sgbd: String) -> Unit)? = null

    private val _version = MutableStateFlow(0)

    /** Changes whenever the language or a file changes: screens holding translated texts refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    val enabled: Boolean get() = language.isNotEmpty() && language != Original

    /** Replaces everything: new language and its loaded files. */
    fun reset(language: String, files: Collection<EcuTranslations>) {
        this.language = language
        tables.clear()
        asked.clear()
        files.filter { it.language == language }.forEach { tables[EdiabasMeasurementCatalog.normalize(it.sgbd)] = it }
        _version.update { it + 1 }
    }

    /** Adds or replaces the file of one SGBD (ignored if it is in another language). */
    fun put(file: EcuTranslations) {
        if (file.language != language) return
        tables[EdiabasMeasurementCatalog.normalize(file.sgbd)] = file
        _version.update { it + 1 }
    }

    fun remove(sgbd: String) {
        if (tables.remove(EdiabasMeasurementCatalog.normalize(sgbd)) != null) _version.update { it + 1 }
    }

    fun loaded(): List<EcuTranslations> = tables.values.sortedBy { it.sgbd }

    /**
     * Translations of [sgbd], null if none are loaded. With [fetch], a missing file is reported to
     * [onMissing] (download on demand); lists of every SGBD (the picker's ECU list) do not fetch.
     */
    fun table(sgbd: String?, fetch: Boolean = true): EcuTranslations? {
        if (!enabled || sgbd.isNullOrBlank()) return null
        val key = EdiabasMeasurementCatalog.normalize(sgbd)
        val t = tables[key]
        if (t == null && fetch && asked.add(key)) onMissing?.invoke(key)
        return t
    }

    /** Forget which SGBDs were already reported missing (e.g. after a failed download, to retry). */
    fun retryMissing() = asked.clear()

    /** [original] translated, or [original] itself (no file, no entry, or `de` guard not matching). */
    fun text(type: TranslationType, sgbd: String?, key: String, original: String): String {
        val l = table(sgbd)?.lookup(type, key, original) ?: return original
        return if (l.stale) original else l.text
    }

    /** Translation of a German text without a key (see [EcuTranslations.byOriginal]); else [original]. */
    fun byOriginal(sgbd: String?, original: String): String = table(sgbd)?.byOriginal(original) ?: original

    fun byOriginalOrNull(sgbd: String?, original: String?): String? = original?.let { byOriginal(sgbd, it) }

    // ---------------------------------------------------------------- app objects

    fun measurement(m: Measurement): Measurement {
        val t = table(m.sgbd) ?: return m
        return when (val s = m.source) {
            is MeasurementSource.MeasurementTable -> {
                val label = t.lookup(TranslationType.Measurement, TranslationKeys.hex(s.id.toLong()), m.label)?.takeIf { !it.stale }?.text
                    ?: t.lookup(TranslationType.Result, TranslationKeys.anyJobResult(s.resultName), m.label)?.takeIf { !it.stale }?.text
                if (label == null) m else m.copy(label = label)
            }
            is MeasurementSource.JobResult -> {
                val label = t.lookup(TranslationType.Result, TranslationKeys.result(s.job, s.resultName), m.label)?.takeIf { !it.stale }?.text
                val description = if (m.description.isBlank()) null else t.lookup(TranslationType.Job, s.job, m.description)?.takeIf { !it.stale }?.text
                if (label == null && description == null) m else m.copy(label = label ?: m.label, description = description ?: m.description)
            }
            is MeasurementSource.Sensor -> m
        }
    }

    fun sgbdInfo(info: SgbdInfo): SgbdInfo {
        if (info.description.isBlank()) return info
        val l = table(info.name, fetch = false)?.lookup(TranslationType.Ecu, "", info.description) ?: return info
        return if (l.stale) info else info.copy(description = l.text)
    }

    /** Fault location by code ([TranslationType.Fault]); symptom and status texts by German text. */
    fun fault(sgbd: String?, f: Fault): Fault {
        val t = table(sgbd) ?: return f
        val text = t.lookup(TranslationType.Fault, TranslationKeys.hex(f.code.toLong()), f.text)?.takeIf { !it.stale }?.text ?: f.text
        return f.copy(
            text = text,
            symptom = f.symptom?.let { t.byOriginal(it) ?: it },
            presence = f.presence?.let { t.byOriginal(it) ?: it },
            warning = f.warning?.let { t.byOriginal(it) ?: it },
            extra = f.extra.map { t.byOriginal(it) ?: it },
        )
    }

    fun faultDetail(sgbd: String?, d: FaultDetail): FaultDetail {
        val t = table(sgbd) ?: return d
        return d.copy(
            fault = fault(sgbd, d.fault),
            pcodeText = d.pcodeText?.let { t.byOriginal(it) ?: it },
            kinds = d.kinds.map { t.byOriginal(it) ?: it },
            occurrences = d.occurrences.map { o -> o.copy(values = o.values.map { v -> namedValue(t, v) }) },
        )
    }

    private fun namedValue(t: EcuTranslations, v: NamedValue): NamedValue =
        v.copy(name = t.byOriginal(v.name) ?: v.name, value = t.byOriginal(v.value) ?: v.value)

    /** Job comment of [job] (Tool32 picker), null if not translated. */
    fun jobTitle(sgbd: String?, job: String, original: String?): String? {
        val l = table(sgbd)?.lookup(TranslationType.Job, job, original) ?: return null
        return if (l.stale) null else l.text
    }

    /** Label of a result of [job] (Tool32 results), null if not translated. */
    fun resultLabel(sgbd: String?, job: String, result: String): String? {
        val l = table(sgbd)?.lookup(TranslationType.Result, TranslationKeys.result(job, result), null) ?: return null
        return if (l.stale) null else l.text
    }

    companion object {
        /** Language code that means "show the texts of the SGBDs as they are". */
        const val Original = "de"
    }
}

/** The process-wide [Translator] used by every screen. */
object Translations {
    val shared = Translator()
}
