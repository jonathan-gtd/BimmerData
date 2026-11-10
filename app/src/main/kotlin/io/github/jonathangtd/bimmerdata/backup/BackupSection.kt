/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.ui.MinTouch
import io.github.jonathangtd.bimmerdata.settings.Banner
import io.github.jonathangtd.bimmerdata.settings.Hint
import io.github.jonathangtd.bimmerdata.settings.SectionLabel
import io.github.jonathangtd.bimmerdata.settings.SettingsCard
import io.github.jonathangtd.bimmerdata.settings.SmallButton
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Result line of the Backup page. */
internal sealed interface BackupStatus {
    data object Idle : BackupStatus
    data object Working : BackupStatus
    data class Exported(val cars: Int, val profiles: Int) : BackupStatus
    data class Restored(val summary: RestoreSummary) : BackupStatus
    data class Failed(val message: String) : BackupStatus
}

/** A file read and checked, waiting for the user's "Replace". */
private class PendingRestore(val text: String, val summary: RestoreSummary)

/**
 * Settings › Backup: export the whole app to a file (Android's save dialog) and restore one
 * (Android's file picker, then a check of the file and a confirmation). A restore from the phone
 * also puts Settings › Local API back (not through the API itself).
 */
@Composable
internal fun BackupSection() {
    val context = LocalContext.current
    val res = LocalResources.current
    val manager = remember { BackupManager.get(context) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<BackupStatus>(BackupStatus.Idle) }
    var pending by remember { mutableStateOf<PendingRestore?>(null) }

    fun failed(e: Throwable) = BackupStatus.Failed(e.message ?: e.javaClass.simpleName)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = BackupStatus.Working
        scope.launch {
            status = runCatching {
                withContext(Dispatchers.IO) {
                    val text = manager.export()
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("cannot write the file")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    val (content, _) = BackupCodec.decode(text)
                    BackupStatus.Exported(content.garage.vehicles.size, content.profiles.profiles.size)
                }
            }.getOrElse(::failed)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = BackupStatus.Working
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size <= BackupCodec.MAX_FILE_BYTES) { "file too large" }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("cannot read the file")
                    PendingRestore(text, manager.restore(text, includeApi = true, dryRun = true))
                }
            }.onSuccess { pending = it; status = BackupStatus.Idle }.onFailure { status = failed(it) }
        }
    }

    BackupContentView(
        status = status,
        onExport = { save.launch(BackupCodec.fileName(System.currentTimeMillis())) },
        onRestore = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
    )

    pending?.let { p ->
        val s = p.summary
        val date = s.createdAt?.let { runCatching { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(java.time.Instant.parse(it))) }.getOrNull() }
            ?: res.getString(R.string.backup_unknown_date)
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = Cockpit.Card,
            title = { Text(stringResource(R.string.backup_confirm_title), color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp) },
            text = {
                Text(
                    stringResource(R.string.backup_confirm_text, date, s.appVersion ?: "?", s.cars, s.profiles, s.graphs),
                    color = Cockpit.Text, fontSize = 16.sp, lineHeight = 22.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    status = BackupStatus.Working
                    scope.launch {
                        status = runCatching { withContext(Dispatchers.IO) { BackupStatus.Restored(manager.restore(p.text, includeApi = true)) } }.getOrElse(::failed)
                    }
                }, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.backup_confirm), color = Cockpit.Warning) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.cancel), color = Cockpit.Accent) }
            },
        )
    }
}

/** Stateless content of the Backup page (also used by the screenshot tests). */
@Composable
internal fun BackupContentView(status: BackupStatus, onExport: () -> Unit, onRestore: () -> Unit) {
    val working = status == BackupStatus.Working
    when (status) {
        BackupStatus.Idle -> Unit
        BackupStatus.Working -> Banner(stringResource(R.string.backup_working), Cockpit.Muted)
        is BackupStatus.Exported -> Banner(stringResource(R.string.backup_exported, status.cars, status.profiles), Cockpit.Accent)
        is BackupStatus.Failed -> Banner(stringResource(R.string.backup_failed, status.message), Cockpit.Danger)
        is BackupStatus.Restored -> SettingsCard {
            Text(stringResource(R.string.backup_restored, status.summary.cars, status.summary.profiles), color = Cockpit.Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            val todo = status.summary.todoCodes.mapNotNull { Todo.of(it) }
            if (todo.isNotEmpty()) {
                SectionLabel(stringResource(R.string.backup_todo))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in todo) Hint("• " + stringResource(t.label()))
                }
            }
        }
    }
    SettingsCard {
        SectionLabel(stringResource(R.string.backup_export_label))
        Hint(stringResource(R.string.backup_export_hint))
        SmallButton(stringResource(R.string.backup_export), enabled = !working, onClick = onExport)
    }
    SettingsCard {
        SectionLabel(stringResource(R.string.backup_restore_label))
        Hint(stringResource(R.string.backup_restore_hint))
        SmallButton(stringResource(R.string.backup_restore), enabled = !working, color = Cockpit.Warning, onClick = onRestore)
    }
    SettingsCard {
        SectionLabel(stringResource(R.string.backup_privacy_label))
        Hint(stringResource(R.string.backup_privacy_vin))
        Hint(stringResource(R.string.backup_privacy_secrets))
        Hint(stringResource(R.string.backup_privacy_not))
        Hint(stringResource(R.string.backup_privacy_api))
    }
}

private fun Todo.label(): Int = when (this) {
    Todo.EcuFiles -> R.string.backup_todo_ecu_files
    Todo.TelegrafSecret -> R.string.backup_todo_telegraf_secret
    Todo.TranslationFolder -> R.string.backup_todo_translation_folder
    Todo.RecordingFolder -> R.string.backup_todo_recording_folder
}
