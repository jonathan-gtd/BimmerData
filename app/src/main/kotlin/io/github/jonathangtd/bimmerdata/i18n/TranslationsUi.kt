/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.i18n

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.core.net.toUri
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.net.LocalNetwork
import io.github.jonathangtd.bimmerdata.settings.LocalNetworkBanner
import io.github.jonathangtd.bimmerdata.settings.rememberLocalNetworkAccess
import io.github.jonathangtd.bimmerdata.live.i18n.TranslationTemplate
import io.github.jonathangtd.bimmerdata.live.i18n.TranslationType
import io.github.jonathangtd.bimmerdata.live.i18n.Translations
import io.github.jonathangtd.bimmerdata.live.i18n.Translator
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.launch
import java.util.Locale

/** Settings › Translations: language, source, ECUs, "Update" and what is loaded. */
@Composable
fun TranslationsSettingsSection() {
    val context = LocalContext.current
    val store = remember { TranslationSettings.get(context) }
    val repo = remember { TranslationRepository.get(context) }
    val config by store.state.collectAsStateWithLifecycle()
    val repoState by repo.state.collectAsStateWithLifecycle()
    val version by Translations.shared.version.collectAsState()

    Card {
        Label(stringResource(R.string.tr_section))
        LanguagePicker(config) { lang -> store.update { it.copy(language = lang) } }
        Hint(stringResource(if (config.enabled) R.string.tr_language_hint else R.string.tr_disabled))
        if (!config.enabled) return@Card

        Line()
        Text(stringResource(R.string.tr_source), color = Cockpit.Text, fontSize = 16.sp)
        Choice(
            listOf(TranslationSource.Url to stringResource(R.string.tr_source_url), TranslationSource.Folder to stringResource(R.string.tr_source_folder)),
            config.source,
        ) { s -> store.update { it.copy(source = s) } }
        if (config.source == TranslationSource.Url) UrlFields(config, store) else FolderField(config, store)

        Line()
        Text(stringResource(R.string.tr_ecus), color = Cockpit.Text, fontSize = 16.sp)
        Choice(
            listOf(EcuSelection.Auto to stringResource(R.string.tr_ecus_auto), EcuSelection.Chosen to stringResource(R.string.tr_ecus_chosen)),
            config.selection,
        ) { s ->
            store.update { c -> c.copy(selection = s, chosen = if (s == EcuSelection.Chosen && c.chosen.isEmpty()) repo.updateTargets().toSet() else c.chosen) }
        }
        if (config.selection == EcuSelection.Auto) Hint(stringResource(R.string.tr_ecus_auto_hint)) else ChosenEcus(config, store, repo)

        Line()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(if (repoState.updating) R.string.tr_updating else R.string.tr_update), enabled = !repoState.updating) { repo.updateAll() }
        }
        repoState.lastUpdate?.let { r ->
            if (r.updated.isEmpty() && r.notFound.isEmpty() && r.errors.isEmpty()) {
                Hint(stringResource(R.string.tr_update_none))
            } else {
                Hint(pluralStringResource(R.plurals.tr_update_result, r.updated.size, r.updated.size, r.notFound.size))
                if (r.errors.isNotEmpty()) Text(stringResource(R.string.tr_update_errors, r.errors.joinToString("; ")), color = Cockpit.Warning, fontSize = 13.sp)
            }
        }

        Line()
        Text(stringResource(R.string.tr_stats_title), color = Cockpit.Text, fontSize = 16.sp)
        val loaded = remember(version) { Translations.shared.loaded() }
        if (loaded.isEmpty()) Hint(stringResource(R.string.tr_stats_none))
        loaded.forEach { t -> CoverageLine(repo, t.sgbd, t.entries.size, version) }
    }
}

@Composable
private fun CoverageLine(repo: TranslationRepository, sgbd: String, entries: Int, version: Int) {
    var coverage by remember(sgbd, version) { mutableStateOf<TranslationTemplate.Coverage?>(null) }
    var noSgbd by remember(sgbd, version) { mutableStateOf(false) }
    LaunchedEffect(sgbd, version) {
        coverage = repo.coverage(sgbd)
        noSgbd = coverage == null
    }
    val name = sgbd.uppercase(Locale.ROOT)
    val c = coverage
    when {
        c != null -> {
            Text(
                stringResource(
                    R.string.tr_stats_line, name,
                    c.translated(TranslationType.Measurement), c.total(TranslationType.Measurement),
                    c.translated(TranslationType.Fault), c.total(TranslationType.Fault),
                    c.allTranslated, c.allTotal,
                ),
                color = Cockpit.Text, fontSize = 14.sp,
            )
            if (c.stale > 0) Text(stringResource(R.string.tr_stats_stale, c.stale), color = Cockpit.Warning, fontSize = 13.sp)
        }
        noSgbd -> Text(stringResource(R.string.tr_stats_no_sgbd, name, entries), color = Cockpit.Muted, fontSize = 14.sp)
        else -> Text(name, color = Cockpit.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun LanguagePicker(config: TranslationConfig, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val phone = LocalConfiguration.current.locales[0]
    @Composable
    fun name(code: String): String = when (code) {
        "" -> stringResource(R.string.tr_language_phone, phone.getDisplayLanguage(phone).replaceFirstChar { it.titlecase(phone) })
        Translator.Original -> stringResource(R.string.tr_language_original)
        else -> Locale.forLanguageTag(code).let { l -> l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) } } + " ($code)"
    }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.DropdownList) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tr_language), color = Cockpit.Muted, fontSize = 13.sp)
                Text(name(config.language), color = Cockpit.Text, fontSize = 16.sp)
            }
            Text("▾", color = Cockpit.Accent, fontSize = 18.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Cockpit.Card) {
            TranslationConfig.LANGUAGES.forEach { code ->
                DropdownMenuItem(
                    text = { Text(name(code), color = if (code == config.language) Cockpit.Accent else Cockpit.Text) },
                    onClick = { open = false; onPick(code) },
                )
            }
        }
    }
}

