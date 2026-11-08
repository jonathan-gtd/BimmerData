/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.api.android.ApiRuntimeState
import io.github.jonathangtd.bimmerdata.export.ExportConfig
import io.github.jonathangtd.bimmerdata.export.ExportMode
import io.github.jonathangtd.bimmerdata.export.ExportPhase
import io.github.jonathangtd.bimmerdata.export.LineQueue
import io.github.jonathangtd.bimmerdata.export.QueueEstimate
import io.github.jonathangtd.bimmerdata.export.TelegrafClient
import io.github.jonathangtd.bimmerdata.export.TelegrafRuntime
import io.github.jonathangtd.bimmerdata.export.WriteResult
import io.github.jonathangtd.bimmerdata.live.CsvSessionRecorder
import io.github.jonathangtd.bimmerdata.live.RecordingStorage
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.ui.LocalAppNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

// ---------------------------------------------------------------- tools

@Composable
internal fun ToolsSection() {
    val navigator = LocalAppNavigator.current
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { navigator.openTool32(null) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_tool32), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.nav_tool32), color = Cockpit.Text, fontSize = 16.sp)
                Hint(stringResource(R.string.settings2_tool32_hint))
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------- recording

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingSection(config: RecordingConfig, store: AppSettings) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            error = runCatching { RecordingStorage.choose(context, uri) }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        }
    }
    // config is part of the key: the label follows a change of folder
    val folder = remember(config) { RecordingStorage.describe(context) }
    val custom = remember(config) { RecordingStorage.chosenFolder(context) != null }
    var prefix by rememberSaveable(config.filePrefix) { mutableStateOf(config.filePrefix) }

    SettingsCard {
        InfoRow(
            stringResource(R.string.settings_rec_folder), folder,
            if (custom) stringResource(R.string.settings2_rec_custom_hint) else stringResource(R.string.settings_rec_folder_hint, folder),
        )
        error?.let { Text(stringResource(R.string.settings2_rec_choose_failed, it), color = Cockpit.Danger, fontSize = 13.sp) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(stringResource(R.string.settings2_rec_choose)) { pick.launch(null) }
            if (custom) SmallButton(stringResource(R.string.settings2_rec_default)) { RecordingStorage.reset(context) }
        }
        Divider()
        Text(stringResource(R.string.settings2_rec_rows), color = Cockpit.Text, fontSize = 16.sp)
        Segmented(
            options = RecordingConfig.ROW_INTERVALS.map { ms ->
                ms to if (ms == 0L) stringResource(R.string.settings2_rec_rows_all) else stringResource(R.string.settings2_rec_rows_hz, (1000 / ms).toInt())
            },
            selected = config.rowIntervalMs,
            onSelect = { ms -> store.updateRecording { it.copy(rowIntervalMs = ms) } },
        )
        Hint(
            if (config.rowIntervalMs == 0L) stringResource(R.string.settings2_rec_rows_hint_all)
            else stringResource(R.string.settings2_rec_rows_hint, (1000 / config.rowIntervalMs).toInt()),
        )
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = prefix,
                onValueChange = { v -> prefix = v.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40) },
                label = { Text(stringResource(R.string.settings2_rec_prefix)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            SmallButton(stringResource(R.string.settings_api_apply), enabled = CsvSessionRecorder.safePrefix(prefix) != config.filePrefix) {
                store.updateRecording { it.copy(filePrefix = CsvSessionRecorder.safePrefix(prefix)) }
            }
        }
        Hint(stringResource(R.string.settings2_rec_prefix_hint, CsvSessionRecorder.fileName(System.currentTimeMillis(), prefix = prefix)))
    }
    SettingsCard {
        SectionLabel(stringResource(R.string.s3_rec_content))
        Hint(stringResource(R.string.s3_rec_content_hint))
        Hint(stringResource(R.string.s3_rec_time_hint))
    }
}

