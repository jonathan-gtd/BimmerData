/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.dyno

import io.github.jonathangtd.bimmerdata.live.vehicle.CarPhysics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class DynoProcessorTest {
    private val physics = CarPhysics(massKg = 1600.0).resolved()!!

    /** Wheel power (kW) the simulated car gives at [rpm]: rises to [peakKw] at 6000 rpm. */
    private fun truthKw(rpm: Double, peakKw: Double = 150.0): Double = peakKw * (rpm / 6000) * (2 - rpm / 6000)

    private data class Sim(val capture: DynoCapture, val gearKmhPerRpm: Double)

    /**
     * A full-throttle pull in one gear ([kmhPerRpm]) from [fromRpm] to [toRpm], integrated every
     * millisecond with the same physics as the processor; read every [periodMs] ± [jitterMs] with
     * the engine speed rounded (± [rpmNoise]) and the speed in whole km/h.
     */
    private fun pull(
        kmhPerRpm: Double = 0.035, fromRpm: Double = 2000.0, toRpm: Double = 6300.0, periodMs: Int = 160, jitterMs: Int = 70,
        rpmNoise: Double = 0.0, seed: Int = 1, startMs: Long = 1_000_000L, overlayTorque: Boolean = false, peakKw: Double = 150.0,
    ): Sim {
        val rnd = Random(seed)
        var v = kmhPerRpm * fromRpm / 3.6
        var t = 0L
        var next = 0L
        val rpm = ArrayList<TimedValue>()
        val speed = ArrayList<TimedValue>()
        val overlay = ArrayList<TimedValue>()
        while (t < 120_000) {
            val r = v * 3.6 / kmhPerRpm
            if (r >= toRpm) break
            if (t >= next) {
                val noise = if (rpmNoise > 0) rnd.nextDouble(-rpmNoise, rpmNoise) else 0.0
                rpm += TimedValue(startMs + t, (r + noise).roundToInt().toDouble())
                speed += TimedValue(startMs + t, (v * 3.6).roundToInt().toDouble())
                if (overlayTorque) overlay += TimedValue(startMs + t, truthKw(r, peakKw) * 1000 / (r * 2 * PI / 60))
                next = t + periodMs + if (jitterMs > 0) rnd.nextInt(-jitterMs, jitterMs + 1) else 0
            }
            val p = truthKw(r, peakKw) * 1000
            val resist = 0.5 * physics.airDensity * physics.dragAreaM2 * v * v * v + physics.rollingResistance * physics.massKg * 9.80665 * v
            v += (p - resist) / (physics.massKg * v) * 0.001
            t++
        }
        return Sim(DynoCapture(rpm, speed, overlay = overlay), kmhPerRpm)
    }

    private fun assertCurveWithin(result: DynoResult, tolerance: Double) {
        assertTrue(result.ok, "problem ${result.problem}")
        for (pt in result.points) {
            val truthCh = truthKw(pt.rpm) * KW_TO_CH
            assertTrue(abs(pt.wheelPowerCh - truthCh) / truthCh < tolerance, "at ${pt.rpm.toInt()} rpm: ${pt.wheelPowerCh} ch vs $truthCh ch")
        }
    }

    @Test
    fun `a pull read 6 times a second gives the wheel power within 3 percent`() {
        val sim = pull()
        val r = DynoProcessor.process(sim.capture, physics)
        assertCurveWithin(r, 0.03)
        assertEquals(35.0, r.kmhPer1000Rpm!!, 0.3)
        val peak = r.peakWheelPowerCh!!
        assertTrue(abs(peak - truthKw(r.peakWheelPowerRpm!!) * KW_TO_CH) / peak < 0.03)
        assertTrue(r.peakWheelPowerRpm!! > 5500)
        assertTrue(DynoWarning.AssumedFlat in r.warnings)
        assertTrue(DynoWarning.FewSamples !in r.warnings)
        assertTrue(r.sampleRateHz in 5.0..8.0, "rate ${r.sampleRateHz}")
        assertTrue(r.binRpm in 100..250)
        // the torque at the engine is the wheel power over the engine's angular speed
        val pt = r.points[r.points.size / 2]
        assertEquals(pt.wheelPowerCh / KW_TO_CH * 1000 / (pt.rpm * 2 * PI / 60), pt.torqueNm, 0.5)
        // engine estimate = wheel power with the driveline loss added back
        assertEquals(pt.wheelPowerCh / 0.85, pt.enginePowerCh, 1e-6)
    }

    @Test
    fun `irregular readings 2 to 3 times a second still give the curve within 5 percent`() {
        val r = DynoProcessor.process(pull(periodMs = 400, jitterMs = 150, seed = 7).capture, physics)
        assertCurveWithin(r, 0.05)
        assertTrue(DynoWarning.LowSampleRate in r.warnings)
    }

    @Test
    fun `noisy engine speed is smoothed out`() {
        val r = DynoProcessor.process(pull(periodMs = 150, jitterMs = 40, rpmNoise = 15.0, seed = 3).capture, physics)
        assertCurveWithin(r, 0.08)
    }

    @Test
    fun `a gear change in the middle is refused`() {
        val low = pull(kmhPerRpm = 0.020, toRpm = 5800.0).capture
        val endKmh = low.speed.last().value
        val endMs = low.rpm.last().tMs
        // next gear: same speed, engine speed down by the ratio
        val high = pull(kmhPerRpm = 0.028, fromRpm = endKmh / 0.028, toRpm = 5800.0, startMs = endMs + 300, seed = 2).capture
        val both = DynoCapture(low.rpm + high.rpm, low.speed + high.speed)
        val r = DynoProcessor.process(both, physics)
        assertEquals(DynoProblem.NotOneGear, r.problem)
        assertNotNull(r.ratioSpreadPct)
    }

    @Test
    fun `no mass, no speed, too few readings, coasting`() {
        val sim = pull()
        assertEquals(DynoProblem.NoMass, DynoProcessor.process(sim.capture, null).problem)
        assertEquals(DynoProblem.NoSpeed, DynoProcessor.process(sim.capture.copy(speed = emptyList()), physics).problem)
        val few = DynoCapture(sim.capture.rpm.take(4), sim.capture.speed.take(4))
        assertEquals(DynoProblem.TooFewSamples, DynoProcessor.process(few, physics).problem)
        // engine braking: the same readings backwards in time
        val t0 = sim.capture.rpm.first().tMs
        val t1 = sim.capture.rpm.last().tMs
        fun back(l: List<TimedValue>) = l.map { TimedValue(t0 + t1 - it.tMs, it.value) }
        assertEquals(DynoProblem.NotAccelerating, DynoProcessor.process(DynoCapture(back(sim.capture.rpm), back(sim.capture.speed)), physics).problem)
    }

    @Test
    fun `the car's own torque is drawn next to the measured one, as torque and power`() {
        val sim = pull(overlayTorque = true)
        val r = DynoProcessor.process(sim.capture, physics, overlayUnit = OverlayUnit.Torque)
        assertTrue(r.ok)
        for (pt in r.points) {
            assertNotNull(pt.overlayTorqueNm)
            assertTrue(abs(pt.overlayPowerCh!! - pt.wheelPowerCh) / pt.wheelPowerCh < 0.05, "${pt.overlayPowerCh} vs ${pt.wheelPowerCh}")
        }
        assertEquals(r.peakWheelPowerCh!!, r.peakOverlayPowerCh!!, r.peakWheelPowerCh!! * 0.05)
        // without a unit the overlay is not drawn
        assertNull(DynoProcessor.process(sim.capture, physics).points.first().overlayTorqueNm)
    }

    @Test
    fun `an overlay in kW is converted to ch and to torque`() {
        val sim = pull()
        val kw = sim.capture.rpm.map { TimedValue(it.tMs, truthKw(it.value)) }
        val r = DynoProcessor.process(sim.capture.copy(overlay = kw), physics, overlayUnit = OverlayUnit.Kw)
        val pt = r.points[2]
        assertEquals(truthKw(pt.rpm) * KW_TO_CH, pt.overlayPowerCh!!, truthKw(pt.rpm) * 0.03)
        assertEquals(pt.overlayPowerCh!! / KW_TO_CH * 1000 / (pt.rpm * 2 * PI / 60), pt.overlayTorqueNm!!, 1.0)
    }

    @Test
    fun `overlay units`() {
        assertEquals(OverlayUnit.Torque, OverlayUnit.of("Nm"))
        assertEquals(OverlayUnit.Torque, OverlayUnit.of("N·m"))
        assertEquals(OverlayUnit.Ch, OverlayUnit.of("ch"))
        assertEquals(OverlayUnit.Ch, OverlayUnit.of("PS"))
        assertEquals(OverlayUnit.Kw, OverlayUnit.of("kW"))
        assertEquals(OverlayUnit.Hp, OverlayUnit.of("hp"))
        assertNull(OverlayUnit.of("°C"))
    }

    @Test
    fun `helpers - interpolation, bins, duplicates`() {
        val s = listOf(TimedValue(0, 0.0), TimedValue(1000, 10.0))
        assertEquals(5.0, DynoProcessor.interpolate(s, 500), 1e-9)
        assertEquals(0.0, DynoProcessor.interpolate(s, -5), 1e-9)
        assertEquals(10.0, DynoProcessor.interpolate(s, 5000), 1e-9)
        assertEquals(100, DynoProcessor.binWidth(400.0, 50))
        assertEquals(250, DynoProcessor.binWidth(5000.0, 20))
        assertEquals(200, DynoProcessor.binWidth(4000.0, 50))
        val dup = DynoProcessor.clean(listOf(TimedValue(2, 1.0), TimedValue(1, 0.0), TimedValue(2, 3.0), TimedValue(3, Double.NaN)))
        assertEquals(listOf(TimedValue(1, 0.0), TimedValue(2, 3.0)), dup)
        // the local fit of a straight line gives its slope exactly
        val line = (0 until 12).map { TimedValue(it * 200L, 1000.0 + it * 200 * 0.5) }
        val (v, slope) = DynoProcessor.localQuadratic(line, 4)
        assertEquals(1400.0, v, 1e-6)
        assertEquals(500.0, slope, 1e-6)
    }
}
