/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.cars

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.DashboardViewModel
import io.github.jonathangtd.bimmerdata.dashboard.SgbdChoices
import io.github.jonathangtd.bimmerdata.dashboard.ui.Chip
import io.github.jonathangtd.bimmerdata.dashboard.ui.CockpitSheet
import io.github.jonathangtd.bimmerdata.dashboard.ui.MinTouch
import io.github.jonathangtd.bimmerdata.dashboard.ui.PillButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.RoundIconButton
import io.github.jonathangtd.bimmerdata.dashboard.ui.SectionTitle
import io.github.jonathangtd.bimmerdata.dashboard.ui.SgbdStep
import io.github.jonathangtd.bimmerdata.dashboard.ui.SheetHeader
import io.github.jonathangtd.bimmerdata.dashboard.ui.TextInputDialog
import io.github.jonathangtd.bimmerdata.dashboard.ui.fieldColors
import io.github.jonathangtd.bimmerdata.faults.ConfirmDialog
import io.github.jonathangtd.bimmerdata.faults.MessageCard
import io.github.jonathangtd.bimmerdata.faults.TaskCard
import io.github.jonathangtd.bimmerdata.faults.messageText
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import io.github.jonathangtd.bimmerdata.vehicle.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One car as My cars lists it. */
internal data class CarRowInfo(val car: Vehicle, val profiles: Int, val selected: Boolean)

/** What can be done to one car: the "⋮" menu of its row. */
internal class CarActions(
    val onSelect: (String) -> Unit,
    val onDetect: (String) -> Unit,
    val onEcus: (String) -> Unit,
    val onRename: (String) -> Unit,
    val onDelete: (String) -> Unit,
)

private sealed interface CarsSheet {
    data object Add : CarsSheet
    data class Rename(val id: String) : CarsSheet
    data class Delete(val id: String) : CarsSheet
    data class Ecus(val id: String) : CarsSheet
}

/**
 * My cars: every car with its VIN state, series, profiles and ECUs; show one (tap), detect its
 * ECUs, set them by hand, rename, delete (with its profiles, after a confirmation), add one by
 * hand. Shown at start when there are several cars, and from the Car tab ("My cars") and the
 * profile switch ("Change car"). [onShown]: a car was chosen, back to the dashboard.
 */
