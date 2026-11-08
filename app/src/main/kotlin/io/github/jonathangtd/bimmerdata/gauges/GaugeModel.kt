/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import android.graphics.Paint
import android.graphics.Path
import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.serialization.Serializable

@Serializable
enum class GaugeType {
    /** 270° arc with the value in the middle (oil, coolant, rpm). */
    Dial,

    /** Bars centered on 0, one row per measurement (fuel trims bank 1 / bank 2). */
    BipolarBar,

    /** Hero value with unit, label and the session min / max. */
    BigNumber,

    /** Value with a sparkline of the last [GaugeRenderer.TREND_WINDOW_MS]. */
    Trend,

    /** Two or three values side by side (lambda B1 · B2). */
    MultiValue,

    /** Text or state result. */
    Status,
}

/** One measurement shown by a card, already resolved to display strings. */
class GaugeChannel(
    val measurementId: String,
    /** Short label (e.g. "B1") used by multi-row cards. */
    val label: String,
    /** Display unit (see [GaugeFormat.displayUnit]). */
    val unit: String,
    val decimals: Int,
)

/** Immutable description of what a card draws; built by the dashboard from its layout + catalog. */
class GaugeSpec(
    val type: GaugeType,
    val title: String,
    /** Small secondary text (range of a bipolar card, …); null = none. */
    val caption: String?,
    val min: Double,
    val max: Double,
    val warnBelow: Double?,
    val warnAbove: Double?,
    val channels: List<GaugeChannel>,
) {
    fun isWarning(value: Double): Boolean =
        !value.isNaN() && ((warnBelow != null && value < warnBelow) || (warnAbove != null && value > warnAbove))
}

/** Where cards read live data from at draw time (no recomposition per sample). */
interface GaugeFeed {
    fun sample(id: String): Sample?
    fun history(id: String): ValueHistory?

    /** Wall clock used to detect stale samples and to place sparkline points. */
    val nowMs: Long

    /** A sample older than this is shown as "no data". */
    val staleAfterMs: Long get() = 5_000
}

/**
 * Per-card mutable drawing state: smoothed values, cached strings and reusable paths, so that a
 * frame allocates (almost) nothing. One instance per card on screen.
 */
class GaugeState {
    private var count = 0
    private var smooth = emptyArray<SmoothValue>()
    private var targets = DoubleArray(0)
    private var smoothed = DoubleArray(0)
    private var texts = arrayOfNulls<String>(0)
    private var cachedTarget = DoubleArray(0)
    private var cachedDecimals = IntArray(0)
    private var cachedValueText = arrayOfNulls<String>(0)

    var animating = false
        private set

    internal val path = Path()
    internal val fillPath = Path()

    fun target(i: Int): Double = if (i < count) targets[i] else Double.NaN
    fun smoothed(i: Int): Double = if (i < count) smoothed[i] else Double.NaN
    fun text(i: Int): String? = if (i < count) texts[i] else null
    fun hasData(i: Int): Boolean = !target(i).isNaN() || text(i) != null

    /** Reads the latest samples of [spec]'s channels and advances the smoothing to [frameNanos]. */
    fun sync(spec: GaugeSpec, feed: GaugeFeed, frameNanos: Long) {
        val n = spec.channels.size
        if (n != count) resize(n)
        val now = feed.nowMs
        var moving = false
        for (i in 0 until n) {
            val sample = feed.sample(spec.channels[i].measurementId)
            val fresh = sample != null && now - sample.timestampMs <= feed.staleAfterMs
            val target = if (fresh) sample.value ?: Double.NaN else Double.NaN
            targets[i] = target
            texts[i] = if (fresh) sample.text else null
            smoothed[i] = smooth[i].update(target, frameNanos)
            val epsilon = (spec.max - spec.min).let { if (it > 0) it * 0.0005 else 0.001 }
            if (!smooth[i].isSettled(target, epsilon)) moving = true
        }
        animating = moving
    }

    /** Formatted (target, not smoothed) value of channel [i], cached until it changes. */
    fun valueText(i: Int, format: GaugeFormat, decimals: Int, signed: Boolean = false): String {
        if (i >= count) return GaugeFormat.NO_DATA
        val t = targets[i]
        if (t.isNaN()) return texts[i] ?: GaugeFormat.NO_DATA
        val cached = cachedValueText[i]
        if (cached != null && cachedTarget[i] == t && cachedDecimals[i] == decimals + if (signed) 100 else 0) return cached
        val s = format.format(t, decimals, signed)
        cachedTarget[i] = t
        cachedDecimals[i] = decimals + if (signed) 100 else 0
        cachedValueText[i] = s
        return s
    }

    private fun resize(n: Int) {
        count = n
        smooth = Array(n) { SmoothValue() }
        targets = DoubleArray(n) { Double.NaN }
        smoothed = DoubleArray(n) { Double.NaN }
        texts = arrayOfNulls(n)
        cachedTarget = DoubleArray(n) { Double.NaN }
        cachedDecimals = IntArray(n)
        cachedValueText = arrayOfNulls(n)
    }

    // ---- ellipsized text cache, a few slots per card ----

    private val fitText = arrayOfNulls<String>(FIT_SLOTS)
    private val fitWidth = FloatArray(FIT_SLOTS)
    private val fitSize = FloatArray(FIT_SLOTS)
    private val fitResult = arrayOfNulls<String>(FIT_SLOTS)

    internal fun ellipsized(slot: Int, text: String, paint: Paint, maxWidth: Float): String {
        if (fitText[slot] === text && fitWidth[slot] == maxWidth && fitSize[slot] == paint.textSize) return fitResult[slot]!!
        val result = ellipsize(text, paint, maxWidth)
        fitText[slot] = text
        fitWidth[slot] = maxWidth
        fitSize[slot] = paint.textSize
        fitResult[slot] = result
        return result
    }

    companion object {
        internal const val FIT_SLOTS = 12

        internal fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
            if (maxWidth <= 0f) return ""
            if (paint.measureText(text) <= maxWidth) return text
            val ell = paint.measureText("…")
            var lo = 0
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (paint.measureText(text, 0, mid) + ell <= maxWidth) lo = mid else hi = mid - 1
            }
            return text.substring(0, lo).trimEnd() + "…"
        }
    }
}
