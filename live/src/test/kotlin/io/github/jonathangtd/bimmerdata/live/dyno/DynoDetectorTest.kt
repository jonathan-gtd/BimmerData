/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.dyno

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DynoDetectorTest {
    /** One read cycle at [t]: engine speed, speed (k = 0.035 km/h per rpm unless given), pedal. */
    private fun DynoDetector.cycle(t: Long, rpm: Double, pedal: Double?, kmh: Double = rpm * 0.035): List<DynoEvent> = listOfNotNull(
        onValue(DynoRole.Rpm, t, rpm),
        onValue(DynoRole.Speed, t, kmh),
        pedal?.let { onValue(DynoRole.Pedal, t, it) },
    )

    @Test
    fun `armed - full throttle and rising engine speed start a run, the pedal release ends it`() {
        val d = DynoDetector(hasPedal = true)
        d.arm()
        assertEquals(DynoDetector.State.Armed, d.state)
        assertTrue(d.cycle(0, 2000.0, 20.0).isEmpty())
        assertTrue(d.cycle(200, 2050.0, 95.0).isEmpty()) // pedal down, engine speed not rising yet when read
        val started = d.cycle(400, 2150.0, 99.0)
        assertTrue(started.single() is DynoEvent.Started)
        assertEquals(DynoDetector.State.Running, d.state)
        var t = 600L
        var r = 2150.0
        repeat(20) { r += 100; d.cycle(t, r, 99.0).let { assertTrue(it.isEmpty(), "$it") }; t += 200 }
        val end = d.cycle(t, r + 50, 30.0).single() as DynoEvent.Finished
        assertEquals(DynoEnd.PedalReleased, end.end)
        // from the reading before the start, without the readings of the lift
        assertEquals(200L, end.capture.rpm.first().tMs)
        assertEquals(t - 200, end.capture.rpm.last().tMs)
        assertTrue(end.capture.pedal.all { it.value >= 90 })
        assertEquals(DynoDetector.State.Armed, d.state)
    }

    @Test
    fun `an engine speed drop ends the run at its highest, and the next run waits for a pedal release`() {
        val d = DynoDetector(hasPedal = true)
        d.arm()
        d.cycle(0, 3000.0, 99.0)
        assertTrue(d.cycle(200, 3100.0, 99.0).single() is DynoEvent.Started)
        var t = 400L
        var r = 3100.0
        repeat(10) { r += 120; d.cycle(t, r, 99.0); t += 200 }
        val top = t - 200
        val end = d.cycle(t, r - 1500, 99.0, kmh = r * 0.035).filterIsInstance<DynoEvent.Finished>().single()
        assertEquals(DynoEnd.RpmDropped, end.end)
        assertEquals(top, end.capture.rpm.last().tMs)
        // pedal still down, engine speed rising again: no new run until the pedal was released
        t += 200
        assertTrue(d.cycle(t, r - 1400, 99.0).isEmpty())
        t += 200
        assertTrue(d.cycle(t, r - 1300, 99.0).isEmpty())
        t += 200
        assertTrue(d.cycle(t, r - 1300, 10.0).isEmpty())
        t += 200
        d.cycle(t, r - 1250, 99.0)
        t += 200
        assertTrue(d.cycle(t, r - 1200, 99.0).single() is DynoEvent.Started)
    }

    @Test
    fun `a gear change (speed to engine speed ratio) ends the run before it`() {
        val d = DynoDetector(hasPedal = true)
        d.arm()
        d.cycle(0, 2000.0, 99.0)
        d.cycle(200, 2100.0, 99.0)
        var t = 400L
        var r = 2100.0
        repeat(10) { r += 100; d.cycle(t, r, 99.0); t += 200 }
        // clutch slipping: engine speed up, speed not following, twice
        val first = t
        assertTrue(d.cycle(t, r + 300, 99.0, kmh = r * 0.035).isEmpty())
        t += 200
        val end = d.cycle(t, r + 600, 99.0, kmh = r * 0.035).filterIsInstance<DynoEvent.Finished>().single()
        assertEquals(DynoEnd.GearChanged, end.end)
        assertTrue(end.capture.rpm.last().tMs < first)
    }

    @Test
    fun `without a pedal input only a manual start starts a run, stop ends it, too short runs are discarded`() {
        val d = DynoDetector(hasPedal = false)
        d.arm()
        var t = 0L
        var r = 2000.0
        repeat(5) { r += 200; assertTrue(d.cycle(t, r, null).isEmpty()); t += 200 }
        assertEquals(DynoDetector.State.Armed, d.state)
        assertTrue(d.start(t) is DynoEvent.Started)
        repeat(3) { r += 200; d.cycle(t, r, null); t += 200 }
        val short = d.stop(t) as DynoEvent.Discarded
        assertEquals(3, short.rpmSamples)
        assertTrue(d.start(t) is DynoEvent.Started)
        repeat(12) { r += 100; d.cycle(t, r, null); t += 200 }
        val run = d.stop(t) as DynoEvent.Finished
        assertEquals(DynoEnd.Stopped, run.end)
        assertEquals(12, run.capture.rpm.size)
        assertEquals(DynoDetector.State.Armed, d.state)
        assertNull(d.stop(t))
    }

    @Test
    fun `no engine speed for a while ends the run, disarm ends it too`() {
        val d = DynoDetector(hasPedal = false)
        d.start(0)
        repeat(8) { d.cycle(it * 200L, 2000.0 + it * 100, null) }
        assertNull(d.tick(2000))
        val end = d.tick(1400 + 3001) as DynoEvent.Finished
        assertEquals(DynoEnd.NoData, end.end)
        assertEquals(DynoDetector.State.Idle, d.state)
        d.arm()
        d.start(10_000)
        repeat(8) { d.cycle(10_000 + it * 200L, 2000.0 + it * 100, null) }
        assertEquals(DynoEnd.Disarmed, (d.disarm(12_000) as DynoEvent.Finished).end)
        assertEquals(DynoDetector.State.Idle, d.state)
    }
}