@Composable
private fun UrlFields(config: TranslationConfig, store: TranslationSettings) {
    var url by rememberSaveable(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var token by rememberSaveable(config.token) { mutableStateOf(config.token) }
    Field(url, { url = it }, stringResource(R.string.tr_url))
    Hint(stringResource(R.string.tr_url_hint))
    val lan = rememberLocalNetworkAccess()
    if (LocalNetwork.isLocalUrl(config.baseUrl)) LocalNetworkBanner(lan, stringResource(R.string.lan_translations_denied))
    Field(token, { token = it }, stringResource(R.string.tr_token), secret = true)
    Hint(stringResource(R.string.tr_token_hint))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(stringResource(R.string.tr_apply), enabled = url.isNotBlank() && (url.trim() != config.baseUrl || token.trim() != config.token)) {
            if (LocalNetwork.isLocalUrl(url) && !lan.granted) lan.request()
            store.update { it.copy(baseUrl = url.trim(), token = token.trim()) }
        }
        Button(stringResource(R.string.tr_url_default), enabled = config.baseUrl != TranslationConfig.DEFAULT_URL) {
            store.update { it.copy(baseUrl = TranslationConfig.DEFAULT_URL) }
        }
    }
}

@Composable
private fun FolderField(config: TranslationConfig, store: TranslationSettings) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            store.update { it.copy(folderUri = uri.toString()) }
        }
    }
    Text(
        config.folderUri?.let { it.toUri().lastPathSegment } ?: stringResource(R.string.tr_folder_none),
        color = Cockpit.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
    )
    Hint(stringResource(R.string.tr_folder_hint))
    Button(stringResource(R.string.tr_folder_pick)) { pick.launch(null) }
}

@Composable
private fun ChosenEcus(config: TranslationConfig, store: TranslationSettings, repo: TranslationRepository) {
    var text by rememberSaveable(config.chosen) { mutableStateOf(config.chosen.sorted().joinToString(", ")) }
    fun parse(s: String) = s.split(',', ';', ' ').map { it.trim().lowercase(Locale.ROOT).removeSuffix(".prg") }.filter { it.isNotEmpty() }.toSet()
    Field(text, { text = it }, stringResource(R.string.tr_ecus_field))
    Hint(stringResource(R.string.tr_ecus_chosen_hint))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(stringResource(R.string.tr_apply), enabled = parse(text) != config.chosen) { store.update { it.copy(chosen = parse(text)) } }
        Button(stringResource(R.string.tr_ecus_use_detected)) { store.update { it.copy(chosen = it.chosen + repo.detectedSgbds()) } }
    }
}

/** Faults › ECU: "Export for translation" (CSV template of the ECU from the user's own file). */
@Composable
fun TranslationExportButton(sgbd: String) {
    val export = rememberTranslationExport(sgbd)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(stringResource(R.string.tr_export)) { export.launch() }
        Hint(stringResource(R.string.tr_export_hint))
        export.message?.let { (m, err) -> Text(m, color = if (err) Cockpit.Warning else Cockpit.Accent, fontSize = 13.sp) }
    }
}

/** "Export for translation" of [sgbd]: [launch] asks where to save; [message] says how it went. */
class TranslationExport(val launch: () -> Unit, val message: Pair<String, Boolean>?)

@Composable
fun rememberTranslationExport(sgbd: String): TranslationExport {
    val context = LocalContext.current
    val repo = remember { TranslationRepository.get(context) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val res = LocalResources.current
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                message = runCatching { repo.exportTemplate(sgbd, uri) }.fold(
                    { n -> res.getQuantityString(R.plurals.tr_export_done, n, n) to false },
                    { e -> res.getString(R.string.tr_export_failed, e.message ?: e.javaClass.simpleName) to true },
                )
            }
        }
    }
    return TranslationExport({ create.launch(sgbd.lowercase(Locale.ROOT) + ".csv") }, message)
}

// ---------------------------------------------------------------- building blocks (same look as the Settings screen)

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = Cockpit.Card, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Cockpit.Border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun Label(text: String) =
    Text(text.uppercase(Locale.ROOT), color = Cockpit.Muted, fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Hint(text: String) = Text(text, color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun Line() = HorizontalDivider(color = Cockpit.Border, modifier = Modifier.padding(vertical = 2.dp))

@Composable
private fun Button(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (enabled) Cockpit.Text.copy(alpha = 0.6f) else Cockpit.Border),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) { Text(text, color = if (enabled) Cockpit.Text else Cockpit.Muted) }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cockpit.Accent, unfocusedBorderColor = Cockpit.Border,
            focusedLabelColor = Cockpit.Accent, unfocusedLabelColor = Cockpit.Muted,
            focusedTextColor = Cockpit.Text, unfocusedTextColor = Cockpit.Text, cursorColor = Cockpit.Accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Cockpit.Background, RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((value, label) in options) {
            val sel = value == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .background(if (sel) Cockpit.Accent.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) Cockpit.Accent else Cockpit.Muted, fontSize = 15.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}
