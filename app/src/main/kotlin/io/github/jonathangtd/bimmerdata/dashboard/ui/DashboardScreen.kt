/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.ui

import android.content.res.Resources
import android.content.Intent
import androidx.activity.compose.BackHandler
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import io.github.jonathangtd.bimmerdata.ui.ConnectionPillContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardViewModel
import io.github.jonathangtd.bimmerdata.dashboard.GridLimits
import io.github.jonathangtd.bimmerdata.dashboard.GridRules
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.dashboard.Target
import io.github.jonathangtd.bimmerdata.dashboard.car.CarPageRenderer
import io.github.jonathangtd.bimmerdata.dashboard.car.CarScreen
import io.github.jonathangtd.bimmerdata.dashboard.car.StatusLine
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.currentLocale
import io.github.jonathangtd.bimmerdata.gauges.rememberFrameClock
import io.github.jonathangtd.bimmerdata.gauges.rememberPhoneRenderer
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.ui.BarlowCondensed
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What is open on top of the dashboard. */
internal sealed interface Sheet {
    data class EditCard(val target: Target, val pageId: String, val cardId: String) : Sheet
    data class AddCard(val target: Target, val pageId: String, val col: Int?, val row: Int?) : Sheet
    data class Layout(val target: Target, val pageId: String) : Sheet
    data class Pages(val target: Target) : Sheet
    data object CarFullscreen : Sheet
    data class ConfirmReset(val target: Target) : Sheet
    data object RenameDashboard : Sheet
}

