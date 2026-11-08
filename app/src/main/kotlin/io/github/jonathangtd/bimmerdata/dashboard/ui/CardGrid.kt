/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.CardSpecs
import io.github.jonathangtd.bimmerdata.dashboard.GridRules
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeCard
import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.GaugeRenderer
import io.github.jonathangtd.bimmerdata.gauges.GaugeState
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class DropPreview(val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int, val valid: Boolean)

/** Callbacks of the grid editor; moves and resizes return false when refused. */
class GridActions(
    val onMove: (cardId: String, col: Int, row: Int) -> Boolean,
    val onResize: (cardId: String, colSpan: Int, rowSpan: Int) -> Boolean,
    val onCardTap: (cardId: String) -> Unit,
    val onEmptyTap: (col: Int, row: Int) -> Unit,
    val onLongPress: () -> Unit,
    /** Double tap outside the editor (trend cards: next time window). */
    val onCardDoubleTap: ((cardId: String) -> Unit)? = null,
)

/**
 * Cards of one page on a grid that fills the available space. In [editing] mode cards can be
 * dragged (snap to cells, swap with a card of the same place), resized from their bottom-right
 * corner, tapped to edit; empty cells show a "+" to add a card there.
 */
@Composable
fun CardGrid(
    page: Page,
    renderer: GaugeRenderer,
    feed: GaugeFeed,
    frameClock: State<Long>,
    index: Map<String, Measurement>,
    editing: Boolean,
    gapPx: Float,
    cornerRadiusDp: Float,
    actions: GridActions,
    addLabel: String,
    modifier: Modifier = Modifier,
) {
    val states = remember(page.id) { HashMap<String, GaugeState>() }
    var drop by remember { mutableStateOf<DropPreview?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val cellW = (widthPx - gapPx * (page.columns - 1)) / page.columns
        val cellH = (heightPx - gapPx * (page.rows - 1)) / page.rows
        if (cellW <= 0f || cellH <= 0f) return@BoxWithConstraints
        val corner = with(density) { cornerRadiusDp.dp.toPx() }

        // Empty cells (edit mode): dashed outline + "+".
        if (editing) {
            for (row in 0 until page.rows) for (col in 0 until page.columns) {
                if (!GridRules.isFree(page, col, row)) continue
                val x = col * (cellW + gapPx)
                val y = row * (cellH + gapPx)
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() })
                        .pointerInput(col, row) { detectTapGestures(onTap = { actions.onEmptyTap(col, row) }) }
                        .semantics { contentDescription = addLabel; onClick { actions.onEmptyTap(col, row); true } },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val inset = 1.dp.toPx()
                        drawRoundRect(
                            color = Cockpit.Border, topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                            cornerRadius = CornerRadius(corner.coerceAtMost(size.minDimension / 4)),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                        )
                    }
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = Cockpit.Muted, modifier = Modifier.size(22.dp))
                }
            }
        }

        // Drop target highlight while dragging / resizing.
        drop?.let { p ->
            val x = p.col * (cellW + gapPx)
            val y = p.row * (cellH + gapPx)
            val w = p.colSpan * cellW + (p.colSpan - 1) * gapPx
            val h = p.rowSpan * cellH + (p.rowSpan - 1) * gapPx
            Canvas(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() }),
            ) {
                val color = if (p.valid) Cockpit.Accent else Cockpit.Danger
                val cr = CornerRadius(corner.coerceAtMost(size.minDimension / 4))
                drawRoundRect(color = color.copy(alpha = 0.10f), cornerRadius = cr)
                drawRoundRect(color = color.copy(alpha = 0.8f), cornerRadius = cr, style = Stroke(width = 2.dp.toPx()))
            }
        }

        for (card in page.cards) {
            key(card.id) {
                val spec = remember(card, index, renderer.format) { CardSpecs.build(card, index, renderer.format) }
                val state = states.getOrPut(card.id) { GaugeState() }
                GridCard(
                    card = card, page = page, cellW = cellW, cellH = cellH, gapPx = gapPx, editing = editing, cornerPx = corner,
                    actions = actions, onDrop = { drop = it },
                ) {
                    GaugeCard(spec, state, renderer, feed, frameClock, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun GridCard(
    card: Card,
    page: Page,
    cellW: Float,
    cellH: Float,
    gapPx: Float,
    editing: Boolean,
    cornerPx: Float,
    actions: GridActions,
    onDrop: (DropPreview?) -> Unit,
    content: @Composable () -> Unit,
) {
    val targetX = card.col * (cellW + gapPx)
    val targetY = card.row * (cellH + gapPx)
    val targetW = card.colSpan * cellW + (card.colSpan - 1) * gapPx
    val targetH = card.rowSpan * cellH + (card.rowSpan - 1) * gapPx

    val pos = remember(card.id) { Animatable(Offset(targetX, targetY), Offset.VectorConverter) }
    val size = remember(card.id) { Animatable(Size(targetW, targetH), Size.VectorConverter) }
    var dragging by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragSize by remember { mutableStateOf(Size.Zero) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentPage by rememberUpdatedState(page)
    val currentActions by rememberUpdatedState(actions)
    val currentCard by rememberUpdatedState(card)

    val settle = spring<Offset>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    val settleSize = spring<Size>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    LaunchedEffect(targetX, targetY, dragging) { if (!dragging) pos.animateTo(Offset(targetX, targetY), settle) }
    LaunchedEffect(targetW, targetH, resizing) { if (!resizing) size.animateTo(Size(targetW, targetH), settleSize) }

    fun cellOf(p: Offset): Pair<Int, Int> {
        val c = currentCard
        val col = (p.x / (cellW + gapPx)).roundToInt().coerceIn(0, (currentPage.columns - c.colSpan).coerceAtLeast(0))
        val row = (p.y / (cellH + gapPx)).roundToInt().coerceIn(0, (currentPage.rows - c.rowSpan).coerceAtLeast(0))
        return col to row
    }

    fun spanOf(s: Size): Pair<Int, Int> {
        val c = currentCard
        val cs = ((s.width + gapPx) / (cellW + gapPx)).roundToInt().coerceIn(1, (currentPage.columns - c.col).coerceAtLeast(1))
        val rs = ((s.height + gapPx) / (cellH + gapPx)).roundToInt().coerceIn(1, (currentPage.rows - c.row).coerceAtLeast(1))
        return cs to rs
    }

    Box(
        Modifier
            .zIndex(if (dragging || resizing) 2f else 1f)
            .offset {
                val p = if (dragging) dragPos else pos.value
                IntOffset(p.x.roundToInt(), p.y.roundToInt())
            }
            .layout { measurable, _ ->
                val s = if (resizing) dragSize else size.value
                val w = s.width.roundToInt().coerceAtLeast(1)
                val h = s.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val lifted = dragging
                scaleX = if (lifted) 1.03f else 1f
                scaleY = if (lifted) 1.03f else 1f
            }
            .pointerInput(card.id, editing, card.type) {
                detectTapGestures(
                    onDoubleTap = if (!editing && card.type == GaugeType.Trend && actions.onCardDoubleTap != null) {
                        { _ -> currentActions.onCardDoubleTap?.invoke(currentCard.id) }
                    } else {
                        null
                    },
                    onTap = { if (editing) currentActions.onCardTap(currentCard.id) },
                    onLongPress = {
                        if (!editing) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentActions.onLongPress()
                        }
                    },
                )
            }
            .then(
                if (!editing) Modifier else Modifier.pointerInput(card.id, cellW, cellH, gapPx) {
                    detectDragGestures(
                        onDragStart = {
                            dragPos = pos.value
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragPos += amount
                            val (c, r) = cellOf(dragPos)
                            val cc = currentCard
                            onDrop(DropPreview(c, r, cc.colSpan, cc.rowSpan, GridRules.canMove(currentPage, cc.id, c, r)))
                        },
                        onDragEnd = {
                            val (c, r) = cellOf(dragPos)
                            onDrop(null)
                            scope.launch {
                                pos.snapTo(dragPos)
                                currentActions.onMove(currentCard.id, c, r)
                                dragging = false
                            }
                        },
                        onDragCancel = {
                            onDrop(null)
                            scope.launch {
                                pos.snapTo(dragPos)
                                dragging = false
                            }
                        },
                    )
                },
            ),
    ) {
        Box(Modifier.fillMaxSize().alpha(if (dragging) 0.92f else 1f)) { content() }
        if (editing) {
            val shape = RoundedCornerShape(with(LocalDensity.current) { cornerPx.toDp() })
            Box(Modifier.fillMaxSize().border(1.5.dp, Cockpit.Accent.copy(alpha = if (dragging || resizing) 0.9f else 0.45f), shape))
            ResizeHandle(
                modifier = Modifier.align(Alignment.BottomEnd),
                onStart = {
                    dragSize = size.value
                    resizing = true
                },
                onDrag = { amount ->
                    dragSize = Size(
                        (dragSize.width + amount.x).coerceAtLeast(cellW * 0.6f),
                        (dragSize.height + amount.y).coerceAtLeast(cellH * 0.6f),
                    )
                    val (cs, rs) = spanOf(dragSize)
                    val cc = currentCard
                    onDrop(DropPreview(cc.col, cc.row, cs, rs, GridRules.resize(currentPage, cc.id, cs, rs) != null))
                },
                onEnd = {
                    val (cs, rs) = spanOf(dragSize)
                    onDrop(null)
                    scope.launch {
                        size.snapTo(dragSize)
                        currentActions.onResize(currentCard.id, cs, rs)
                        resizing = false
                    }
                },
            )
        }
    }
}

/** 48 dp touch target in the card's bottom-right corner, drawn as a small corner bracket. */
@Composable
private fun ResizeHandle(modifier: Modifier, onStart: () -> Unit, onDrag: (Offset) -> Unit, onEnd: () -> Unit) {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    Canvas(
        modifier
            .size(MinTouch)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start() },
                    onDrag = { change, amount ->
                        change.consume()
                        drag(amount)
                    },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                )
            },
    ) {
        val s = 11.dp.toPx()
        val m = 9.dp.toPx()
        val w = 2.5.dp.toPx()
        val x = size.width - m
        val y = size.height - m
        drawLine(Cockpit.Accent, Offset(x - s, y), Offset(x, y), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(Cockpit.Accent, Offset(x, y - s), Offset(x, y), strokeWidth = w, cap = StrokeCap.Round)
    }
}