// ---------------------------------------------------------------- Telegraf export

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TelegrafSection(t: TelegrafSettings, store: AppSettings) {
    val context = LocalContext.current
    val runtime = remember { TelegrafRuntime.get(context) }
    val secrets = remember { SecretStore.get(context) }
    val state by runtime.state.collectAsStateWithLifecycle()
    val secretVersion by secrets.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val numbers = remember { NumberFormat.getIntegerInstance() }

    var url by rememberSaveable(t.url) { mutableStateOf(t.url) }
    var car by rememberSaveable(t.car) { mutableStateOf(t.car) }
    var user by rememberSaveable(t.username) { mutableStateOf(t.username) }
    var secret by rememberSaveable { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteRejected by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }

    // queue figures (free space, rate) move while the page is open
    LaunchedEffect(Unit) {
        while (true) {
            runtime.refresh()
            delay(2_000)
        }
    }

    val urlValid = url.isBlank() || TelegrafClient.isValidUrl(url)
    val secretName = if (t.authMode == TelegrafAuthMode.Basic) SecretStore.TELEGRAF_PASSWORD else SecretStore.TELEGRAF_TOKEN
    val secretStored = remember(secretVersion, secretName) { secrets.has(secretName) }
    val dirty = url.trim() != t.url || car.trim() != t.car || user.trim() != t.username || secret.isNotEmpty()

    fun save() {
        if (!urlValid) return
        if (secret.isNotEmpty() && t.authMode != TelegrafAuthMode.None) secrets.set(secretName, secret)
        secret = ""
        store.updateTelegraf { it.copy(url = url.trim(), car = car.trim(), username = user.trim()) }
        runtime.sendNow()
    }

    val exportedText = stringResource(R.string.s3_tg_rejected_exported)
    val exportFailedText = stringResource(R.string.settings2_tg_test_failed, "%s")
    val exportRejected = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportResult = runCatching {
            // concatenated gzip members are a valid gzip file: zcat gives every line back
            context.contentResolver.openOutputStream(uri)!!.use { out -> runtime.rejectedFiles().forEach { f -> f.inputStream().use { it.copyTo(out) } } }
            exportedText
        }.getOrElse { exportFailedText.replace("%s", it.message ?: it.javaClass.simpleName) }
    }

    // ---- server
    SettingsCard {
        SwitchRow(
            title = stringResource(R.string.settings2_tg_enable),
            hint = stringResource(R.string.settings2_tg_enable_hint),
            checked = t.enabled,
            onChange = { on -> store.updateTelegraf { it.copy(enabled = on) } },
        )
        Divider()
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim() },
            label = { Text(stringResource(R.string.settings2_tg_url)) },
            placeholder = { Text("http://192.168.1.10:8186/api/v2/write") },
            singleLine = true, isError = !urlValid,
            supportingText = if (!urlValid) ({ Text(stringResource(R.string.settings2_tg_url_error)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.settings2_tg_auth), color = Cockpit.Text, fontSize = 16.sp)
        Segmented(
            options = listOf(
                TelegrafAuthMode.None to stringResource(R.string.settings2_tg_auth_none),
                TelegrafAuthMode.Token to stringResource(R.string.settings2_tg_auth_token),
                TelegrafAuthMode.Basic to stringResource(R.string.settings2_tg_auth_basic),
            ),
            selected = t.authMode,
            onSelect = { m -> secret = ""; store.updateTelegraf { it.copy(authMode = m) } },
        )
        if (t.authMode == TelegrafAuthMode.Basic) {
            OutlinedTextField(
                value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.settings2_tg_user)) },
                singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (t.authMode != TelegrafAuthMode.None) {
            OutlinedTextField(
                value = secret, onValueChange = { secret = it },
                label = { Text(stringResource(if (t.authMode == TelegrafAuthMode.Basic) R.string.settings2_tg_password else R.string.settings2_tg_token)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                supportingText = { Text(stringResource(if (secretStored) R.string.settings2_tg_secret_stored else R.string.settings2_tg_secret_missing)) },
                colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = car, onValueChange = { car = it.take(60) }, label = { Text(stringResource(R.string.settings2_tg_car)) },
            supportingText = { Text(stringResource(R.string.settings2_tg_car_hint)) },
            singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        if (dirty) {
            SmallButton(stringResource(R.string.settings2_save), enabled = urlValid, color = Cockpit.Accent) { save() }
        }
        testResult?.let { Text(it, color = Cockpit.Text, fontSize = 14.sp) }
        val testing2 = stringResource(R.string.settings2_tg_testing)
        val okText = stringResource(R.string.settings2_tg_test_ok)
        val authText = stringResource(R.string.settings2_tg_test_auth, "%s")
        val failText = stringResource(R.string.settings2_tg_test_failed, "%s")
        SmallButton(stringResource(R.string.settings2_tg_test), enabled = !testing && TelegrafClient.isValidUrl(url)) {
            if (dirty) save()
            testing = true
            testResult = testing2
            scope.launch {
                val r = runtime.test(runtime.configNow().copy(url = url.trim()))
                testResult = when (r) {
                    WriteResult.Ok -> okText
                    is WriteResult.AuthFailed -> authText.replace("%s", r.message)
                    is WriteResult.Rejected -> failText.replace("%s", r.message)
                    is WriteResult.Retry -> failText.replace("%s", r.message)
                }
                testing = false
            }
        }
    }

    // ---- sending
    SettingsCard {
        SectionLabel(stringResource(R.string.s3_tg_sending))
        Text(stringResource(R.string.settings2_tg_mode), color = Cockpit.Text, fontSize = 16.sp)
        Segmented(
            options = listOf(ExportMode.AfterTrip to stringResource(R.string.settings2_tg_mode_after), ExportMode.Live to stringResource(R.string.settings2_tg_mode_live)),
            selected = t.mode,
            onSelect = { m -> store.updateTelegraf { it.copy(mode = m) } },
        )
        Hint(stringResource(if (t.mode == ExportMode.AfterTrip) R.string.settings2_tg_mode_after_hint else R.string.settings2_tg_mode_live_hint))
        Divider()
        Text(stringResource(R.string.settings2_tg_rate), color = Cockpit.Text, fontSize = 16.sp)
        Segmented(
            options = ExportConfig.RATES.map { it to it.toString() },
            selected = t.pointsPerSecond,
            onSelect = { r -> store.updateTelegraf { it.copy(pointsPerSecond = r) } },
        )
        Hint(stringResource(R.string.settings2_tg_rate_hint))
    }

    // ---- queue on the phone
    SettingsCard {
        SectionLabel(stringResource(R.string.s3_tg_queue))
        val (color, phase) = when (state.phase) {
            ExportPhase.Off -> Cockpit.Muted to R.string.settings2_tg_phase_off
            ExportPhase.Idle -> Cockpit.Accent to R.string.settings2_tg_phase_idle
            ExportPhase.WaitingForNetwork -> Cockpit.Warning to R.string.settings2_tg_phase_network
            ExportPhase.WaitingForUnmetered -> Cockpit.Warning to R.string.settings2_tg_phase_unmetered
            ExportPhase.Sending -> Cockpit.Accent to R.string.settings2_tg_phase_sending
            ExportPhase.Retrying -> Cockpit.Warning to R.string.settings2_tg_phase_retrying
            ExportPhase.AuthFailed -> Cockpit.Danger to R.string.settings2_tg_phase_auth
        }
        StatusLine(color, stringResource(phase))
        state.lastSuccessMs?.let { Hint(stringResource(R.string.settings2_tg_last_ok, DateUtils.getRelativeTimeSpanString(it).toString())) }
        state.lastError?.let { Text(stringResource(R.string.settings2_tg_last_error, it), color = Cockpit.Warning, fontSize = 13.sp) }

        if (state.blocked) {
            Banner(stringResource(R.string.s3_tg_blocked, size(context, state.reserveBytes), numbers.format(state.refusedLines)), Cockpit.Danger)
        } else if (QueueEstimate.growthLevel(state.queuedBytes) >= 0) {
            Banner(stringResource(R.string.s3_tg_growing, size(context, state.queuedBytes)), Cockpit.Warning)
        }
        Figure(stringResource(R.string.s3_tg_points), numbers.format(state.queuedLines))
        Figure(
            stringResource(R.string.s3_tg_disk),
            if (state.queuedRawBytes > 0) stringResource(R.string.s3_tg_disk_value, size(context, state.queuedBytes), size(context, state.queuedRawBytes))
            else size(context, state.queuedBytes),
        )
        Figure(stringResource(R.string.s3_tg_free), stringResource(R.string.s3_tg_free_value, size(context, state.freeBytes), size(context, state.reserveBytes)))
        val measurements = state.measurementCount.coerceAtLeast(10)
        val rate = QueueEstimate.rawBytesPerSecond(state.queueBytesPerSecond, t.pointsPerSecond, measurements)
        val autonomy = QueueEstimate.autonomySeconds(state.roomBytes, rate, QueueEstimate.gzipRatio(state.queuedBytes, state.queuedRawBytes))
        Figure(
            stringResource(R.string.s3_tg_autonomy),
            when {
                autonomy == null -> "—"
                autonomy < 60 -> stringResource(R.string.s3_tg_autonomy_none)
                else -> "≈ " + durationText(autonomy)
            },
        )
        Hint(
            if (state.queueBytesPerSecond > 0) stringResource(R.string.s3_tg_autonomy_measured)
            else pluralStringResource(R.plurals.s3_tg_autonomy_estimated, measurements, t.pointsPerSecond, measurements),
        )
        if (state.sentLines > 0) Hint(stringResource(R.string.s3_tg_sent, numbers.format(state.sentLines)))
        if (state.refusedLines > 0 && !state.blocked) Hint(stringResource(R.string.s3_tg_refused, numbers.format(state.refusedLines)))
        Divider()
        Text(stringResource(R.string.s3_tg_reserve), color = Cockpit.Text, fontSize = 16.sp)
        Segmented(
            options = QueueEstimate.RESERVES_MB.map { mb -> mb to size(context, mb * LineQueue.MB) },
            selected = t.reserveMb,
            onSelect = { mb -> store.updateTelegraf { it.copy(reserveMb = mb) } },
        )
        Hint(stringResource(R.string.s3_tg_reserve_hint))
        Hint(stringResource(R.string.s3_tg_where))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(stringResource(R.string.settings2_tg_send), enabled = t.enabled && state.queuedLines > 0) { runtime.sendNow() }
            SmallButton(stringResource(R.string.settings2_tg_clear), enabled = state.queuedLines > 0, color = Cockpit.Warning) { confirmClear = true }
        }
    }

    // ---- batches refused by the server
    if (state.rejectedBatches > 0) {
        SettingsCard {
            SectionLabel(stringResource(R.string.s3_tg_rejected_title))
            Banner(pluralStringResource(R.plurals.s3_tg_rejected_text, state.rejectedBatches, state.rejectedBatches, numbers.format(state.rejectedLines)), Cockpit.Warning)
            exportResult?.let { Hint(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(stringResource(R.string.s3_tg_rejected_retry), color = Cockpit.Accent) { runtime.retryRejected() }
                SmallButton(stringResource(R.string.s3_tg_rejected_export)) { exportRejected.launch("bimmerdata-rejected.lp.gz") }
                SmallButton(stringResource(R.string.s3_tg_rejected_delete), color = Cockpit.Warning) { confirmDeleteRejected = true }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings2_tg_clear_title)) },
            text = { Text(stringResource(R.string.settings2_tg_clear_text, numbers.format(state.queuedLines))) },
            confirmButton = {
                TextButton(onClick = { runtime.clearQueue(); confirmClear = false }) { Text(stringResource(R.string.settings2_tg_clear), color = Cockpit.Warning) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.settings_cancel), color = Cockpit.Text) } },
            containerColor = Cockpit.Card,
        )
    }
    if (confirmDeleteRejected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteRejected = false },
            title = { Text(stringResource(R.string.s3_tg_rejected_delete)) },
            text = { Text(stringResource(R.string.s3_tg_rejected_delete_text, numbers.format(state.rejectedLines))) },
            confirmButton = {
                TextButton(onClick = { runtime.deleteRejected(); confirmDeleteRejected = false }) {
                    Text(stringResource(R.string.s3_tg_rejected_delete), color = Cockpit.Warning)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteRejected = false }) { Text(stringResource(R.string.settings_cancel), color = Cockpit.Text) } },
            containerColor = Cockpit.Card,
        )
    }
}

