/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** When an alert rule fires: the value is below or above its threshold. */
@Serializable
enum class AlertCondition { Below, Above }

/** How loud an alert is on the card: orange ([Warning]) or red ([Danger]); red wins. */
@Serializable
enum class AlertLevel { Warning, Danger }

/**
 * One alert rule of a card: when the value is [condition] [threshold] the card turns the
 * [level]'s color, and with [sound] a short tone plays when the alert starts. A card has at most
 * [MAX_PER_CARD] rules, each can be switched off ([enabled]) without losing it.
 */
@Serializable
data class CardAlert(
    val enabled: Boolean = true,
    val condition: AlertCondition,
    val threshold: Double,
    val level: AlertLevel = AlertLevel.Warning,
    val sound: Boolean = false,
) {
    companion object {
        const val MAX_PER_CARD = 4
    }
}

/**
 * The alert logic shared by the card drawing (phone, Android Auto, rendered PNG) and the alert
 * sounds ([io.github.jonathangtd.bimmerdata.alerts.AlertMonitor]), pure so both agree.
 *
 * State of one value = a bit mask of the rules active now (bit i = rule i of the card). A rule
 * starts as soon as the value passes its threshold, and stops only once the value is back past
 * the threshold by [margin] (hysteresis): a value hovering around the threshold does not flicker.
 * No value (NaN: no data, stale sample) ends every rule.
 */
object Alerts {

    /**
     * Hysteresis of a card: the bigger of one step of its last shown digit (10^-decimals) and 0.5 %
     * of its range ([min]..[max]).
     */
    fun margin(min: Double, max: Double, decimals: Int): Double {
        val step = 10.0.pow(-decimals.coerceIn(0, 6))
        val range = if (max > min && (max - min).isFinite()) (max - min) * 0.005 else 0.0
        return max(step, range)
    }

    /** Next mask of [alerts] for [value], from the previous [mask]. */
    fun step(alerts: List<CardAlert>, value: Double, mask: Int, margin: Double): Int {
        if (value.isNaN() || alerts.isEmpty()) return 0
        var next = 0
        val m = abs(margin)
        for (i in 0 until minOf(alerts.size, 31)) {
            val a = alerts[i]
            if (!a.enabled || !a.threshold.isFinite()) continue
            val was = mask and (1 shl i) != 0
            val on = when (a.condition) {
                AlertCondition.Below -> if (was) value < a.threshold + m else value < a.threshold
                AlertCondition.Above -> if (was) value > a.threshold - m else value > a.threshold
            }
            if (on) next = next or (1 shl i)
        }
        return next
    }

    /** Level shown for [mask]: [AlertLevel.Danger] if any active rule is red, else orange, null = none. */
    fun levelOf(alerts: List<CardAlert>, mask: Int): AlertLevel? {
        if (mask == 0) return null
        var level: AlertLevel? = null
        for (i in 0 until minOf(alerts.size, 31)) {
            if (mask and (1 shl i) == 0) continue
            if (alerts[i].level == AlertLevel.Danger) return AlertLevel.Danger
            level = AlertLevel.Warning
        }
        return level
    }

    /** Level of [value] without history (a single picture: rendered PNG, still images). */
    fun levelFor(alerts: List<CardAlert>, value: Double): AlertLevel? = levelOf(alerts, step(alerts, value, 0, 0.0))

    /** True when a rule with [CardAlert.sound] is active in [now] and was not in [before]. */
    fun soundStarts(alerts: List<CardAlert>, before: Int, now: Int): Boolean {
        val started = now and before.inv()
        if (started == 0) return false
        for (i in 0 until minOf(alerts.size, 31)) {
            if (started and (1 shl i) != 0 && alerts[i].sound) return true
        }
        return false
    }

    /** The stronger of two levels (red over orange over none). */
    fun max(a: AlertLevel?, b: AlertLevel?): AlertLevel? = when {
        a == AlertLevel.Danger || b == AlertLevel.Danger -> AlertLevel.Danger
        a != null || b != null -> AlertLevel.Warning
        else -> null
    }

    /**
     * Rules for the older amber thresholds of a card (`warnBelow` / `warnAbove`, before alert
     * rules existed) merged into [alerts]: each threshold replaces the first orange rule without
     * sound of the same condition, else is added (while there is room).
     */
    fun withLegacy(alerts: List<CardAlert>, warnBelow: Double?, warnAbove: Double?): List<CardAlert> {
        var out = alerts
        for ((cond, t) in listOf(AlertCondition.Below to warnBelow, AlertCondition.Above to warnAbove)) {
            if (t == null || !t.isFinite()) continue
            val rule = CardAlert(condition = cond, threshold = t)
            val i = out.indexOfFirst { it.condition == cond && it.level == AlertLevel.Warning && !it.sound }
            out = when {
                i >= 0 -> out.toMutableList().also { it[i] = rule }
                out.size < CardAlert.MAX_PER_CARD -> out + rule
                else -> out
            }
        }
        return out
    }

    /** Why [alerts] cannot be saved, or null: too many rules, threshold not a finite number. */
    fun problem(alerts: List<CardAlert>): String? = when {
        alerts.size > CardAlert.MAX_PER_CARD -> "at most ${CardAlert.MAX_PER_CARD} alert rules per card (${alerts.size} given)"
        alerts.any { !it.threshold.isFinite() } -> "an alert threshold must be a finite number"
        else -> null
    }
}

/**
 * When to play an alert tone: once when an alert with sound starts ([Alerts.soundStarts]), and
 * not more than once per [minIntervalMs] for the same card. Pure (the clock is passed in).
 */
class AlertSoundGate(private val minIntervalMs: Long = 10_000) {
    private val last = HashMap<String, Long>()

    /** True when the tone of card [key] should play now; records it. */
    fun shouldPlay(key: String, nowMs: Long): Boolean {
        val prev = last[key]
        if (prev != null && nowMs - prev < minIntervalMs) return false
        last[key] = nowMs
        return true
    }

    fun forget(keep: Set<String>) {
        last.keys.retainAll(keep)
    }
}
