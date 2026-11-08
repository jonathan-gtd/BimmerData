/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.api.ApiTokens
import io.github.jonathangtd.bimmerdata.api.Scope
import io.github.jonathangtd.bimmerdata.api.android.ApiRuntime
import io.github.jonathangtd.bimmerdata.api.android.ApiRuntimeState
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import java.util.Locale

/**
 * Settings: a list of categories ([SettingsHome]), each opening its own page with a back button
 * (and the system back gesture): connection, recording, GPS position, Telegraf export, local
 * REST API, translations, tools (Tool32) and about.
 */
@Composable
fun SettingsScreen(initialPage: SettingsPage? = null) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    BackHandler(enabled = page != null) { page = null }
    val current = page
    if (current == null) {
        SettingsHome(onOpen = { page = it })
    } else {
        SettingsPageScaffold(current, onBack = { page = null }) { SettingsPageContent(current) }
    }
}

/** The content of one settings page (the sections below, in one or more cards). */
@Composable
internal fun SettingsPageContent(page: SettingsPage) {
    val context = LocalContext.current
    val store = remember { AppSettings.get(context) }
    val runtime = remember { ApiRuntime.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val api by runtime.state.collectAsStateWithLifecycle()
    when (page) {
        SettingsPage.Connection -> ConnectionSection(settings, store)
        SettingsPage.Recording -> RecordingSection(settings.recording, store)
        SettingsPage.Gps -> GpsSection(settings.gps, store)
        SettingsPage.Telegraf -> TelegrafSection(settings.telegraf, store)
        SettingsPage.Api -> ApiSection(settings.api, api, store, runtime)
        SettingsPage.Translations -> io.github.jonathangtd.bimmerdata.i18n.TranslationsSettingsSection()
        SettingsPage.Tools -> ToolsSection()
        SettingsPage.About -> AboutSection()
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun ConnectionSection(settings: Settings, store: AppSettings) {
    SettingsCard {
        SectionLabel(stringResource(R.string.settings_section_connection))
        Text(stringResource(R.string.settings_default_mode), color = Cockpit.Text, fontSize = 16.sp)
        Segmented(
            options = listOf(LiveMode.Usb to stringResource(R.string.settings_mode_usb), LiveMode.Simulation to stringResource(R.string.settings_mode_sim)),
            selected = settings.defaultMode,
            onSelect = { m -> store.update { it.copy(defaultMode = m) } },
        )
        Hint(stringResource(R.string.settings_default_mode_hint))
        Divider()
        SwitchRow(
            title = stringResource(R.string.settings_auto_connect),
            hint = stringResource(R.string.settings_auto_connect_hint),
            checked = settings.autoConnectOnCable,
            onChange = { on -> store.update { it.copy(autoConnectOnCable = on) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiSection(config: ApiConfig, state: ApiRuntimeState, store: AppSettings, runtime: ApiRuntime) {
    val context = LocalContext.current
    var showToken by rememberSaveable { mutableStateOf(false) }
    var confirmRegenerate by rememberSaveable { mutableStateOf(false) }
    var portText by rememberSaveable(config.port) { mutableStateOf(config.port.toString()) }
    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && portValue in ApiConfig.PORT_RANGE

    SettingsCard {
        SwitchRow(
            title = stringResource(R.string.settings_api_enable),
            hint = stringResource(R.string.settings_api_enable_hint),
            checked = config.enabled,
            onChange = { on -> store.updateApi { it.copy(enabled = on) } },
        )
        Banner(stringResource(R.string.settings_api_warning), Cockpit.Warning)
        if (config.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (color, text) = when {
                    state.running -> Cockpit.Accent to stringResource(R.string.settings_api_status_running, state.port)
                    state.error != null -> Cockpit.Danger to stringResource(R.string.settings_api_status_error, state.error)
                    else -> Cockpit.Warning to stringResource(R.string.settings_api_status_starting)
                }
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Text(text, color = Cockpit.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = runtime::refresh) { Text(stringResource(R.string.settings_api_refresh), color = Cockpit.Accent) }
            }
        }

        Divider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = portText,
                onValueChange = { v -> portText = v.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.settings_api_port)) },
                singleLine = true,
                isError = !portValid,
                supportingText = if (!portValid) ({ Text(stringResource(R.string.settings_api_port_error)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (portValid) store.updateApi { it.copy(port = portValue) } }),
                colors = fieldColors(),
                modifier = Modifier.width(140.dp),
            )
            SmallButton(stringResource(R.string.settings_api_apply), enabled = portValid && portValue != config.port) {
                store.updateApi { it.copy(port = portValue!!) }
            }
        }

        if (config.enabled) {
            Text(stringResource(R.string.settings_api_addresses), color = Cockpit.Muted, fontSize = 13.sp)
            val port = if (state.running) state.port else config.port
            if (state.addresses.isEmpty()) {
                Hint(stringResource(R.string.settings_api_no_network))
            } else {
                for (a in state.addresses) {
                    val url = "http://${a.host}:$port/api/v1"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Mono(url)
                            Text(a.interfaceName, color = Cockpit.Muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = { copy(context, url, sensitive = false) }) { Text(stringResource(R.string.settings_copy), color = Cockpit.Accent) }
                    }
                }
            }
        }

        Divider()
        Text(stringResource(R.string.settings_api_token), color = Cockpit.Muted, fontSize = 13.sp)
        Mono(if (showToken) config.token else ApiTokens.redact(config.token).replace("…", "••••••••••••"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(stringResource(if (showToken) R.string.settings_api_hide else R.string.settings_api_show)) { showToken = !showToken }
            SmallButton(stringResource(R.string.settings_copy)) { copy(context, config.token, sensitive = true) }
            SmallButton(stringResource(R.string.settings_share)) { share(context, config.token) }
            SmallButton(stringResource(R.string.settings_api_regenerate), color = Cockpit.Warning) { confirmRegenerate = true }
        }

        Divider()
        Text(stringResource(R.string.settings_api_scopes), color = Cockpit.Muted, fontSize = 13.sp)
        for ((scope, title, hint) in listOf(
            Triple(Scope.Read, R.string.settings_scope_read, R.string.settings_scope_read_hint),
            Triple(Scope.Layout, R.string.settings_scope_layout, R.string.settings_scope_layout_hint),
            Triple(Scope.Config, R.string.settings_scope_config, R.string.settings_scope_config_hint),
        )) {
            SwitchRow(stringResource(title), stringResource(hint), scope in config.scopes, onChange = { on ->
                store.updateApi { it.copy(scopes = if (on) it.scopes + scope else it.scopes - scope) }
            })
        }
        SwitchRow(stringResource(R.string.settings_scope_diag), stringResource(R.string.settings_scope_diag_hint), checked = false, enabled = false, onChange = {})

        Divider()
        Text(stringResource(R.string.settings_api_instruction), color = Cockpit.Text, fontSize = 16.sp)
        Hint(stringResource(R.string.settings_api_instruction_hint))
        val host = state.addresses.firstOrNull()?.host ?: "127.0.0.1"
        val base = "http://$host:${if (state.running) state.port else config.port}/api/v1"
        val line = stringResource(R.string.settings_api_instruction_line, base, config.token, "$base/openapi.json")
        Surface(color = Cockpit.Background, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Cockpit.Border), modifier = Modifier.fillMaxWidth()) {
            Text(
                if (showToken) line else line.replace(config.token, ApiTokens.redact(config.token)),
                color = Cockpit.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(stringResource(R.string.settings_copy)) { copy(context, line, sensitive = true) }
            SmallButton(stringResource(R.string.settings_share)) { share(context, line) }
        }
        Hint(stringResource(R.string.settings_api_background))
        Divider()
        ApiDocsRow(config, state)
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.settings_api_regenerate_title)) },
            text = { Text(stringResource(R.string.settings_api_regenerate_text)) },
            confirmButton = {
                TextButton(onClick = { store.regenerateToken(); confirmRegenerate = false }) {
                    Text(stringResource(R.string.settings_api_regenerate), color = Cockpit.Warning)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text(stringResource(R.string.settings_cancel), color = Cockpit.Text) } },
            containerColor = Cockpit.Card,
        )
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    SettingsCard {
        Text("BimmerData", color = Cockpit.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = BarlowCondensed)
        Text(stringResource(R.string.settings_version, version), color = Cockpit.Muted, fontSize = 14.sp)
        Hint(stringResource(R.string.settings_license))
        InfoRow(stringResource(R.string.settings_source), SOURCE_URL, null)
        Divider()
        Text(stringResource(R.string.settings_credits), color = Cockpit.Muted, fontSize = 13.sp)
        Hint(stringResource(R.string.settings_credit_ediabas))
        Hint(stringResource(R.string.settings_credit_fonts))
        Hint(stringResource(R.string.settings_credit_libs))
        Hint(stringResource(R.string.settings_trademark))
        Divider()
        LicensesBlock()
    }
}

private const val SOURCE_URL = "github.com/jonathan-gtd/BimmerData"

// ---------------------------------------------------------------- actions

/** Copies [text]; a token is flagged sensitive so Android 13+ hides it in the clipboard preview. */
private fun copy(context: Context, text: String, sensitive: Boolean) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("BimmerData", text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.settings_copied, Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------------------------------------------------------- building blocks

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Cockpit.Card,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Cockpit.Border),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text.uppercase(Locale.ROOT), color = Cockpit.Muted, fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
internal fun Mono(text: String) {
    Text(text, color = Cockpit.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
}

/** Coloured dot and a line of text (state of a service). */
@Composable
internal fun StatusLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, color = Cockpit.Text, fontSize = 15.sp)
    }
}