/** "Points waiting ............ 12 345" */
@Composable
private fun Figure(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Cockpit.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Cockpit.Text, fontSize = 15.sp)
    }
}

// ---------------------------------------------------------------- API documentation

@Composable
internal fun ApiDocsRow(config: ApiConfig, state: ApiRuntimeState) {
    val navigator = LocalAppNavigator.current
    val host = state.addresses.firstOrNull()?.host ?: "127.0.0.1"
    val url = "http://$host:${if (state.running) state.port else config.port}/api/v1/docs"
    SmallButton(stringResource(R.string.settings2_api_docs), color = Cockpit.Accent) { navigator.openApiDocs() }
    Hint(stringResource(R.string.settings2_api_docs_hint, url))
}

// ---------------------------------------------------------------- licenses

private class License(val name: String, val license: String, val url: String)

private val LICENSES = listOf(
    License("EdiabasLib (Ulrich Holeschak), ported to Kotlin", "GPL-3.0", "github.com/uholeschak/ediabaslib"),
    License("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0", "kotlinlang.org"),
    License("AndroidX, Jetpack Compose, Car App Library", "Apache-2.0", "developer.android.com/jetpack"),
    License("usb-serial-for-android", "MIT", "github.com/mik3y/usb-serial-for-android"),
    License("Swagger UI (swagger-ui-dist)", "Apache-2.0", "github.com/swagger-api/swagger-ui"),
    License("Barlow, Barlow Semi Condensed (Jeremy Tribby)", "SIL OFL 1.1", "github.com/jpt/barlow"),
)

@Composable
internal fun LicensesBlock() {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val swaggerVersion = remember { runCatching { context.assets.open("swagger-ui/VERSION").use { it.readBytes().toString(Charsets.UTF_8).trim() } }.getOrNull() }
    Text(stringResource(R.string.settings2_licenses), color = Cockpit.Muted, fontSize = 13.sp)
    SmallButton(stringResource(if (open) R.string.settings2_licenses_hide else R.string.settings2_licenses_show)) { open = !open }
    if (open) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            for (l in LICENSES) {
                val name = if (l.name.startsWith("Swagger") && swaggerVersion != null) "${l.name} $swaggerVersion" else l.name
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(name, color = Cockpit.Text, fontSize = 14.sp)
                    Hint("${l.license} · ${l.url}")
                }
            }
        }
    }
}
