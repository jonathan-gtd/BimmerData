/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.fake

import io.github.jonathangtd.bimmerdata.live.AcquisitionState
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.LiveDataSource
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated, plausible idle values for an N52 warming up in the driveway: ~700 rpm with noise and
 * an occasional blip, temperatures rising slowly, trims oscillating ±3 %, lambda cycling around
 * 1.00. Unknown ids get a slow sine so any card shows something.
 */
class FakeLiveDataSource(scope: CoroutineScope, private val periodMs: Long = 115) : LiveDataSource {
    private val _samples = MutableStateFlow<Map<String, Sample>>(emptyMap())
    override val samples: StateFlow<Map<String, Sample>> = _samples.asStateFlow()
    private val _status = MutableStateFlow(AcquisitionStatus(AcquisitionState.Connecting, message = "Simulation"))
    override val status: StateFlow<AcquisitionStatus> = _status.asStateFlow()

    @Volatile private var requested: List<Measurement> = emptyList()
    private val random = Random(7)
    private val startMs = System.currentTimeMillis()

    override fun setMeasurements(measurements: List<Measurement>) {
        requested = measurements
    }

    init {
        scope.launch {
            delay(600)
            var lastCycle = System.nanoTime()
            var rate = 1000.0 / periodMs
            while (isActive) {
                val now = System.currentTimeMillis()
                val t = (now - startMs) / 1000.0
                val ids = LinkedHashSet<String>(DEFAULT_IDS)
                requested.mapTo(ids) { it.id }
                _samples.value = ids.associateWith { id -> Sample(value(id, t), null, now) }
                val nanos = System.nanoTime()
                rate = rate * 0.9 + 0.1 * (1e9 / max(1L, nanos - lastCycle))
                lastCycle = nanos
                _status.value = AcquisitionStatus(AcquisitionState.Running, cyclesPerSecond = rate, message = "Simulation")
                delay(periodMs + random.nextLong(-15, 15))
            }
        }
    }

    private fun noise(amp: Double) = (random.nextDouble() - 0.5) * 2 * amp
    private fun wave(t: Double, period: Double, phase: Double = 0.0) = sin(2 * PI * (t + phase) / period)

    /** 0..1 rev blip lasting ~2.5 s every 25 s. */
    private fun blip(t: Double): Double {
        val x = t % 25.0 - 18.0
        return if (x in 0.0..2.5) sin(PI * x / 2.5).let { it * it } else 0.0
    }

    /** First-order warm-up from [from] to [to] with time constant [tau] seconds. */
    private fun warm(t: Double, from: Double, to: Double, tau: Double) = to - (to - from) * exp(-t / tau)

    private fun value(id: String, t: Double): Double = when (id.lowercase()) {
        "msv70/0x4807" -> 700 + 1700 * blip(t) + 12 * wave(t, 1.7) + noise(8.0)
        "msv70/0x4402" -> warm(t, 62.0, 104.0, 160.0) + 0.3 * wave(t, 23.0) + noise(0.05)
        "msv70/0x4300" -> warm(t, 58.0, 92.0, 120.0) + 0.2 * wave(t, 31.0)
        "msv70/0x4200" -> warm(t, 21.0, 33.0, 300.0) + 0.3 * wave(t, 17.0)
        "msv70/0x5a81" -> 2.3 + 2.2 * wave(t, 2.6) + 0.8 * wave(t, 9.0) + noise(0.2)
        "msv70/0x5a82" -> -1.6 + 2.0 * wave(t, 3.1, 1.0) + 0.7 * wave(t, 11.0) + noise(0.2)
        "msv70/0x5a50" -> 1.0 + 0.025 * wave(t, 1.1) + noise(0.004)
        "msv70/0x5a51" -> 1.0 + 0.022 * wave(t, 1.25, 0.4) + noise(0.004)
        "msv70/0x5a49" -> 12.0 + 9.0 * blip(t) + 1.5 * wave(t, 2.3) + noise(0.3)
        "msv70/0x5804" -> 18.0 + 22.0 * blip(t) + 1.2 * wave(t, 3.7) + noise(0.4)
        "msv70/0x460a" -> 14.15 + 0.05 * wave(t, 13.0) + noise(0.01)
        "msv70/0x580d" -> 0.0
        "msv70/0x4600" -> 2.4 + 14 * blip(t) + noise(0.05)
        "msv70/0x581a" -> 104.0 + 4 * blip(t) + noise(0.3)
        "msv70/0x4202" -> 330.0 + 400 * blip(t) + noise(4.0)
        else -> 50 + 30 * wave(t, 20.0, id.hashCode() % 7 / 1.0)
    }

    companion object {
        /** Always simulated, so the default dashboard is alive even before setMeasurements. */
        val DEFAULT_IDS = listOf(
            "msv70/0x4402", "msv70/0x4300", "msv70/0x5A81", "msv70/0x5A82", "msv70/0x4807",
            "msv70/0x5A50", "msv70/0x5A51", "msv70/0x5A49", "msv70/0x5804", "msv70/0x4200",
            "msv70/0x460A", "msv70/0x580D",
        )
    }
}
