/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId

class EventsTest {
    private val t0 = 1_800_000_000_000L
    private val paris = ZoneId.of("Europe/Paris")

    /** Points of a fake history: rpm every 250 ms, oil every 1 s with a gap, an unknown value. */
    private val history: Map<String, List<Pair<Long, Double>>> = mapOf(
        "msv70/0x4807" to (-90_000L..40_000L step 250).map { t0 + it to 800.0 + it / 100.0 },
        "msv70/0x4402" to ((-70_000L..-20_000L step 1000) + (5_000L..40_000L step 1000)).map { t0 + it to 90.0 },
        "x/unknown" to listOf(t0 - 1_000 to 1.0, t0 - 500 to Double.NaN),
        "msv70/0x4300" to listOf(t0 - 200_000 to 80.0), // nothing in the window
    )
    private val info = mapOf("msv70/0x4807" to ValueInfo("Engine speed", "rpm"), "msv70/0x4402" to ValueInfo("Oil", "°C"))

    private fun build(nowMs: Long): EventSnapshot {
        val (from, to) = EventSnapshots.window(t0, nowMs)
        return EventSnapshots.build(t0, from, to, history.keys, info::get) { id, f, t -> history[id].orEmpty().filter { it.first in f..t }.shuffled() }
    }

    @Test
    fun `snapshot window - a minute before, 30 s after, cut at now while capturing`() {
        assertEquals(t0 - 60_000 to t0 + 30_000, EventSnapshots.window(t0, t0 + 50_000))
        assertEquals(t0 - 60_000 to t0 + 4_000, EventSnapshots.window(t0, t0 + 4_000)) // connection lost after 4 s
        assertEquals(t0 - 60_000 to t0, EventSnapshots.window(t0, t0 - 10)) // clock before the tap
    }

    @Test
    fun `snapshot keeps the points of the window, relative times, sorted, labels`() {
        val s = build(t0 + 50_000)
        assertEquals(listOf("Engine speed", "Oil", "x/unknown"), s.series.map { it.label }) // 0x4300: no point, left out
        val rpm = s.series[0]
        assertEquals(-60_000L, rpm.t.first())
        assertEquals(30_000L, rpm.t.last())
        assertEquals(361, rpm.t.size)
        assertTrue(rpm.t.zipWithNext().all { (a, b) -> b > a }, "oldest first")
        assertEquals("rpm", rpm.unit)
        val oil = s.series[1]
        assertEquals(-60_000L, oil.t.first())
        assertFalse(oil.t.any { it in -19_999L..4_999L }, "the gap stays a gap")
        assertEquals(listOf(-1_000L), s.series[2].t) // NaN dropped
        assertFalse(s.isEmpty)
    }

    @Test
    fun `connection lost - the snapshot ends with the last values read`() {
        val s = build(t0 + 3_000)
        assertEquals(t0 + 3_000, s.toMs)
        assertEquals(3_000L, s.series[0].t.last())
    }

    @Test
    fun `nothing read - empty snapshot`() {
        val s = EventSnapshots.build(t0, t0 - 60_000, t0 + 30_000, listOf("a"), { null }) { _, _, _ -> emptyList() }
        assertTrue(s.isEmpty)
        assertTrue(s.series.isEmpty())
    }

    @Test
    fun `CSV like the recordings, time from the event, marker at 0`() {
        val e = DriveEvent("ev-1", t0, note = "jerk; 3rd gear")
        val s = EventSnapshot(t0, t0 - 1000, t0 + 1000, listOf(
            SnapshotSeries("a", "Engine speed", "rpm", listOf(-500, 500), listOf(800.0, 2500.5)),
            SnapshotSeries("b", "Oil", "", listOf(500), listOf(90.0)),
        ))
        val lines = EventSnapshots.csv(e, s, paris).removePrefix("\uFEFF").split("\r\n")
        assertEquals("time_ms;iso_time;Engine speed [rpm];Oil;marker", lines[0])
        assertEquals("-500;2027-01-15T08:59:59.500+01:00;800;;", lines[1])
        assertEquals("0;2027-01-15T09:00:00.000+01:00;;;\"event: jerk; 3rd gear\"", lines[2])
        assertEquals("500;2027-01-15T09:00:00.500+01:00;2500.5;90;", lines[3])
        assertEquals("", lines.last())
        assertEquals("bimmerdata_event_20270115_090000.csv", EventSnapshots.csvName(e, paris))
    }