@Composable
fun CarsScreen(onBack: () -> Unit, onShown: () -> Unit, vm: DashboardViewModel = viewModel()) {
    val context = LocalContext.current
    val res = LocalResources.current
    val book = remember { CarBook.get(context) }
    val repo = remember { VehicleRepository.get(context) }
    val state by repo.state.collectAsStateWithLifecycle()
    val profiles by ProfileStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf<CarsSheet?>(null) }

    val counts = profiles?.profiles.orEmpty().groupingBy { it.carId }.eachCount()
    val rows = state.garage.vehicles.map { CarRowInfo(it, counts[it.id] ?: 0, it.id == state.garage.currentId) }
    fun car(id: String) = state.garage.vehicles.firstOrNull { it.id == id }

    val actions = CarActions(
        onSelect = { id ->
            scope.io { book.select(id) }
            onShown()
        },
        onDetect = { id ->
            scope.io {
                book.select(id)
                repo.detect()
            }
        },
        onEcus = { id -> sheet = CarsSheet.Ecus(id) },
        onRename = { id -> sheet = CarsSheet.Rename(id) },
        onDelete = { id -> sheet = CarsSheet.Delete(id) },
    )

    Box(Modifier.fillMaxSize().background(Cockpit.Background)) {
        CarsContent(
            rows = rows, state = state, onBack = onBack, onAdd = { sheet = CarsSheet.Add }, actions = actions,
            onCancel = repo::cancel, onDismissMessage = repo::dismissMessage,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    MissingEcusQuestion(state, snackbar)

    when (val s = sheet) {
        null -> Unit
        CarsSheet.Add -> AddCarDialog(
            onDismiss = { sheet = null },
            onAdd = { label, series, model ->
                sheet = null
                scope.io { book.create(label, series, model) }
            },
        )
        is CarsSheet.Rename -> car(s.id)?.let { c -> RenameCarDialog(c, onDismiss = { sheet = null }) }
        is CarsSheet.Delete -> car(s.id)?.let { c ->
            val n = counts[c.id] ?: 0
            ConfirmDialog(
                title = stringResource(R.string.cars_delete_title, c.label),
                text = pluralStringResource(R.plurals.cars_delete_text, n, n),
                confirm = stringResource(R.string.cars_delete),
                onConfirm = {
                    sheet = null
                    scope.launch {
                        withContext(Dispatchers.IO) { book.delete(c.id) }
                        snackbar.showSnackbar(res.getString(R.string.cars_deleted, c.label))
                    }
                },
                onDismiss = { sheet = null },
            )
        }
        is CarsSheet.Ecus -> car(s.id)?.let { c ->
            CarEcusSheet(c, loadChoices = { vm.sgbdChoices() }, snackbar = snackbar, onDismiss = { sheet = null })
        }
    }
}

/** Runs [block] off the main thread (the car book writes files). */
internal fun CoroutineScope.io(block: () -> Unit) {
    launch { withContext(Dispatchers.IO) { block() } }
}

/** New name for [car] (My cars and the Car tab). */
@Composable
internal fun RenameCarDialog(car: Vehicle, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val book = remember { CarBook.get(context) }
    val scope = rememberCoroutineScope()
    TextInputDialog(
        title = stringResource(R.string.cars_rename), initial = car.label, placeholder = stringResource(R.string.cars_label_placeholder),
        maxLength = Cars.MAX_LABEL_LENGTH, hint = null,
        onConfirm = { text ->
            onDismiss()
            if (text.isNotBlank()) scope.io { book.update(car.id, label = text) }
        },
        onDismiss = onDismiss,
    )
}

/** Stateless content of My cars (also used by the screenshot tests). */
@Composable
internal fun CarsContent(
    rows: List<CarRowInfo>,
    state: VehicleState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    actions: CarActions,
    onCancel: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(R.drawable.ic_chevron_left, stringResource(R.string.back), onClick = onBack, background = Color.Transparent)
            Text(
                stringResource(R.string.cars_title), color = Cockpit.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = BarlowCondensed, modifier = Modifier.padding(start = 4.dp),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy) TaskCard(state.task, onCancel)
            state.message?.let { m -> MessageCard(messageText(m), m.error, onDismissMessage) }
        }
        if (rows.isEmpty()) {
            NoCar(onAdd)
            return
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(stringResource(R.string.cars_intro), color = Cockpit.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            items(rows, key = { it.car.id }) { r ->
                Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp)) {
                    Box(Modifier.padding(4.dp)) { CarRow(r, busy = state.busy, actions = actions) }
                }
            }
            item {
                PillButton(
                    stringResource(R.string.cars_add), onClick = onAdd, icon = R.drawable.ic_add,
                    color = Cockpit.OnAccent, background = Cockpit.Accent, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

/** No car yet: connecting adds it, or add it by hand. */
@Composable
internal fun NoCar(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 32.dp).widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(112.dp).border(1.5.dp, Cockpit.Accent.copy(alpha = 0.35f), CircleShape).padding(10.dp)
                    .clip(CircleShape).background(Cockpit.Accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_profile), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.cars_empty_title), color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.cars_empty_hint), color = Cockpit.Muted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PillButton(stringResource(R.string.cars_add), onClick = onAdd, icon = R.drawable.ic_add, color = Cockpit.OnAccent, background = Cockpit.Accent)
        }
    }
}

/** One car: shown marker, name (+ "Shown" badge), series and VIN, profiles and ECUs; tap shows it, ⋮ for the rest. */
@Composable
private fun CarRow(r: CarRowInfo, busy: Boolean, actions: CarActions) {
    val c = r.car
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
            .background(if (r.selected) Cockpit.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(role = Role.Button) { actions.onSelect(c.id) }
            .semantics { selected = r.selected }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(if (r.selected) Cockpit.Accent else Cockpit.Border, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.label, color = Cockpit.Text, fontSize = 18.sp, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (r.selected) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.cars_selected), color = Cockpit.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Cockpit.Accent.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(carSubtitle(c), color = Cockpit.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ecus = c.sgbds().size
            Text(
                pluralStringResource(R.plurals.cars_profiles, r.profiles, r.profiles) + " · " +
                    (if (ecus == 0) stringResource(R.string.cars_no_ecus) else pluralStringResource(R.plurals.cars_ecus, ecus, ecus)),
                color = Cockpit.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            RoundIconButton(R.drawable.ic_more, stringResource(R.string.cars_actions, c.label), onClick = { menu = true }, background = Color.Transparent)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Cockpit.Card) {
                if (!r.selected) CarMenuItem(R.drawable.ic_check, stringResource(R.string.cars_select)) { menu = false; actions.onSelect(c.id) }
                CarMenuItem(R.drawable.ic_plug, stringResource(R.string.cars_detect), enabled = !busy) { menu = false; actions.onDetect(c.id) }
                CarMenuItem(R.drawable.ic_faults, stringResource(R.string.cars_ecus_action)) { menu = false; actions.onEcus(c.id) }
                CarMenuItem(R.drawable.ic_edit, stringResource(R.string.cars_rename)) { menu = false; actions.onRename(c.id) }
                CarMenuItem(R.drawable.ic_delete, stringResource(R.string.cars_delete), color = Cockpit.Danger) { menu = false; actions.onDelete(c.id) }
            }
        }
    }
}