@Composable
internal fun Divider() = HorizontalDivider(color = Cockpit.Border, modifier = Modifier.padding(vertical = 2.dp))

@Composable
internal fun InfoRow(title: String, value: String?, hint: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Cockpit.Text, fontSize = 16.sp)
        value?.let { Mono(it) }
        hint?.let { Hint(it) }
    }
}

@Composable
internal fun Banner(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.45f))) {
        Text(text, color = Cockpit.Text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
internal fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (enabled) Cockpit.Text else Cockpit.Muted, fontSize = 16.sp)
            hint?.let { Hint(it) }
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Cockpit.OnAccent, checkedTrackColor = Cockpit.Accent,
                uncheckedThumbColor = Cockpit.Muted, uncheckedTrackColor = Cockpit.Background, uncheckedBorderColor = Cockpit.Border,
            ),
        )
    }
}

@Composable
internal fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Cockpit.Background, RoundedCornerShape(10.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
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

@Composable
internal fun SmallButton(text: String, enabled: Boolean = true, color: Color = Cockpit.Text, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.6f) else Cockpit.Border),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) { Text(text, color = if (enabled) color else Cockpit.Muted) }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cockpit.Accent, unfocusedBorderColor = Cockpit.Border,
    focusedLabelColor = Cockpit.Accent, unfocusedLabelColor = Cockpit.Muted,
    focusedTextColor = Cockpit.Text, unfocusedTextColor = Cockpit.Text, cursorColor = Cockpit.Accent,
)

