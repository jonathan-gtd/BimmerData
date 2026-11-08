/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

/** Result of a lookup: the translated [text], or [stale] when the `de` guard did not match. */
data class Lookup(val text: String, val stale: Boolean, val status: TranslationStatus)

/**
 * The translations of one SGBD in one language, indexed for fast lookups (in memory, a few
 * hundred kB at most per SGBD).
 *
 * The `de` guard: a translation is made for the German text of one version of the SGBD. When the
 * user's file has another text under the same key (other SGBD version, other meaning), the
 * translation is **not shown**: [lookup] returns it as [Lookup.stale], and [Translator] keeps the
 * original German text. Wrong translations are worse than German ones for fault texts. Entries
 * without a `de` text are not guarded.
 */
class EcuTranslations(val sgbd: String, val language: String, entries: List<TranslationEntry>) {
    private val byKey = HashMap<TranslationType, HashMap<String, TranslationEntry>>()
    private val byGerman = HashMap<String, TranslationEntry>()

    /** Translated entries (non-empty translation). */
    val entries: List<TranslationEntry> = entries.filter { it.translated }

    init {
        for (e in this.entries) {
            val map = byKey.getOrPut(e.type) { HashMap() }
            val old = map[e.key]
            if (old == null || (old.status != TranslationStatus.Reviewed && e.status == TranslationStatus.Reviewed)) map[e.key] = e
            if (e.de.isNotBlank()) {
                val g = GermanText.normalize(e.de)
                if (g.isNotEmpty()) {
                    val prev = byGerman[g]
                    if (prev == null || (prev.status != TranslationStatus.Reviewed && e.status == TranslationStatus.Reviewed)) byGerman[g] = e
                }
            }
        }
    }

    fun count(type: TranslationType): Int = byKey[type]?.size ?: 0

    fun entry(type: TranslationType, key: String): TranslationEntry? = byKey[type]?.get(TranslationKeys.normalize(type, key))

    /**
     * Translation of [key] of [type]; [original] is the German text the app would show (checked
     * against the entry's `de`). A result "JOB/RESULT" falls back to the entry of job "*".
     */
    fun lookup(type: TranslationType, key: String, original: String?): Lookup? {
        val k = TranslationKeys.normalize(type, key)
        var e = byKey[type]?.get(k)
        if (e == null && type == TranslationType.Result && !k.startsWith("*/")) {
            e = byKey[type]?.get("*/" + k.substringAfterLast('/'))
        }
        e ?: return null
        val stale = !original.isNullOrBlank() && e.de.isNotBlank() && !GermanText.same(e.de, original)
        return Lookup(e.text, stale, e.status)
    }

    /**
     * Translation of a German text met at run time without a key (fault symptom, presence or
     * environment texts returned by FS_LESEN, state texts of results): any entry whose `de`
     * matches, null if none.
     */
    fun byOriginal(original: String): String? {
        if (original.isBlank()) return null
        return byGerman[GermanText.normalize(original)]?.text
    }
}
