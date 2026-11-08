/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.fake

import io.github.jonathangtd.bimmerdata.live.LiveDataSource
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.SessionRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pretends to write a CSV: counts one row per sample emission, keeps markers in memory. */
class FakeSessionRecorder(scope: CoroutineScope, source: LiveDataSource) : SessionRecorder {
    private val _state = MutableStateFlow(RecordingState())
    override val state: StateFlow<RecordingState> = _state.asStateFlow()
    val markers = mutableListOf<Pair<Long, String>>()

    init {
        scope.launch {
            source.samples.collect {
                val s = _state.value
                if (s.recording) _state.value = s.copy(rows = s.rows + 1)
            }
        }
    }

    override fun start() {
        if (_state.value.recording) return
        val now = System.currentTimeMillis()
        val name = "msv70_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(now)) + ".csv"
        markers.clear()
        _state.value = RecordingState(recording = true, fileName = name, startedAtMs = now, rows = 0)
    }

    override fun stop() {
        _state.value = _state.value.copy(recording = false)
    }

    override fun mark(label: String) {
        if (_state.value.recording) markers += System.currentTimeMillis() to label
    }
}
