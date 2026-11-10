/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.i18n

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import android.provider.DocumentsContract
import io.github.jonathangtd.bimmerdata.files.EcuImporter
import io.github.jonathangtd.bimmerdata.live.EdiabasMeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.i18n.EcuTranslations
import io.github.jonathangtd.bimmerdata.live.i18n.TranslationCsv
import io.github.jonathangtd.bimmerdata.live.i18n.TranslationRefresh
import io.github.jonathangtd.bimmerdata.live.i18n.TranslationTemplate
import io.github.jonathangtd.bimmerdata.live.i18n.Translations
import io.github.jonathangtd.bimmerdata.live.i18n.Translator
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Translation files on the phone: cache, download and the process-wide [Translator].
 *
 * - Cache: `files/translations/<lang>/<sgbd>.csv`, loaded into [Translations.shared] at start
 *   (a few files, small), so the app works offline.
 * - On demand: when a screen shows a text of an ECU without a cached file, the file is fetched
 *   once per app run (if that ECU is selected, see [EcuSelection]); "not found" is remembered for
 *   the run. [updateAll] fetches every selected / cached ECU again.
 * - Source: `<baseUrl>/<lang>/<sgbd>.csv` over HTTP(S) with an optional bearer token, or a folder
 *   picked with the Android folder picker (`<lang>/<sgbd>.csv` inside it, or `<sgbd>.csv` directly).
 */
class TranslationRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = TranslationSettings.get(app)
    private val translator: Translator = Translations.shared
    private val root = File(app.filesDir, "translations")
    private val ecuDir = EcuImporter(app).ecuDir
    private val fetchLock = Mutex()

    data class State(
        val updating: Boolean = false,
        /** Result of the last "Update" ([updated] files, [notFound] ECUs without a file, [errors]). */
        val lastUpdate: UpdateResult? = null,
        /** ECUs for which the source has no file (this app run). */
        val notFound: Set<String> = emptySet(),
        val lastError: String? = null,
    )

    data class UpdateResult(val updated: List<String>, val notFound: List<String>, val errors: List<String>, val atMs: Long)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val refreshPrefs = app.getSharedPreferences("translations_refresh", Context.MODE_PRIVATE)
    private val coverageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, TranslationTemplate.Coverage>>()

    // Every property is declared above: init starts work on other threads, which must never see
    // a property that is not set yet (crash at start, "Lazy.getValue() on a null object").
    init {
        loadCache(settings.state.value)
        translator.onMissing = { sgbd -> scope.launch { fetchOnDemand(sgbd) } }
        scope.launch { autoRefresh() }
        // language or source changed: other cache folder / other files
        scope.launch {
            settings.state.map { it.effectiveLanguage to it.sourceKey() }.distinctUntilChanged().drop(1).collect {
                _state.update { s -> s.copy(notFound = emptySet()) }
                loadCache(settings.state.value)
            }
        }
    }

    private fun TranslationConfig.sourceKey(): String = if (source == TranslationSource.Url) "$baseUrl|$token" else folderUri.orEmpty()

    private fun langDir(lang: String) = File(root, lang.lowercase(Locale.ROOT))

    private fun cacheFile(lang: String, sgbd: String) = File(langDir(lang), EdiabasMeasurementCatalog.normalize(sgbd) + ".csv")

    /** Folder of the cached files, `translations/<language>/<sgbd>.csv` (Settings › Backup). */
    val cacheRoot: File get() = root

    /** Reads the cached files again (after a restore replaced them). */
    fun reloadCache() {
        _state.update { s -> s.copy(notFound = emptySet()) }
        loadCache(settings.state.value)
    }

    /** Cached SGBDs of the current language. */
    fun cachedSgbds(): List<String> =
        langDir(settings.state.value.effectiveLanguage).listFiles()?.filter { it.name.endsWith(".csv") }?.map { it.name.removeSuffix(".csv") }?.sorted().orEmpty()

    private fun loadCache(config: TranslationConfig) {
        val lang = config.effectiveLanguage
        if (!config.enabled) {
            translator.reset(lang, emptyList())
            return
        }
        val files = langDir(lang).listFiles()?.filter { it.isFile && it.name.endsWith(".csv") }.orEmpty().mapNotNull { f ->
            runCatching { EcuTranslations(f.name.removeSuffix(".csv"), lang, TranslationCsv.parse(f.readText(Charsets.UTF_8)).entries) }.getOrNull()
        }
        translator.reset(lang, files)
    }

    private fun selected(config: TranslationConfig, sgbd: String): Boolean =
        config.selection == EcuSelection.Auto || EdiabasMeasurementCatalog.normalize(sgbd) in config.chosen

    /** SGBDs "Update" fetches: the chosen ones, or (auto) the ECUs of the car plus the cached ones. */
    fun updateTargets(): List<String> {
        val c = settings.state.value
        return when (c.selection) {
            EcuSelection.Chosen -> c.chosen.sorted()
            EcuSelection.Auto -> (detectedSgbds() + cachedSgbds()).map(EdiabasMeasurementCatalog::normalize).distinct().sorted()
        }
    }

    fun detectedSgbds(): List<String> = runCatching { VehicleRepository.get(app).sgbds() }.getOrDefault(emptyList()).map(EdiabasMeasurementCatalog::normalize).distinct()

    private suspend fun fetchOnDemand(sgbd: String) {
        val c = settings.state.value
        if (!c.enabled || !selected(c, sgbd) || cacheFile(c.effectiveLanguage, sgbd).isFile) return
        if (sgbd in _state.value.notFound) return
        fetch(c, sgbd)
    }


    /**
     * At start, at most once per day ([TranslationRefresh.IntervalMs]): asks every cached file
     * again with its HTTP validators, so an unchanged file costs a 304 and no download. Without
     * network the check fails quietly and runs again at the next start.
     */
    private suspend fun autoRefresh() {
        val c = settings.state.value
        val now = System.currentTimeMillis()
        if (!c.enabled || !TranslationRefresh.isDue(refreshPrefs.getLong(K_LAST_CHECK, 0), now)) return
        val targets = cachedSgbds()
        if (targets.isEmpty()) return
        var failed = false
        for (s in targets) if (fetch(c, s, conditional = true) is FetchResult.Failed) failed = true
        if (!failed) refreshPrefs.edit { putLong(K_LAST_CHECK, now) }
        translator.retryMissing()
    }

    /** Downloads every target again ([updateTargets]). */
    fun updateAll() {
        if (_state.value.updating) return
        scope.launch {
            _state.update { it.copy(updating = true, notFound = emptySet()) }
            val c = settings.state.value
            val updated = ArrayList<String>()
            val missing = ArrayList<String>()
            val errors = ArrayList<String>()
            if (c.enabled) {
                for (s in updateTargets()) {
                    when (val r = fetch(c, s)) {
                        FetchResult.Ok, FetchResult.Unchanged -> updated += s
                        FetchResult.NotFound -> missing += s
                        is FetchResult.Failed -> errors += "${s.uppercase(Locale.ROOT)}: ${r.message}"
                    }
                }
                translator.retryMissing()
            }
            _state.update { it.copy(updating = false, lastUpdate = UpdateResult(updated, missing, errors, System.currentTimeMillis())) }
        }
    }

    private sealed interface FetchResult {
        data object Ok : FetchResult
        data object NotFound : FetchResult
        /** Conditional request answered 304: the cached file is current. */
        data object Unchanged : FetchResult
        data class Failed(val message: String) : FetchResult
    }

    private fun validatorsFile(lang: String, sgbd: String) = File(cacheFile(lang, sgbd).path + ".etag")

    /**
     * Downloads (or reads from the folder) the file of [sgbd]. With [conditional], a cached file
     * is asked with its validators and a 304 leaves it untouched.
     */
    private suspend fun fetch(c: TranslationConfig, sgbd: String, conditional: Boolean = false): FetchResult = fetchLock.withLock {
        val lang = c.effectiveLanguage
        val name = EdiabasMeasurementCatalog.normalize(sgbd)
        var validators = TranslationRefresh.Validators()
        val text = try {
            when (c.source) {
                TranslationSource.Url -> {
                    val known = if (conditional && cacheFile(lang, name).isFile) {
                        TranslationRefresh.Validators.decode(runCatching { validatorsFile(lang, name).readText() }.getOrNull())
                    } else {
                        TranslationRefresh.Validators()
                    }
                    when (val d = download(fileUrl(c.baseUrl, lang, name), c.token, known)) {
                        Download.NotModified -> return@withLock FetchResult.Unchanged
                        Download.NotFound -> null
                        is Download.Body -> d.text.also { validators = d.validators }
                    }
                }
                TranslationSource.Folder -> readFolder(c.folderUri ?: throw IOException("No folder chosen"), lang, name)
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (ex: Exception) {
            val msg = ex.message ?: ex.javaClass.simpleName
            _state.update { it.copy(lastError = msg) }
            return@withLock FetchResult.Failed(msg)
        }
        if (text == null) {
            _state.update { it.copy(notFound = it.notFound + name) }
            return@withLock FetchResult.NotFound
        }
        val parsed = TranslationCsv.parse(text)
        if (parsed.entries.isEmpty() && parsed.errors.isNotEmpty()) return@withLock FetchResult.Failed(parsed.errors.first())
        val file = cacheFile(lang, name)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        val vf = validatorsFile(lang, name)
        if (validators.isEmpty) vf.delete() else runCatching { vf.writeText(validators.encode()) }
        // still the same language? (the user may have switched during the download)
        if (settings.state.value.effectiveLanguage == lang) translator.put(EcuTranslations(name, lang, parsed.entries))
        _state.update { it.copy(notFound = it.notFound - name, lastError = null) }
        FetchResult.Ok
    }

    /** Removes the cached file of [sgbd] (current language). */
    fun forget(sgbd: String) {
        val lang = settings.state.value.effectiveLanguage
        cacheFile(lang, sgbd).delete()
        validatorsFile(lang, sgbd).delete()
        translator.remove(sgbd)
    }

    private sealed interface Download {
        data object NotFound : Download
        data object NotModified : Download
        class Body(val text: String, val validators: TranslationRefresh.Validators) : Download
    }

    private fun download(url: String, token: String, known: TranslationRefresh.Validators): Download {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("Accept", "text/csv, text/plain, */*")
            conn.setRequestProperty("User-Agent", "BimmerData")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            known.conditionalHeaders().forEach { (k, v) -> conn.setRequestProperty(k, v) }
            return when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Download.Body(
                    conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) },
                    TranslationRefresh.Validators(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified")),
                )
                HttpURLConnection.HTTP_NOT_MODIFIED -> Download.NotModified
                HttpURLConnection.HTTP_NOT_FOUND -> Download.NotFound
                else -> throw IOException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** `<folder>/<lang>/<sgbd>.csv`, else `<folder>/<sgbd>.csv`; null if neither exists. */
    private fun readFolder(folder: String, lang: String, sgbd: String): String? {
        val tree = folder.toUri()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val children = list(tree, rootId)
        val langDir = children.firstOrNull { it.dir && it.name.equals(lang, ignoreCase = true) }
        val file = langDir?.let { d -> list(tree, d.id).firstOrNull { !it.dir && it.name.equals("$sgbd.csv", ignoreCase = true) } }
            ?: children.firstOrNull { !it.dir && it.name.equals("$sgbd.csv", ignoreCase = true) }
            ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.id)
        return app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw IOException("Cannot read ${file.name}")
    }

    private class Doc(val id: String, val name: String, val dir: Boolean)

    private fun list(tree: Uri, parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val out = ArrayList<Doc>()
        app.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) out += Doc(c.getString(0), c.getString(1) ?: continue, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
        } ?: throw IOException("Cannot list the translation folder")
        return out
    }

    // ---------------------------------------------------------------- templates and coverage

    /** The user's .prg of [sgbd], null if it is not imported. */
    fun sgbdFile(sgbd: String): File? {
        val name = EdiabasMeasurementCatalog.normalize(sgbd) + ".prg"
        return File(ecuDir, name).takeIf { it.isFile } ?: ecuDir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Template CSV of [sgbd] from the user's own file, with the translations of the current
     * language already known (cache) kept in it.
     */
    suspend fun exportTemplate(sgbd: String, target: Uri) = withContext(Dispatchers.IO) {
        val file = sgbdFile(sgbd) ?: throw IOException("${sgbd.uppercase(Locale.ROOT)}.PRG is not imported")
        var entries = TranslationTemplate.generate(sgbd, file)
        val cached = cacheFile(settings.state.value.effectiveLanguage, sgbd)
        if (cached.isFile) entries = TranslationTemplate.merge(entries, TranslationCsv.parse(cached.readText(Charsets.UTF_8)).entries)
        (app.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write the file")).use {
            it.write(TranslationCsv.write(entries).toByteArray(Charsets.UTF_8))
        }
        entries.size
    }

    /** Coverage of the loaded file of [sgbd] against the user's own SGBD; null without the SGBD. */
    suspend fun coverage(sgbd: String): TranslationTemplate.Coverage? = withContext(Dispatchers.IO) {
        val file = sgbdFile(sgbd) ?: return@withContext null
        val key = "${file.path}|${file.lastModified()}|${translator.version.value}"
        coverageCache[sgbd]?.let { (k, c) -> if (k == key) return@withContext c }
        val template = runCatching { TranslationTemplate.generate(sgbd, file) }.getOrNull() ?: return@withContext null
        TranslationTemplate.coverage(template, translator.table(sgbd, fetch = false)).also { coverageCache[sgbd] = key to it }
    }


    companion object {
        private const val K_LAST_CHECK = "last_check_ms"

        /** `<base>/<lang>/<sgbd>.csv`, the base URL with or without a trailing slash. */
        fun fileUrl(baseUrl: String, lang: String, sgbd: String): String =
            baseUrl.trim().trimEnd('/') + "/" + lang.lowercase(Locale.ROOT) + "/" + EdiabasMeasurementCatalog.normalize(sgbd) + ".csv"

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: TranslationRepository? = null

        /** Also loads the cached files into [Translations.shared]: call it before showing SGBD texts. */
        fun get(context: Context): TranslationRepository =
            instance ?: synchronized(this) { instance ?: TranslationRepository(context).also { instance = it } }
    }
}