@Composable
fun DashboardScreen(onEditingChange: (Boolean) -> Unit = {}, vm: DashboardViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val index by vm.measurementIndex.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appSettings by remember { AppSettings.get(context) }.state.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val frame = rememberFrameClock()
    val renderer = rememberPhoneRenderer()
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    LaunchedEffect(ui.editing) { onEditingChange(ui.editing) }
    LifecycleStartEffect(vm) {
        vm.setForeground(true)
        onStopOrDispose { vm.setForeground(false) }
    }
    BackHandler(enabled = ui.editing) { vm.setEditing(false) }

    val dashboard = ui.dashboard
    if (dashboard == null) {
        Box(Modifier.fillMaxSize().background(Cockpit.Background))
        return
    }

    fun message(text: String, withUndo: Boolean = false) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = if (withUndo) res.getString(R.string.undo) else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.undo()
        }
    }

    // A layout written elsewhere (REST API...) during an edit: offer it (see DashboardSync).
    val external = ui.external
    LaunchedEffect(external) {
        if (external != null) {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                res.getString(R.string.external_change), actionLabel = res.getString(R.string.external_load),
                withDismissAction = true, duration = SnackbarDuration.Indefinite,
            )
            if (r == SnackbarResult.ActionPerformed) vm.acceptExternal()
        }
    }

    // ---- import / export ----
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = vm.exportJson()
        if (uri != null && json != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                .onSuccess { message(res.getString(R.string.export_ok)) }
                .onFailure { message(res.getString(R.string.import_failed, it.message.orEmpty())) }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            val error = if (text == null) "?" else vm.importJson(text)
            message(if (error == null) res.getString(R.string.import_ok) else res.getString(R.string.import_failed, error), withUndo = error == null)
        }
    }
    fun share() {
        val json = vm.exportJson() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, res.getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, json)
        }
        context.startActivity(Intent.createChooser(send, res.getString(R.string.share_layout)))
    }

    val target = if (ui.editing) ui.target else Target.Phone
    val pages = dashboard.pages(target)
    val pageIndex = ui.pageIndex(target).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val currentPage = pages.getOrNull(pageIndex)

    fun actionsFor(t: Target, page: Page) = GridActions(
        onMove = { id, c, r -> vm.moveCard(t, page.id, id, c, r) },
        onResize = { id, cs, rs ->
            vm.resizeCard(t, page.id, id, cs, rs).also { if (!it) message(res.getString(R.string.size_refused)) }
        },
        onCardTap = { id -> sheet = Sheet.EditCard(t, page.id, id) },
        onEmptyTap = { c, r -> sheet = addSheetOrMessage(res, t, page, c, r) { message(it) } ?: sheet },
        onLongPress = { vm.setEditing(true) },
        onCardDoubleTap = { id -> vm.cycleTrendWindow(t, page.id, id)?.let { message(res.getString(R.string.trend_window_now, it)) } },
    )

    /** "Add a card": on the current page of [t], or on a first page created on the fly (default grid). */
    fun addFirstOrNext(t: Target) {
        vm.setEditing(true)
        val page = vm.ensurePage(t, res.getString(R.string.new_page, 1)) ?: return
        sheet = addSheetOrMessage(res, t, page, null, null) { message(it) } ?: sheet
    }

    Box(Modifier.fillMaxSize().background(Cockpit.Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                // The bottom bar handles the navigation inset, except while editing (it is hidden then).
                .then(if (ui.editing) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            if (ui.editing) {
                EditorHeader(target = ui.target, onTarget = vm::setTarget, onDone = { vm.setEditing(false) })
            } else {
                DashboardHeader(
                    title = dashboard.title(stringResource(R.string.app_name)),
                    onRename = { sheet = Sheet.RenameDashboard },
                    status = status, rec = rec,
                    onRecord = vm::toggleRecording, onEdit = { vm.setEditing(true) },
                    connection = connection, lastMode = appSettings.lastMode, onConnect = vm::connect, onDisconnect = vm::disconnect,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (pages.isNotEmpty() || ui.editing) SegmentedTabs(
                labels = pages.map { it.name }, selected = pageIndex, onSelect = { vm.selectPage(target, it) },
                trailing = if (ui.editing) {
                    {
                        RoundIconButton(
                            R.drawable.ic_add, stringResource(R.string.add_page),
                            onClick = { vm.addPage(target, res.getString(R.string.new_page, pages.size + 1)) },
                            background = Color.Transparent, tint = Cockpit.Accent, size = 40.dp,
                        )
                    }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(12.dp))
            if (ui.editing) {
                EditorToolbar(
                    onLayout = {
                        vm.ensurePage(target, res.getString(R.string.new_page, 1))?.let { sheet = Sheet.Layout(target, it.id) }
                    },
                    onPages = { sheet = Sheet.Pages(target) },
                    onAddCard = { addFirstOrNext(target) },
                    onShare = ::share,
                    onSaveFile = { saveFile.launch("bimmerdata-layout.json") },
                    onImport = { openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onReset = { sheet = Sheet.ConfirmReset(target) },
                    onRename = { sheet = Sheet.RenameDashboard },
                )
                Text(
                    stringResource(R.string.edit_hint), color = Cockpit.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (pages.isEmpty()) {
                    // No page at all (first launch, or after a reset): add a card or a page.
                    EmptyPage(
                        onAdd = { addFirstOrNext(target) },
                        onAddPage = {
                            vm.setEditing(true)
                            vm.addPage(target, res.getString(R.string.new_page, 1))
                        },
                        car = target == Target.Car,
                    )
                } else if (target == Target.Phone) {
                    PhonePager(
                        pages = pages, pageIndex = pageIndex, editing = ui.editing,
                        onPageSettled = { vm.selectPage(Target.Phone, it) },
                    ) { page ->
                        if (page.cards.isEmpty() && !ui.editing) {
                            EmptyPage(onAdd = {
                                vm.setEditing(true)
                                sheet = addSheetOrMessage(res, Target.Phone, page, null, null) { message(it) } ?: sheet
                            })
                        } else {
                            CardGrid(
                                page = page, renderer = renderer, feed = vm.feed, frameClock = frame, index = index,
                                editing = ui.editing, gapPx = with(LocalDensity.current) { 12.dp.toPx() }, cornerRadiusDp = 20f,
                                actions = actionsFor(Target.Phone, page), addLabel = stringResource(R.string.add_card),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                } else if (currentPage != null) {
                    CarEditor(
                        page = currentPage, pageIndex = pageIndex, pageCount = pages.size, status = status, rec = rec,
                        vm = vm, index = index, frame = frame, actions = actionsFor(Target.Car, currentPage),
                        onFullscreen = { sheet = Sheet.CarFullscreen },
                    )
                }
            }

            if (rec.recording && !ui.editing) {
                Spacer(Modifier.height(12.dp))
                RecordingBar(rec, onMark = {
                    vm.mark()
                    message(res.getString(R.string.rec_marker_added))
                })
            }
            Spacer(Modifier.height(12.dp))
        }
        val snackbarBottom = if (rec.recording && !ui.editing) 84.dp else 16.dp
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(bottom = snackbarBottom),
        ) { data ->
            Snackbar(
                data, containerColor = Track, contentColor = Cockpit.Text, actionColor = Cockpit.Accent,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    when (val s = sheet) {
        null -> Unit
        is Sheet.EditCard -> {
            val page = dashboard.pages(s.target).firstOrNull { it.id == s.pageId }
            val card = page?.card(s.cardId)
            if (page == null || card == null) {
                sheet = null
            } else {
                CardEditorSheet(
                    card = card, page = page, target = s.target, vm = vm, renderer = renderer,index = index, frame = frame,
                    onDismiss = { sheet = null },
                    onSave = { updated ->
                        if (vm.updateCard(s.target, page.id, updated)) sheet = null else message(res.getString(R.string.size_refused))
                    },
                    onDelete = {
                        vm.deleteCard(s.target, page.id, card.id)
                        sheet = null
                        message(res.getString(R.string.card_deleted), withUndo = true)
                    },
                )
            }
        }
        is Sheet.AddCard -> {
            val page = dashboard.pages(s.target).firstOrNull { it.id == s.pageId }
            if (page == null) {
                sheet = null
            } else {
                MeasurementPickerSheet(
                    vm = vm, fixedType = null, initialIds = emptyList(), renderer = renderer, onDismiss = { sheet = null },
                    onConfirm = { type, ids ->
                        val card = newCard(type, ids, s.target, page, s.col, s.row)
                        if (!vm.addCard(s.target, page.id, card)) {
                            message(res.getString(if (s.target == Target.Car && page.cards.size >= GridLimits.Car.maxCards) R.string.car_max_cards else R.string.page_full))
                        }
                        sheet = null
                    },
                )
            }
        }
        is Sheet.Layout -> {
            val page = dashboard.pages(s.target).firstOrNull { it.id == s.pageId }
            if (page == null) {
                sheet = null
            } else {
                LayoutSheet(
                    page = page, target = s.target, onDismiss = { sheet = null },
                    onGrid = { c, r -> vm.setGrid(s.target, page.id, c, r).also { if (!it) message(res.getString(R.string.grid_refused)) } },
                    onPreset = { preset ->
                        vm.applyPreset(s.target, page.id, preset)
                        sheet = null
                    },
                )
            }
        }
        is Sheet.Pages -> PagesSheet(
            pages = dashboard.pages(s.target), onDismiss = { sheet = null },
            onRename = { id, name -> vm.renamePage(s.target, id, name) },
            onMove = { from, to -> vm.movePage(s.target, from, to) },
            onDelete = { id ->
                vm.deletePage(s.target, id)
                message(res.getString(R.string.page_deleted), withUndo = true)
            },
            onAdd = { vm.addPage(s.target, res.getString(R.string.new_page, dashboard.pages(s.target).size + 1)) },
        )
        Sheet.CarFullscreen -> CarFullscreenPreview(
            pages = dashboard.carPages, initialIndex = ui.carPage, vm = vm, index = index, status = status, rec = rec,
            frame = frame, onDismiss = { sheet = null },
        )
        is Sheet.ConfirmReset -> ConfirmDialog(
            text = stringResource(if (s.target == Target.Phone) R.string.reset_confirm_phone else R.string.reset_confirm_car),
            confirm = stringResource(R.string.reset_default),
            onConfirm = {
                vm.resetTarget(s.target)
                sheet = null
                message(res.getString(R.string.reset_default), withUndo = true)
            },
            onDismiss = { sheet = null },
        )
        Sheet.RenameDashboard -> TextInputDialog(
            title = stringResource(R.string.dashboard_name), initial = dashboard.name.orEmpty(),
            placeholder = stringResource(R.string.app_name), maxLength = Dashboard.MAX_NAME_LENGTH,
            hint = stringResource(R.string.dashboard_name_hint),
            onConfirm = {
                vm.renameDashboard(it)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
    }
}

/** Opens the add-card flow if the page has room, else reports why. */
private fun addSheetOrMessage(res: Resources, t: Target, page: Page, col: Int?, row: Int?, message: (String) -> Unit): Sheet? {
    if (page.cards.size >= GridLimits.of(t).maxCards) {
        message(res.getString(R.string.car_max_cards))
        return null
    }
    if (GridRules.firstFreeSlot(page, 1, 1) == null) {
        message(res.getString(R.string.page_full))
        return null
    }
    return Sheet.AddCard(t, page.id, col, row)
}

/**
 * Title, recording and edit buttons, then the connection pill ([ConnectionPillContent]): a big
 * "Connect" when disconnected, the colored state with the read rate when connected.
 */
@Composable
internal fun DashboardHeader(
    title: String, onRename: () -> Unit, status: AcquisitionStatus, rec: RecordingState,
    onRecord: () -> Unit, onEdit: () -> Unit,
    connection: LiveConnection, lastMode: LiveMode?, onConnect: (LiveMode) -> Unit, onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val renameLabel = stringResource(R.string.dashboard_rename)
            Text(
                title, color = Cockpit.Text, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = renameLabel, onClick = onRename),
            )
            RecordPill(rec, onRecord)
            RoundIconButton(R.drawable.ic_edit, stringResource(R.string.edit), onClick = onEdit)
        }
        ConnectionPillContent(connection, status, lastMode, onConnect, onDisconnect, Modifier.fillMaxWidth())
    }
}

@Composable
private fun RecordPill(rec: RecordingState, onClick: () -> Unit) {
    val elapsed by produceState(0L, rec.recording, rec.startedAtMs) {
        while (rec.recording) {
            value = System.currentTimeMillis() - (rec.startedAtMs ?: System.currentTimeMillis())
            delay(250)
        }
    }
    val description = stringResource(if (rec.recording) R.string.rec_stop else R.string.rec_start)
    Row(
        Modifier
            .height(MinTouch)
            .clip(RoundedCornerShape(24.dp))
            .background(if (rec.recording) RecordBg else Cockpit.Card)
            .border(1.dp, if (rec.recording) RecordBorder else Cockpit.Border, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(RecordRed, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            if (rec.recording) StatusLine.elapsed(elapsed) else stringResource(R.string.rec_short),
            color = if (rec.recording) RecordText else Cockpit.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RecordingBar(rec: RecordingState, onMark: () -> Unit) {
    Surface(color = Cockpit.Card, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rec_label), color = Cockpit.Muted, fontSize = 12.sp)
                Text(rec.fileName.orEmpty(), color = Cockpit.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PillButton(stringResource(R.string.rec_marker), onClick = onMark, icon = R.drawable.ic_flag)
        }
    }
}

@Composable
private fun EditorHeader(target: Target, onTarget: (Target) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentedTabs(
            labels = listOf(stringResource(R.string.target_phone), stringResource(R.string.target_car)),
            selected = if (target == Target.Phone) 0 else 1,
            onSelect = { onTarget(if (it == 0) Target.Phone else Target.Car) },
            modifier = Modifier.weight(1f),
        )
        PillButton(stringResource(R.string.done), onClick = onDone, icon = R.drawable.ic_check, color = Cockpit.OnAccent, background = Cockpit.Accent)
    }
}

@Composable
private fun EditorToolbar(
    onLayout: () -> Unit, onPages: () -> Unit, onAddCard: () -> Unit,
    onShare: () -> Unit, onSaveFile: () -> Unit, onImport: () -> Unit, onReset: () -> Unit,
    onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(stringResource(R.string.add_card_short), onClick = onAddCard, icon = R.drawable.ic_add, color = Cockpit.Accent, modifier = Modifier.weight(1f))
        PillButton(stringResource(R.string.layout), onClick = onLayout, icon = R.drawable.ic_grid, modifier = Modifier.weight(1f))
        PillButton(stringResource(R.string.pages), onClick = onPages, icon = R.drawable.ic_pages, modifier = Modifier.weight(1f))
        Box {
            RoundIconButton(R.drawable.ic_more, stringResource(R.string.more), onClick = { menu = true }, background = Track)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Cockpit.Card) {
                MenuItem(R.drawable.ic_edit, stringResource(R.string.dashboard_rename)) { menu = false; onRename() }
                MenuItem(R.drawable.ic_share, stringResource(R.string.share_layout)) { menu = false; onShare() }
                MenuItem(R.drawable.ic_download, stringResource(R.string.export_file)) { menu = false; onSaveFile() }
                MenuItem(R.drawable.ic_upload, stringResource(R.string.import_file)) { menu = false; onImport() }
                MenuItem(R.drawable.ic_reset, stringResource(R.string.reset_default)) { menu = false; onReset() }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: Int, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = Cockpit.Text, fontSize = 15.sp) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = MinTouch),
    )
}

/** A page without cards: one big "+" that opens the card picker, and what can be shown. */
@Composable
internal fun EmptyPage(onAdd: () -> Unit, onAddPage: (() -> Unit)? = null, car: Boolean = false) {
    val addLabel = stringResource(R.string.empty_title)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 32.dp).widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(112.dp)
                    .border(1.5.dp, Cockpit.Accent.copy(alpha = 0.35f), CircleShape)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Cockpit.Accent.copy(alpha = 0.14f))
                    .clickable(role = Role.Button, onClickLabel = addLabel, onClick = onAdd)
                    .semantics { contentDescription = addLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = Cockpit.Accent, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                addLabel, color = Cockpit.Text, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (car) R.string.empty_hint_car else R.string.empty_hint), color = Cockpit.Muted, fontSize = 15.sp, lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PillButton(
                stringResource(R.string.add_card), onClick = onAdd, icon = R.drawable.ic_add,
                color = Cockpit.OnAccent, background = Cockpit.Accent,
            )
            if (onAddPage != null) {
                Spacer(Modifier.height(12.dp))
                PillButton(stringResource(R.string.add_page), onClick = onAddPage, icon = R.drawable.ic_pages, color = Cockpit.Accent)
            }
        }
    }
}

@Composable
private fun PhonePager(
    pages: List<Page>, pageIndex: Int, editing: Boolean, onPageSettled: (Int) -> Unit,
    content: @Composable (Page) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = pageIndex) { pages.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onPageSettled(it) }
    }
    LaunchedEffect(pageIndex, pages.size) {
        if (pageIndex in pages.indices && pagerState.currentPage != pageIndex) pagerState.animateScrollToPage(pageIndex)
    }
    HorizontalPager(
        state = pagerState, modifier = Modifier.fillMaxSize(), pageSpacing = 16.dp, userScrollEnabled = !editing,
        key = { i -> pages.getOrNull(i)?.id ?: i },
    ) { i ->
        pages.getOrNull(i)?.let { content(it) }
    }
}

/** Android Auto pages edited on a landscape preview of the car screen. */
@Composable
private fun CarEditor(
    page: Page, pageIndex: Int, pageCount: Int, status: AcquisitionStatus, rec: RecordingState,
    vm: DashboardViewModel, index: Map<String, io.github.jonathangtd.bimmerdata.live.Measurement>,
    frame: androidx.compose.runtime.State<Long>, actions: GridActions, onFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    val locale = currentLocale()
    val density = LocalDensity.current
    val car = remember(locale) { CarPageRenderer(context, locale) }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = widthPx * CarPageRenderer.REFERENCE_HEIGHT / CarPageRenderer.REFERENCE_WIDTH
            car.layout(0f, 0f, widthPx, heightPx)
            val area = car.gridArea
            val nowMs by produceState(System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(500)
                }
            }
            val chrome = CarScreen.chrome(context, page, pageIndex, pageCount, status, rec, nowMs, car.gauges.format)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { heightPx.toDp() })
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { car.drawChrome(it.nativeCanvas, 0f, 0f, size.width, size.height, chrome) }
                }
                CardGrid(
                    page = page, renderer = car.gauges, feed = vm.feed, frameClock = frame, index = index, editing = true,
                    gapPx = car.cardGap, cornerRadiusDp = 26f * heightPx / CarPageRenderer.REFERENCE_HEIGHT / density.density,
                    actions = actions, addLabel = stringResource(R.string.add_card),
                    modifier = Modifier
                        .offset { IntOffset(area.left.toInt(), area.top.toInt()) }
                        .width(with(density) { area.width().toDp() })
                        .height(with(density) { area.height().toDp() }),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.fullscreen_preview), onClick = onFullscreen, icon = R.drawable.ic_fullscreen)
        Spacer(Modifier.height(16.dp))
        Surface(color = Cockpit.Card, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Cockpit.Border)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.car_preview_hint), color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                Text(stringResource(R.string.car_limits_hint), color = Cockpit.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}
