/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.cars

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.DashboardViewModel
import io.github.jonathangtd.bimmerdata.dashboard.ui.CockpitSheet
import io.github.jonathangtd.bimmerdata.dashboard.ui.MinTouch
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.ProfileActions
import io.github.jonathangtd.bimmerdata.dashboard.ui.ProfileEditorSheet
import io.github.jonathangtd.bimmerdata.dashboard.ui.ProfileRow
import io.github.jonathangtd.bimmerdata.dashboard.ui.SectionTitle
import io.github.jonathangtd.bimmerdata.dashboard.ui.SheetHeader
import io.github.jonathangtd.bimmerdata.dashboard.ui.profileFileName
import io.github.jonathangtd.bimmerdata.dashboard.ui.toEditorCar
import io.github.jonathangtd.bimmerdata.dashboard.ui.ConfirmDialog
import io.github.jonathangtd.bimmerdata.faults.MessageCard
import io.github.jonathangtd.bimmerdata.faults.TaskCard
import io.github.jonathangtd.bimmerdata.faults.messageText
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.ui.LocalAppNavigator
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import io.github.jonathangtd.bimmerdata.vehicle.VehicleState
import kotlinx.coroutines.launch

private sealed interface CarTabSheet {
    /** New profile ([id] null) or changes of profile [id]. */
    data class Edit(val id: String?) : CarTabSheet
    data class ConfirmDelete(val id: String, val name: String) : CarTabSheet
    data class CopyTo(val id: String) : CarTabSheet
    data object AddCar : CarTabSheet
    data object RenameCar : CarTabSheet
    data object CarEcus : CarTabSheet
}

/**
 * Profiles tab: the car shown at the top, its name a drop-down of the cars (show another car, add
 * one, My cars), with its ECU list, last detection, "Detect ECUs" and its ECU list editor, rename;
 * then its profiles with the active one marked (activate, create, edit,
 * duplicate, copy to another car, export, import and delete with undo). Without a car shown: a
 * "Change car" drop-down (or "Add a car"), no profile. Shares the dashboard's view model (same activity), so a change here is
 * what the dashboard and Android Auto show.
 */
