/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeFeed
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import io.github.jonathangtd.bimmerdata.live.Sample

/** Latest samples plus per-measurement history, read by the card renderers at draw time (UI thread). */
class LiveFeed : GaugeFeed {
    @Volatile var samples: Map<String, Sample> = emptyMap()
        private set
    private val histories = HashMap<String, ValueHistory>()

    override fun sample(id: String): Sample? = samples[id]
    override fun history(id: String): ValueHistory? = histories[id]
    override val nowMs: Long get() = System.currentTimeMillis()

    fun update(newSamples: Map<String, Sample>) {
        samples = newSamples
        for ((id, s) in newSamples) {
            val v = s.value ?: continue
            histories.getOrPut(id) { ValueHistory() }.add(s.timestampMs, v)
        }
    }
}
