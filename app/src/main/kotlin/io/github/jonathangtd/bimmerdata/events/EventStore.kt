/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.events

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The driver events kept on the phone, in [dir]: `events.json` (the list, newest first) and one
 * `<id>.json` snapshot per event. Every call is synchronized and writes through at once (small
 * files, atomic rename); [state] publishes the list. Pure JVM (tests use a temporary folder).
 */
class EventStore(private val dir: File) {
    private val listFile = File(dir, "events.json")
    private val _state = MutableStateFlow(load())

    /** Every event, newest first. */
    val state: StateFlow<List<DriveEvent>> = _state.asStateFlow()

    fun all(): List<DriveEvent> = _state.value

    fun get(id: String): DriveEvent? = _state.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(event: DriveEvent, snapshot: EventSnapshot?): DriveEvent {
        val e = withSnapshot(event, snapshot)
        if (snapshot != null) writeSnapshot(e.id, snapshot)
        val list = Events.add(_state.value, e)
        // the oldest beyond the limit go with their snapshot
        _state.value.filter { old -> list.none { it.id == old.id } }.forEach { snapshotFile(it.id).delete() }
        save(list)
        return e
    }

    /** Replaces the snapshot of [id] (the part after the tap is complete, or the connection ended). */
    @Synchronized
    fun completeSnapshot(id: String, snapshot: EventSnapshot, complete: Boolean): DriveEvent? {
        val e = get(id) ?: return null
        writeSnapshot(id, snapshot)
        val updated = withSnapshot(e, snapshot).copy(snapshotComplete = complete)
        save(Events.add(_state.value, updated))
        return updated
    }

    @Synchronized
    fun setNote(id: String, note: String): DriveEvent? {
        val e = get(id) ?: return null
        val updated = e.copy(note = Events.cleanNote(note))
        save(Events.add(_state.value, updated))
        return updated
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (get(id) == null) return false
        snapshotFile(id).delete()
        save(_state.value.filterNot { it.id == id })
        return true
    }

    fun snapshot(id: String): EventSnapshot? {
        val f = snapshotFile(id)
        if (get(id) == null || !f.isFile) return null
        return runCatching { EventJson.decodeSnapshot(f.readText()) }.getOrNull()
    }

    /** Replaces every event and snapshot (backup restore). Snapshots null = that event keeps none. */
    @Synchronized
    fun replaceAll(events: List<Pair<DriveEvent, EventSnapshot?>>) {
        dir.listFiles()?.filter { it.name != listFile.name && it.name.endsWith(".json") }?.forEach { it.delete() }
        val list = Events.sorted(events.map { (e, s) -> if (s != null) withSnapshot(e, s).also { writeSnapshot(e.id, s) } else e }).take(Events.MAX_EVENTS)
        save(list)
    }

    private fun withSnapshot(e: DriveEvent, s: EventSnapshot?): DriveEvent =
        if (s == null || s.isEmpty) e.copy(snapshotFromMs = s?.fromMs, snapshotToMs = s?.toMs, values = 0)
        else e.copy(snapshotFromMs = s.fromMs, snapshotToMs = s.toMs, values = s.series.size)

    private fun snapshotFile(id: String) = File(dir, "${safe(id)}.json")

    private fun writeSnapshot(id: String, s: EventSnapshot) = atomicWrite(snapshotFile(id), EventJson.encodeSnapshot(s))

    private fun save(list: List<DriveEvent>) {
        atomicWrite(listFile, EventJson.encodeList(list))
        _state.value = list
    }

    private fun load(): List<DriveEvent> =
        if (listFile.isFile) runCatching { Events.sorted(EventJson.decodeList(listFile.readText())) }.getOrDefault(emptyList()) else emptyList()

    private fun atomicWrite(f: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")
        fun safe(id: String) = id.replace(UNSAFE, "_").take(64)
    }
}
