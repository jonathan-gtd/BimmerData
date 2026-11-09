/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every measurement of the active profile (all its phone and Android Auto pages), kept current by
 * the screens that know the layout and the catalog (phone dashboard, Android Auto). It is read
 * whole, when it fits ([ReadScope]), while the Telegraf export is on and for the columns of a CSV
 * recording; otherwise only what is on screen is read, which keeps the read rate high (each value
 * of a dynamic block costs the ECU about 1 ms per cycle).
 */
object ProfileMeasurements {
    private val _flow = MutableStateFlow<List<Measurement>>(emptyList())

    /** [current] as a flow, to read the new profile at once when it changes. */
    val flow: StateFlow<List<Measurement>> = _flow.asStateFlow()

    var current: List<Measurement>
        get() = _flow.value
        set(value) { _flow.value = value }
}
