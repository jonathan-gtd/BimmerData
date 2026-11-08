/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Readings as the engine emits them, for the exporter tests: every assignment to [value] is one
 * read group, none is skipped (unlike a state flow). Replayed, so the exporter's collector may
 * subscribe after the first emissions.
 */
internal class TestReadings {
    val flow = MutableSharedFlow<List<Pair<String, Sample>>>(replay = 4096)

    var value: Map<String, Sample> = emptyMap()
        set(v) {
            field = v
            check(flow.tryEmit(v.entries.map { it.key to it.value }))
        }
}