@Composable
private fun CarMenuItem(icon: Int, text: String, enabled: Boolean = true, color: Color = Cockpit.Text, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = if (enabled) color else Cockpit.Muted, fontSize = 15.sp) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) },
        onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = MinTouch),
    )
}

/** A car by hand: name (required), series and model. */
@Composable
internal fun AddCarDialog(onDismiss: () -> Unit, onAdd: (label: String, series: String, model: String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var series by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Cockpit.Card,
        title = { Text(stringResource(R.string.cars_add), color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it.take(Cars.MAX_LABEL_LENGTH); error = false }, singleLine = true,
                    label = { Text(stringResource(R.string.cars_label)) },
                    placeholder = { Text(stringResource(R.string.cars_label_placeholder), color = Cockpit.Muted) },
                    isError = error, supportingText = if (error) ({ Text(stringResource(R.string.cars_label_required)) }) else null,
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = series, onValueChange = { series = it.take(Cars.MAX_FIELD_LENGTH) }, singleLine = true,
                        label = { Text(stringResource(R.string.cars_series)) }, modifier = Modifier.weight(1f), colors = fieldColors(),
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it.take(Cars.MAX_FIELD_LENGTH) }, singleLine = true,
                        label = { Text(stringResource(R.string.cars_model)) }, modifier = Modifier.weight(1f), colors = fieldColors(),
                    )
                }
                Text(stringResource(R.string.cars_add_hint), color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (label.isBlank()) error = true else onAdd(label, series, model) }, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.cars_add), color = Cockpit.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.cancel), color = Cockpit.Muted) }
        },
    )
}

/**
 * The ECU list of car [car]: those of the last detection (fixed), those added by hand (tap to
 * remove; one still used by profiles is asked about first), and the ECU folder to add more.
 */
@Composable
internal fun CarEcusSheet(car: Vehicle, loadChoices: suspend () -> SgbdChoices, snackbar: SnackbarHostState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val book = remember { CarBook.get(context) }
    val scope = rememberCoroutineScope()
    val loaded by produceState<SgbdChoices?>(null) { value = loadChoices() }
    var asking by remember { mutableStateOf<Pair<String, List<EcuUse>>?>(null) }
    val scanned = car.ecus.mapNotNull { it.sgbd }.distinct()
    val choices = loaded?.let { c -> SgbdChoices(emptyList(), c.all.filter { it.name !in car.sgbds() }) }

    CockpitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            SheetHeader(stringResource(R.string.cars_ecus_title, car.label))
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (car.sgbds().isEmpty()) Text(stringResource(R.string.cars_ecus_none), color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                if (scanned.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.cars_ecus_scanned))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        scanned.forEach { e -> Chip(e.uppercase(), selected = false, onClick = {}) }
                    }
                }
                val added = car.addedSgbds.filter { it !in scanned }
                if (added.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.cars_ecus_added))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        added.forEach { e ->
                            Chip(e.uppercase(), selected = true, removeLabel = stringResource(R.string.cars_ecu_chip_remove, e.uppercase()), onClick = {
                                scope.launch {
                                    val uses = withContext(Dispatchers.IO) { book.ecuUses(car.id, e) }
                                    if (uses.isEmpty()) withContext(Dispatchers.IO) { book.update(car.id, sgbds = car.sgbds() - e) }
                                    else asking = e to uses
                                }
                            })
                        }
                    }
                }
                Text(stringResource(R.string.cars_ecus_hint), color = Cockpit.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                SgbdStep(choices) { picked -> scope.io { book.update(car.id, sgbds = car.sgbds() + picked) } }
            }
        }
    }
    asking?.let { (sgbd, uses) ->
        EcuInUseDialog(
            sgbd, uses, byDetection = false,
            onRemove = {
                asking = null
                scope.launch { removeWithUndo(book, car.id, sgbd, uses.size, snackbar, res) }
            },
            onKeep = { asking = null },
        )
    }
}

