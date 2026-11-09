/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.graph

import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.gauges.ValueHistory
import io.github.jonathangtd.bimmerdata.live.LiveDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * History of every value the app reads, whoever asked for it (phone dashboard, Android Auto, the
 * Graph tab, the REST API), kept for the longest graph window ([TrendWindow.MAX_SECONDS]) for as
 * long as the app runs. The Graph tab draws from it, so opening a graph shows the past of values
 * already read for the dashboard; it never causes extra reads by itself.
 *
 * Bounded memory per value: [RAW_CAPACITY] raw samples (about 13 min at 20 reads/s, about
 * 200 KB) then 1 s min / max buckets (about 60 KB for an hour). Fed and read on the main thread.
 */
object SharedHistory {
    const val RETAIN_MS = TrendWindow.MAX_SECONDS * 1000L
    const val RAW_CAPACITY = 16_384

    private val histories = HashMap<String, ValueHistory>()
    @Volatile private var started = false

    /** Starts recording what [source] reads (once per process). */
    fun start(source: LiveDataSource) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            source.readings.collect { list -> list.forEach { (id, s) -> add(id, s.timestampMs, s.value) } }
        }
    }

    /** Main thread only. */
    fun add(id: String, timestampMs: Long, value: Double?) {
        if (value == null || value.isNaN()) return
        histories.getOrPut(id) { ValueHistory(capacity = RAW_CAPACITY, retainMs = RETAIN_MS) }.add(timestampMs, value)
    }

    /** Main thread only. */
    fun history(id: String): ValueHistory? = histories[id]

    /** Oldest point kept among [ids] (how far back a graph can scroll), null when none. */
    fun oldestMs(ids: Collection<String>): Long? = ids.mapNotNull { histories[it]?.oldestTimestampMs }.minOrNull()

    /** For tests. */
    fun clear() = histories.clear()
}
