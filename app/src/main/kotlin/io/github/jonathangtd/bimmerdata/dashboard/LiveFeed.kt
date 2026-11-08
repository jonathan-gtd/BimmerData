/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import io.github.jonathangtd.bimmerdata.live.Sample

/** Latest samples plus per-measurement history, read by the card renderers at draw time (UI thread). */
class LiveFeed : GaugeFeed {
    @Volatile var samples: Map<String, Sample> = emptyMap()
        private set
    private val histories = HashMap<String, ValueHistory>()

    /**
     * How far back histories keep min / max buckets: the longest trend window on screen (phone and
     * car layouts). Shorter windows need only the raw samples; the default is the old fixed window.
     */
    var retainMs: Long = TrendWindow.DEFAULT_MS
        set(value) {
            field = value
            histories.values.forEach { it.retainMs = value }
        }

    override fun sample(id: String): Sample? = samples[id]
    override fun history(id: String): ValueHistory? = histories[id]
    override val nowMs: Long get() = System.currentTimeMillis()

    fun update(newSamples: Map<String, Sample>) {
        samples = newSamples
        for ((id, s) in newSamples) {
            val v = s.value ?: continue
            histories.getOrPut(id) { ValueHistory(retainMs = retainMs) }.add(s.timestampMs, v)
        }
    }
}

/** Longest trend window of [pages] in ms (the default window when there is no trend card). */
fun longestTrendWindowMs(pages: List<Page>): Long =
    pages.flatMap { it.cards }.filter { it.type == GaugeType.Trend }.maxOfOrNull { TrendWindow.ms(it.windowSeconds) }
        ?.coerceAtLeast(TrendWindow.DEFAULT_MS) ?: TrendWindow.DEFAULT_MS