/** [CarBook.removeEcu], then one snackbar whose Undo puts the car and its profiles back. */
private suspend fun removeWithUndo(book: CarBook, carId: String, sgbd: String, profiles: Int, snackbar: SnackbarHostState, res: android.content.res.Resources) {
    val before = withContext(Dispatchers.IO) { book.removeEcu(carId, sgbd) }
    snackbar.currentSnackbarData?.dismiss()
    val r = snackbar.showSnackbar(
        res.getQuantityString(R.plurals.cars_ecu_removed, profiles, profiles, sgbd.uppercase()),
        actionLabel = res.getString(R.string.undo), duration = SnackbarDuration.Long,
    )
    if (r == SnackbarResult.ActionPerformed) withContext(Dispatchers.IO) { book.restoreCar(before, carId) }
}

/**
 * An ECU leaves the car's list ([byDetection]: the detection did not find it; else the user removed
 * it) while profiles use it: lists them with their cards and asks whether to take it out of them.
 */
@Composable
internal fun EcuInUseDialog(sgbd: String, uses: List<EcuUse>, byDetection: Boolean, onRemove: () -> Unit, onKeep: () -> Unit) {
    val name = sgbd.uppercase()
    AlertDialog(
        onDismissRequest = onKeep, containerColor = Cockpit.Card,
        title = { Text(name, color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp) },
        text = { EcuInUseText(sgbd, uses, byDetection) },
        confirmButton = {
            TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.cars_ecu_remove), color = Cockpit.Danger, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = MinTouch)) { Text(stringResource(R.string.cars_ecu_keep), color = Cockpit.Text) }
        },
    )
}

/** Body of [EcuInUseDialog]: why, which profiles with how many cards, the question. */
@Composable
internal fun EcuInUseText(sgbd: String, uses: List<EcuUse>, byDetection: Boolean) {
    val name = sgbd.uppercase()
    val cards = uses.sumOf { it.cards }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(if (byDetection) R.string.cars_ecu_lost_scan else R.string.cars_ecu_lost_hand, name) + " " +
                pluralStringResource(R.plurals.cars_ecu_used, uses.size, uses.size, pluralStringResource(R.plurals.cars_cards, cards, cards)),
            color = Cockpit.Text, fontSize = 15.sp, lineHeight = 21.sp,
        )
        uses.forEach { u ->
            Text("• ${u.profile.name} · " + pluralStringResource(R.plurals.cars_cards, u.cards, u.cards), color = Cockpit.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(R.string.cars_ecu_remove_question, name), color = Cockpit.Text, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

/**
 * The question left by the last detection ([VehicleState.missingEcus]), one ECU at a time: "Keep"
 * leaves it in the car's list as added by hand (it already is), "Remove" takes it out of the car and
 * of its profiles, with Undo in [snackbar]. Shown by My cars and the Faults tab.
 */
@Composable
internal fun MissingEcusQuestion(state: VehicleState, snackbar: SnackbarHostState) {
    val missing = state.missingEcus ?: return
    val context = LocalContext.current
    val res = LocalResources.current
    val book = remember { CarBook.get(context) }
    val repo = remember { VehicleRepository.get(context) }
    val scope = rememberCoroutineScope()
    val sgbd = missing.sgbds.first()
    val uses by produceState<List<EcuUse>?>(null, missing) { value = withContext(Dispatchers.IO) { book.ecuUses(missing.carId, sgbd) } }
    val u = uses ?: return
    if (u.isEmpty()) {
        // no profile uses it any more (changed meanwhile): nothing to ask
        LaunchedEffect(missing) { repo.dismissMissing() }
        return
    }
    EcuInUseDialog(
        sgbd, u, byDetection = true,
        onRemove = {
            repo.dismissMissing()
            scope.launch { removeWithUndo(book, missing.carId, sgbd, u.size, snackbar, res) }
        },
        onKeep = repo::dismissMissing,
    )
}
