/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.alerts

import android.content.Context
import android.util.Log
import io.github.jonathangtd.bimmerdata.dashboard.CardSpecs
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardStore
import io.github.jonathangtd.bimmerdata.gauges.AlertSoundGate
import io.github.jonathangtd.bimmerdata.gauges.Alerts
import io.github.jonathangtd.bimmerdata.gauges.CardAlert
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One card watched for its alert sounds: [key] (the card id), its rules, their hysteresis and the
 * measurement ids of its values.
 */
data class WatchedCard(val key: String, val alerts: List<CardAlert>, val margin: Double, val measurementIds: List<String>)

/**
 * Alert state of every watched card, value by value, and which cards should beep now: a rule with
 * a sound that starts ([Alerts.soundStarts]), at most once per [AlertSoundGate] interval per card.
 * Pure (no Android), driven by [AlertMonitor].
 */
class AlertTracker(private val gate: AlertSoundGate = AlertSoundGate()) {
    private val masks = HashMap<String, Int>()

    /**
     * New values: [value] gives the value of a measurement id now (NaN = none or too old). Returns
     * the keys of the cards whose tone should play.
     */
    fun update(cards: List<WatchedCard>, value: (String) -> Double, nowMs: Long): List<String> {
        val beep = ArrayList<String>()
        val seen = HashSet<String>()
        for (c in cards) {
            var starts = false
            c.measurementIds.forEachIndexed { i, id ->
                val k = c.key + "#" + i
                seen += k
                val before = masks[k] ?: 0
                val now = Alerts.step(c.alerts, value(id), before, c.margin)
                masks[k] = now
                if (Alerts.soundStarts(c.alerts, before, now)) starts = true
            }
            if (starts && gate.shouldPlay(c.key, nowMs)) beep += c.key
        }
        masks.keys.retainAll(seen) // cards gone: forget them (they start again if they come back)
        gate.forget(cards.mapTo(HashSet()) { it.key })
        return beep
    }

    /** Edited rules or another profile: every card starts again from "no alert". */
    fun reset() = masks.clear()
}

/**
 * Plays the alert tones of the active profile's cards, whatever page is shown or with the screen
 * off: watches the live samples (the read loop runs in the foreground service while connected)
 * against the cards' alert rules ([AlertTracker]) and plays [AlertTone] when one with a bell
 * starts, if Settings › alert sounds is on. The phone cards are watched: Android Auto shows the
 * same cards ([io.github.jonathangtd.bimmerdata.dashboard.DashboardRules]).
 */
object AlertMonitor {
    private const val TAG = "BimmerData.Alerts"

    /** A sample older than this is no value (the same rule as the cards, GaugeFeed.staleAfterMs). */
    private const val STALE_MS = 5_000L

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = LiveRepository.get(app)
        val tone = AlertTone(app)
        val tracker = AlertTracker()
        scope.launch {
            val first = DashboardStore.saved.value ?: withContext(Dispatchers.IO) { runCatching { DashboardStore(app).load() }.getOrNull() }
            var watchedFor: Dashboard? = null
            var watched = emptyList<WatchedCard>()
            combine(DashboardStore.saved, repo.liveDataSource.samples) { d, s -> (d ?: first) to s }.collect { (d, samples) ->
                if (d !== watchedFor) {
                    watchedFor = d
                    watched = d?.let { cards(it, repo) }.orEmpty()
                    tracker.reset()
                }
                if (watched.isEmpty()) return@collect
                val now = System.currentTimeMillis()
                val beep = tracker.update(watched, { id -> valueOf(samples[id], now) }, now)
                if (beep.isNotEmpty() && AppSettings.get(app).state.value.alertSounds) {
                    Log.i(TAG, "alert tone for card(s) ${beep.joinToString()}")
                    tone.play()
                }
            }
        }
    }

    private fun valueOf(s: Sample?, now: Long): Double =
        if (s == null || now - s.timestampMs > STALE_MS) Double.NaN else s.value ?: Double.NaN

    /** The phone cards of [d] that have an enabled rule, with the hysteresis of their scale. */
    private fun cards(d: Dashboard, repo: LiveRepository): List<WatchedCard> {
        val index = runCatching { repo.measurementsRead() }.getOrDefault(emptyList()).associateBy { it.id }
        val format = GaugeFormat(Locale.getDefault())
        return d.phonePages.flatMap { it.cards }.mapNotNull { raw ->
            val card = raw.withLegacyAlerts()
            if (card.alerts.none { it.enabled }) return@mapNotNull null
            val spec = CardSpecs.build(card, index, format)
            WatchedCard(card.id, card.alerts, spec.alertMargin, card.measurementIds)
        }
    }
}