@Composable
fun CarTabScreen(vm: DashboardViewModel = viewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val carList by vm.carList.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = LocalResources.current
    val book = remember { CarBook.get(context) }
    val repo = remember { VehicleRepository.get(context) }
    val state by repo.state.collectAsStateWithLifecycle()
    val selectedCar = state.garage.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val navigator = LocalAppNavigator.current
    var sheet by remember { mutableStateOf<CarTabSheet?>(null) }

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
        onEdit = { id -> sheet = CarTabSheet.Edit(id) },
        onDuplicate = { id -> scope.launch { vm.duplicateProfile(id)?.let { message(res.getString(R.string.profile_duplicated, it.name)) } } },
        onExport = { id ->
            exportId = id
            saveProfile.launch(profileFileName(profiles.profile(id)?.name ?: "profile"))
        },
        onDelete = { id -> sheet = CarTabSheet.ConfirmDelete(id, profiles.profile(id)?.name.orEmpty()) },
        onCopyTo = if (carList.size > 1) { id -> sheet = CarTabSheet.CopyTo(id) } else null,
    )
    val carActions = CarTabActions(
        car = CarCardActions(
            onDetect = { repo.detect() },
            onEcus = { sheet = CarTabSheet.CarEcus },
            onRename = { sheet = CarTabSheet.RenameCar },
        ),
        onMyCars = navigator.openCars,
        onAddCar = { sheet = CarTabSheet.AddCar },
        onSelectCar = { id -> scope.io { book.select(id) } },
        onCancel = repo::cancel,
        onDismissMessage = repo::dismissMessage,
    )

    Box(Modifier.fillMaxSize().background(Cockpit.Background)) {
        CarTabContent(
            car = selectedCar, cars = state.garage.vehicles, state = state, set = profiles,
            onActivate = activate, onNew = { sheet = CarTabSheet.Edit(null) }, onImport = ::importProfile, actions = actions, carActions = carActions,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    MissingEcusQuestion(state, snackbar)

    when (val s = sheet) {
        null -> Unit
        is CarTabSheet.Edit -> {
            val editing = s.id?.let { profiles.profile(it) }
            ProfileEditorSheet(
                initial = editing, car = selectedCar?.toEditorCar(), loadChoices = { vm.profileEcuChoices() }, onDismiss = { sheet = null },
                onSave = { name, vehicle, ecus, dropCards ->
                    sheet = null
                    scope.launch {
                        if (editing == null) vm.createProfile(name, vehicle, ecus) else vm.editProfile(editing.id, name, vehicle, ecus, dropCards)
                    }
                },
            )
        }
        is CarTabSheet.ConfirmDelete -> ConfirmDialog(
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
        is CarTabSheet.CopyTo -> {
            val p = profiles.profile(s.id)
            CockpitSheet({ sheet = null }) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    SheetHeader(stringResource(R.string.cars_copy_title, p?.name.orEmpty()))
                    carList.filter { it.id != selectedCar?.id }.forEach { c ->
                        Text(
                            c.label, color = Cockpit.Text, fontSize = 17.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                sheet = null
                                scope.launch { vm.copyProfile(s.id, c.id)?.let { message(res.getString(R.string.cars_copied, c.label)) } }
                            }.heightIn(min = MinTouch).padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
        CarTabSheet.AddCar -> AddCarDialog(
            onDismiss = { sheet = null },
            onAdd = { label, series, model ->
                sheet = null
                scope.io { book.create(label, series, model) }
            },
        )
        CarTabSheet.RenameCar -> selectedCar?.let { RenameCarDialog(it, onDismiss = { sheet = null }) }
        CarTabSheet.CarEcus -> selectedCar?.let { CarEcusSheet(it, loadChoices = { vm.sgbdChoices() }, snackbar = snackbar, onDismiss = { sheet = null }) }
    }
}

/** What the Profiles tab does besides the profiles: the car card, My cars, adding the first car, the detection's progress and message. */
internal class CarTabActions(
    val car: CarCardActions = CarCardActions(),
    val onMyCars: () -> Unit = {},
    val onAddCar: () -> Unit = {},
    /** Shows another car (the car list at the top). */
    val onSelectCar: (String) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

/** Stateless content of the Profiles tab (also used by the screenshot tests). [car]: the car shown, null = none yet; [cars]: every car. */
@Composable
internal fun CarTabContent(
    car: Vehicle?,
    cars: List<Vehicle>,
    state: VehicleState,
    set: ProfileSet,
    onActivate: (String) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    actions: ProfileActions,
    carActions: CarTabActions,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Text(
            stringResource(R.string.nav_profiles), color = Cockpit.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = BarlowCondensed, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        // the car first (its name opens the car list): the profiles below are its own
        val picker = CarPicker(cars, carActions.onSelectCar, carActions.onAddCar, carActions.onMyCars)
        if (car == null) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.message?.let { m -> MessageCard(messageText(m), m.error, carActions.onDismissMessage) }
                if (cars.isNotEmpty()) {
                    // cars but none shown: pick one before its profiles
                    var list by remember { mutableStateOf(false) }
                    Box {
                        PillButton(stringResource(R.string.cars_change), onClick = { list = true }, icon = R.drawable.ic_chevron_down, color = Cockpit.OnAccent, background = Cockpit.Accent)
                        CarPickerMenu(list, { list = false }, picker, null)
                    }
                }
            }
            if (cars.isEmpty()) NoCar(carActions.onAddCar)
            return
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.busy) item(key = "task") { TaskCard(state.task, carActions.onCancel) }
            state.message?.let { m -> item(key = "message") { MessageCard(messageText(m), m.error, carActions.onDismissMessage) } }
            item(key = "car") { CarCard(car, busy = state.busy, actions = carActions.car, picker = picker) }
            item(key = "profiles-title") { SectionTitle(stringResource(R.string.nav_profiles), Modifier.padding(top = 8.dp)) }
            if (set.profiles.isEmpty()) {
                // no profile yet: what one is, and the two ways to get one (compact: the car stays above)
                item(key = "no-profile") {
                    Text(stringResource(R.string.profile_create_hint), color = Cockpit.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            } else {
                item(key = "intro") {
                    Text(stringResource(R.string.ptab_intro), color = Cockpit.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                items(set.profiles, key = { it.id }) { p ->
                    Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp)) {
                        Box(Modifier.padding(4.dp)) {
                            ProfileRow(p, active = p.id == set.activeId, onActivate = onActivate, actions = actions)
                        }
                    }
                }
            }
            item(key = "profile-buttons") {
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