    @Test
    fun `JSON round trip of the list and of a snapshot`() {
        val e = DriveEvent("ev-1", t0, "car-1", "E90", "p1", "DSC", 46.5, -0.3, 92.0, "noise", EventSource.Car, t0 - 60_000, t0 + 30_000, true, 2)
        assertEquals(listOf(e), EventJson.decodeList(EventJson.encodeList(listOf(e))))
        val s = build(t0 + 50_000)
        assertEquals(s, EventJson.decodeSnapshot(EventJson.encodeSnapshot(s)))
        // an older file without the optional fields
        assertEquals(DriveEvent("x", 5), EventJson.decodeList("""{"events":[{"id":"x","timeMs":5,"futureField":1}]}""").single())
    }

    @Test
    fun `notes are cleaned and limited, ids unique`() {
        assertEquals("a b", Events.cleanNote(" a\nb "))
        assertEquals(Events.MAX_NOTE, Events.cleanNote("x".repeat(500)).length)
        assertTrue(Events.newId(t0, 1) != Events.newId(t0, 2))
    }

    @Test
    fun `store - add, capture, note, delete, newest first, survives a restart`(@TempDir dir: File) {
        val store = EventStore(dir)
        val a = store.add(DriveEvent("a", t0), null)
        assertFalse(a.snapshotComplete)
        store.add(DriveEvent("b", t0 + 10_000, source = EventSource.Api), null)
        assertEquals(listOf("b", "a"), store.all().map { it.id })
        val snap = build(t0 + 4_000)
        val partial = store.completeSnapshot("a", snap, complete = false)!!
        assertEquals(3, partial.values)
        assertEquals(t0 + 4_000, partial.snapshotToMs)
        val done = store.completeSnapshot("a", build(t0 + 60_000), complete = true)!!
        assertTrue(done.snapshotComplete)
        assertEquals(t0 + 30_000, done.snapshotToMs)
        assertEquals("felt it", store.setNote("a", "  felt it ")!!.note)
        assertNull(store.setNote("zz", "x"))

        val again = EventStore(dir)
        assertEquals(store.all(), again.all())
        assertEquals(build(t0 + 60_000), again.snapshot("a"))
        assertTrue(again.delete("a"))
        assertFalse(again.delete("a"))
        assertNull(again.snapshot("a"))
        assertFalse(File(dir, "a.json").exists())
        assertEquals(listOf("b"), EventStore(dir).all().map { it.id })
    }

    @Test
    fun `store - the oldest go beyond the limit, with their snapshot`(@TempDir dir: File) {
        val store = EventStore(dir)
        val snap = EventSnapshot(t0, t0, t0, listOf(SnapshotSeries("a", t = listOf(0), v = listOf(1.0))))
        repeat(Events.MAX_EVENTS + 2) { i -> store.add(DriveEvent("e$i", t0 + i), snap) }
        assertEquals(Events.MAX_EVENTS, store.all().size)
        assertNull(store.get("e0"))
        assertFalse(File(dir, "e0.json").exists())
        assertNotNull(store.snapshot("e${Events.MAX_EVENTS + 1}"))
    }

    @Test
    fun `store - restore replaces everything`(@TempDir dir: File) {
        val store = EventStore(dir)
        store.add(DriveEvent("old", t0), build(t0 + 60_000))
        val snap = EventSnapshot(t0, t0, t0, listOf(SnapshotSeries("a", t = listOf(0), v = listOf(1.0))))
        store.replaceAll(listOf(DriveEvent("n1", t0 + 1) to snap, DriveEvent("n2", t0 + 2) to null))
        assertEquals(listOf("n2", "n1"), store.all().map { it.id })
        assertNull(store.snapshot("old"))
        assertEquals(snap, store.snapshot("n1"))
        assertNull(store.snapshot("n2"))
        assertEquals(1, store.get("n1")!!.values)
    }
}
