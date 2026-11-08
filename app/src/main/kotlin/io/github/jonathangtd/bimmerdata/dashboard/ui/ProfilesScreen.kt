/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.DashboardViewModel
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.ui.LocalAppNavigator
import kotlinx.coroutines.launch

private sealed interface ProfilesSheet {
    /** New profile ([id] null) or changes of profile [id]. */
    data class Edit(val id: String?) : ProfilesSheet
    data class ConfirmDelete(val id: String, val name: String) : ProfilesSheet
}

/**
 * Profiles tab: every profile with the active one marked; activate, create, edit, duplicate,
 * export, import and delete (with undo). Shares the dashboard's view model (same activity), so a
 * change here is what the dashboard and Android Auto show.
 */
@Composable
fun ProfilesScreen(vm: DashboardViewModel = viewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val navigator = LocalAppNavigator.current
    var sheet by remember { mutableStateOf<ProfilesSheet?>(null) }

    fun message(text: String, actionLabel: String? = null, action: (suspend () -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = actionLabel, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) action?.invoke()
        }
    }

    var exportId by remember { mutableStateOf<String?>(null) }
    val saveProfile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = exportId?.let { vm.exportProfile(it) }
        if (uri != null && json != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                .onSuccess { message(res.getString(R.string.profile_exported)) }
                .onFailure { message(res.getString(R.string.import_failed, it.message.orEmpty())) }
        }
        exportId = null
    }
    val openProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            val result = if (text == null) Result.failure(IllegalArgumentException("?")) else runCatching { vm.importProfile(text, res.getString(R.string.profile_default_name)) }
            result.onSuccess { message(res.getString(R.string.profile_imported, it)) }
                .onFailure { message(res.getString(R.string.import_failed, it.message.orEmpty())) }
        }
    }
    fun importProfile() = openProfile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))

    val activate: (String) -> Unit = { id ->
        if (id != profiles.activeId) scope.launch {
            vm.activateProfile(id)
            val name = profiles.profile(id)?.name.orEmpty()
            message(res.getString(R.string.ptab_activated, name), actionLabel = res.getString(R.string.ptab_open_dashboard)) { navigator.openDashboard() }
        }
    }
    val actions = ProfileActions(
        onEdit = { id -> sheet = ProfilesSheet.Edit(id) },
        onDuplicate = { id -> scope.launch { vm.duplicateProfile(id)?.let { message(res.getString(R.string.profile_duplicated, it.name)) } } },
        onExport = { id ->
            exportId = id
            saveProfile.launch(profileFileName(profiles.profile(id)?.name ?: "profile"))
        },
        onDelete = { id -> sheet = ProfilesSheet.ConfirmDelete(id, profiles.profile(id)?.name.orEmpty()) },
    )

    Box(Modifier.fillMaxSize().background(Cockpit.Background)) {
        ProfilesTabContent(
            set = profiles, onActivate = activate, onNew = { sheet = ProfilesSheet.Edit(null) }, onImport = ::importProfile, actions = actions,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    when (val s = sheet) {
        null -> Unit
        is ProfilesSheet.Edit -> {
            val editing = s.id?.let { profiles.profile(it) }
            ProfileEditorSheet(
                initial = editing, cars = rememberCarChoices(), loadChoices = { vm.sgbdChoices() }, onDismiss = { sheet = null },
                onSave = { name, vehicle, ecus ->
                    sheet = null
                    scope.launch {
                        if (editing == null) vm.createProfile(name, vehicle, ecus) else vm.editProfile(editing.id, name, vehicle, ecus)
                    }
                },
            )
        }
        is ProfilesSheet.ConfirmDelete -> ConfirmDialog(
            text = stringResource(R.string.profile_delete_confirm, s.name),
            confirm = stringResource(R.string.profile_delete),
            onConfirm = {
                sheet = null
                scope.launch {
                    vm.deleteProfile(s.id)
                    message(res.getString(R.string.profile_deleted, s.name), actionLabel = res.getString(R.string.undo)) { vm.undoDelete() }
                }
            },
            onDismiss = { sheet = null },
        )
    }
}

/** Stateless content of the Profiles tab (also used by the screenshot tests). */
@Composable
internal fun ProfilesTabContent(
    set: ProfileSet,
    onActivate: (String) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    actions: ProfileActions,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Text(
            stringResource(R.string.profiles_title), color = Cockpit.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = BarlowCondensed, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        if (set.profiles.isEmpty()) {
            // Nothing yet: what a profile is, and the two ways to get one.
            NoProfile(onCreate = onNew, onImport = onImport)
            return
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(stringResource(R.string.ptab_intro), color = Cockpit.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            items(set.profiles, key = { it.id }) { p ->
                Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp)) {
                    Box(Modifier.padding(4.dp)) {
                        ProfileRow(p, active = p.id == set.activeId, onActivate = onActivate, actions = actions)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        stringResource(R.string.profile_new), onClick = onNew, icon = R.drawable.ic_add,
                        color = Cockpit.OnAccent, background = Cockpit.Accent, modifier = Modifier.weight(1f),
                    )
                    PillButton(stringResource(R.string.profile_import), onClick = onImport, icon = R.drawable.ic_upload, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
