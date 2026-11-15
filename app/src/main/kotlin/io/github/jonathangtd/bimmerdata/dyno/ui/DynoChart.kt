/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dyno.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jonathangtd.bimmerdata.live.dyno.DynoPoint
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** One run drawn: [dim] for the second run of a comparison (thinner, paler). */
internal class ChartRun(val points: List<DynoPoint>, val dim: Boolean)

internal val PowerColor = Cockpit.Accent
internal val TorqueColor = Cockpit.Warning

/** A "nice" step for about [count] grid lines over [span]. */
private fun niceStep(span: Double, count: Int): Double {
    val raw = span / count
    val mag = Math.pow(10.0, floor(Math.log10(raw)))
    val n = raw / mag
    return mag * when {
        n <= 1 -> 1.0
        n <= 2 -> 2.0
        n <= 2.5 -> 2.5
        n <= 5 -> 5.0
        else -> 10.0
    }
}

/**
 * Power (ch, left axis, blue) and torque (Nm, right axis, amber) against engine speed. The car's
 * own value (the overlay) is dashed in the same colors; the second run of a comparison is thinner
 * and paler. [description] is read by TalkBack.
 */
@Composable
internal fun DynoChart(runs: List<ChartRun>, description: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = Cockpit.Muted, fontSize = 11.sp)
    Canvas(modifier.fillMaxWidth().height(260.dp).semantics { contentDescription = description }) {
        val all = runs.flatMap { it.points }
        if (all.isEmpty()) return@Canvas
        val maxP = max(1.0, all.maxOf { max(it.wheelPowerCh, it.overlayPowerCh ?: 0.0) })
        val maxT = max(1.0, all.maxOf { max(it.torqueNm, it.overlayTorqueNm ?: 0.0) })
        val pStep = niceStep(maxP, 5)
        val pTop = ceil(maxP * 1.05 / pStep) * pStep
        val steps = (pTop / pStep).toInt()
        // the torque axis shares the power grid lines, with round values too
        val tTop = niceStep(maxT * 1.05 / steps, 1) * steps
        val rStep = niceStep(all.maxOf { it.rpm } - all.minOf { it.rpm }, 6).coerceAtLeast(250.0)
        val rLo = floor(all.minOf { it.rpm } / rStep) * rStep
        val rHi = max(rLo + rStep, ceil(all.maxOf { it.rpm } / rStep) * rStep)

        val left = 40.dp.toPx()
        val right = size.width - 40.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        fun x(rpm: Double) = (left + (rpm - rLo) / (rHi - rLo) * (right - left)).toFloat()
        fun yP(v: Double) = (bottom - v / pTop * (bottom - top)).toFloat()
        fun yT(v: Double) = (bottom - v / tTop * (bottom - top)).toFloat()

        // grid and axis labels
        for (i in 0..steps) {
            val y = (bottom - i.toFloat() / steps * (bottom - top))
            drawLine(Cockpit.Border, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            val pText = measurer.measure((pStep * i).toInt().toString(), label.copy(color = PowerColor))
            drawText(pText, topLeft = Offset(left - pText.size.width - 6.dp.toPx(), y - pText.size.height / 2f))
            val tText = measurer.measure((tTop / steps * i).toInt().toString(), label.copy(color = TorqueColor))
            drawText(tText, topLeft = Offset(right + 6.dp.toPx(), y - tText.size.height / 2f))
        }
        var r = rLo
        while (r <= rHi + 1) {
            val xx = x(r)
            drawLine(Cockpit.Border.copy(alpha = 0.6f), Offset(xx, top), Offset(xx, bottom), strokeWidth = 1f)
            val t = measurer.measure((r / 1000).let { if (it == Math.rint(it)) it.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", it) }, label)
            drawText(t, topLeft = Offset(xx - t.size.width / 2f, bottom + 4.dp.toPx()))
            r += rStep
        }

        for (run in runs.sortedBy { !it.dim }) {
            val w = if (run.dim) 1.5.dp.toPx() else 2.5.dp.toPx()
            val a = if (run.dim) 0.55f else 1f
            curve(run.points.map { x(it.rpm) to yP(it.wheelPowerCh) }, PowerColor.copy(alpha = a), w, null)
            curve(run.points.map { x(it.rpm) to yT(it.torqueNm) }, TorqueColor.copy(alpha = a), w, null)
            val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
            curve(run.points.mapNotNull { p -> p.overlayPowerCh?.let { x(p.rpm) to yP(it) } }, PowerColor.copy(alpha = a * 0.8f), w * 0.8f, dash)
            curve(run.points.mapNotNull { p -> p.overlayTorqueNm?.let { x(p.rpm) to yT(it) } }, TorqueColor.copy(alpha = a * 0.8f), w * 0.8f, dash)
        }
    }
}

private fun DrawScope.curve(points: List<Pair<Float, Float>>, color: Color, width: Float, effect: PathEffect?) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    drawPath(path, color, style = Stroke(width = width, pathEffect = effect))
}
