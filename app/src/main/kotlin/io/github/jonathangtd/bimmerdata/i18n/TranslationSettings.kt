/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.i18n

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import io.github.jonathangtd.bimmerdata.live.i18n.Translator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Where the translation files come from. */
enum class TranslationSource { Url, Folder }

/** Which ECUs get translated. */
enum class EcuSelection {
    /** The ECUs found in the car and every ECU the app shows a text of (files fetched on demand). */
    Auto,

    /** Only the ECUs in [TranslationConfig.chosen]. */
    Chosen,
}

data class TranslationConfig(
    /** Language code ("fr", "en"...); empty = the phone's language; "de" = original texts. */
    val language: String = "",
    val source: TranslationSource = TranslationSource.Url,
    /** Base URL: files are read from `<baseUrl>/<language>/<sgbd>.csv`. */
    val baseUrl: String = DEFAULT_URL,
    /** Optional token, sent as `Authorization: Bearer <token>` (private repository, own server). */
    val token: String = "",
    /** Folder picked with the Android folder picker (content Uri), for [TranslationSource.Folder]. */
    val folderUri: String? = null,
    val selection: EcuSelection = EcuSelection.Auto,
    /** SGBDs (lower case) for [EcuSelection.Chosen]. */
    val chosen: Set<String> = emptySet(),
) {
    /** Language actually used: [language], or the phone's one. */
    val effectiveLanguage: String
        get() = language.ifBlank { Locale.getDefault().language.lowercase(Locale.ROOT).ifBlank { "en" } }

    val enabled: Boolean get() = effectiveLanguage != Translator.Original

    companion object {
        const val DEFAULT_URL = "https://raw.githubusercontent.com/jonathan-gtd/BimmerData/main/translations"

        /** Languages offered in the settings (any code works through the API of the files). */
        val LANGUAGES = listOf("", "de", "en", "fr", "es", "it", "nl", "pl", "pt", "cs", "sv", "ru", "tr")
    }
}

/** Translation settings, in their own private preferences (not backed up: the token stays on the phone). */
class TranslationSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("translations", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<TranslationConfig> = _state.asStateFlow()

    private fun load() = TranslationConfig(
        language = prefs.getString(K_LANG, "").orEmpty(),
        source = runCatching { TranslationSource.valueOf(prefs.getString(K_SOURCE, null) ?: "") }.getOrDefault(TranslationSource.Url),
        baseUrl = prefs.getString(K_URL, null)?.takeIf { it.isNotBlank() } ?: TranslationConfig.DEFAULT_URL,
        token = prefs.getString(K_TOKEN, "").orEmpty(),
        folderUri = prefs.getString(K_FOLDER, null),
        selection = runCatching { EcuSelection.valueOf(prefs.getString(K_SELECTION, null) ?: "") }.getOrDefault(EcuSelection.Auto),
        chosen = prefs.getStringSet(K_CHOSEN, null)?.toSet() ?: emptySet(),
    )

    @Synchronized
    fun update(transform: (TranslationConfig) -> TranslationConfig) {
        _state.update(transform)
        val s = _state.value
        prefs.edit {
            putString(K_LANG, s.language)
            putString(K_SOURCE, s.source.name)
            putString(K_URL, s.baseUrl)
            putString(K_TOKEN, s.token)
            putString(K_FOLDER, s.folderUri)
            putString(K_SELECTION, s.selection.name)
            putStringSet(K_CHOSEN, s.chosen)
        }
    }

    companion object {
        private const val K_LANG = "language"
        private const val K_SOURCE = "source"
        private const val K_URL = "base_url"
        private const val K_TOKEN = "token"
        private const val K_FOLDER = "folder"
        private const val K_SELECTION = "selection"
        private const val K_CHOSEN = "chosen"

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: TranslationSettings? = null

        fun get(context: Context): TranslationSettings =
            instance ?: synchronized(this) { instance ?: TranslationSettings(context).also { instance = it } }
    }
}
