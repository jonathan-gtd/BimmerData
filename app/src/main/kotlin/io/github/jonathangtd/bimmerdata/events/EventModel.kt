/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.events

import io.github.jonathangtd.bimmerdata.live.CsvSessionRecorder
import io.github.jonathangtd.bimmerdata.live.EventPoints
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where an event was marked from. */
@Serializable
enum class EventSource { Phone, Car, Notification, Api }

/**
 * A driver event: one tap on « Événement » (phone, Android Auto, notification, REST API) when
 * something is felt or heard, so that the instant is found again later instead of "around
 * 18:50". The values read around it are kept in a snapshot ([EventSnapshot], one file per
 * event): [EventSnapshots.BEFORE_MS] before the tap and [EventSnapshots.AFTER_MS] after it, the
 * part after being completed in the background ([snapshotComplete] false meanwhile, or when the
 * connection ended first).
 */
@Serializable
data class DriveEvent(
    val id: String,
    /** Time of the tap, epoch ms. */
    val timeMs: Long,
    val carId: String? = null,
    val carLabel: String? = null,
    val profileId: String? = null,
    val profileName: String? = null,
    /** Last GPS fix at the tap, when the GPS is on. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: Double? = null,
    /** Free text, editable later ([Events.MAX_NOTE] characters). */
    val note: String = "",
    val source: EventSource = EventSource.Phone,
    /** Time range of the snapshot (epoch ms); null = no value was read around the tap. */
    val snapshotFromMs: Long? = null,
    val snapshotToMs: Long? = null,
    val snapshotComplete: Boolean = false,
    /** Values in the snapshot. */
    val values: Int = 0,
)

/** One value of a snapshot: its times relative to the event (ms, negative before) and values. */
@Serializable
data class SnapshotSeries(
    val id: String,
    val label: String = "",
    val unit: String = "",
    val t: List<Long> = emptyList(),
    val v: List<Double> = emptyList(),
)

/** The values read around an event ([eventTimeMs] = time 0 of [SnapshotSeries.t]). */
@Serializable
data class EventSnapshot(
    val eventTimeMs: Long,
    val fromMs: Long,
    val toMs: Long,
    val series: List<SnapshotSeries> = emptyList(),
) {
    val isEmpty: Boolean get() = series.all { it.t.isEmpty() }
}

/** Label and unit of a value id, as shown to the user. */
data class ValueInfo(val label: String, val unit: String)

object EventSnapshots {
    const val BEFORE_MS = 60_000L
    const val AFTER_MS = 30_000L

    /**
     * The snapshot of an event at [eventTimeMs] from the points of [ids] between [fromMs] and
     * [toMs] ([points] gives them in any order and spacing); values with no point in the range
     * are left out, the series ordered by label. [info] names them (the id when unknown).
     */
    fun build(
        eventTimeMs: Long,
        fromMs: Long,
        toMs: Long,
        ids: Collection<String>,
        info: (String) -> ValueInfo?,
        points: (id: String, fromMs: Long, toMs: Long) -> List<Pair<Long, Double>>,
    ): EventSnapshot {
        val series = ids.distinct().mapNotNull { id ->
            val pts = points(id, fromMs, toMs).filter { (t, v) -> t in fromMs..toMs && v.isFinite() }.sortedBy { it.first }
            if (pts.isEmpty()) return@mapNotNull null
            val i = info(id)
            SnapshotSeries(id, i?.label?.ifBlank { null } ?: id, i?.unit.orEmpty(), pts.map { it.first - eventTimeMs }, pts.map { it.second })
        }.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.id }))
        return EventSnapshot(eventTimeMs, fromMs, toMs, series)
    }

    /** The window of an event: [BEFORE_MS] before it to [AFTER_MS] after it, cut at [nowMs] if earlier. */
    fun window(eventTimeMs: Long, nowMs: Long): Pair<Long, Long> =
        (eventTimeMs - BEFORE_MS) to minOf(eventTimeMs + AFTER_MS, maxOf(nowMs, eventTimeMs))

    /**
     * The snapshot as CSV, in the format of the recordings ([CsvSessionRecorder]): UTF-8 with
     * BOM, `;`, `.` decimals, `\r\n`; header `time_ms;iso_time;<label [unit]>...;marker`, where
     * `time_ms` counts from the event (negative before it). One row per distinct sample time,
     * each column holding the value read at that time (empty otherwise); the event itself is the
     * row at 0 with `event` (or `event: <note>`) in `marker`.
     */
    fun csv(event: DriveEvent, snapshot: EventSnapshot, zone: ZoneId = ZoneId.systemDefault()): String {
        val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(zone)
        val cols = snapshot.series
        val byTime = sortedMapOf<Long, Array<Double?>>()
        cols.forEachIndexed { c, s ->
            for (k in s.t.indices) byTime.getOrPut(s.t[k]) { arrayOfNulls(cols.size) }[c] = s.v.getOrNull(k)
        }
        if (0L !in byTime) byTime[0L] = arrayOfNulls(cols.size)
        val marker = EventPoints.csvMarker(event.note)
        return buildString {
            append('\uFEFF')
            append("time_ms;iso_time")
            for (s in cols) append(';').append(CsvSessionRecorder.cell(if (s.unit.isBlank()) s.label else "${s.label} [${s.unit}]"))
            append(";marker\r\n")
            for ((t, row) in byTime) {
                append(t).append(';').append(iso.format(Instant.ofEpochMilli(snapshot.eventTimeMs + t)))
                for (v in row) {
                    append(';')
                    if (v != null) append(CsvSessionRecorder.number(v))
                }
                append(';')
                if (t == 0L) append(CsvSessionRecorder.cell(marker))
                append("\r\n")
            }
        }
    }

    /** `bimmerdata_event_<yyyyMMdd_HHmmss>.csv` (local time of the event). */
    fun csvName(event: DriveEvent, zone: ZoneId = ZoneId.systemDefault()): String =
        "bimmerdata_event_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(zone).format(Instant.ofEpochMilli(event.timeMs)) + ".csv"
}

/** Rules on the list of events. */
object Events {
    const val MAX_NOTE = 200

    /** Events kept on the phone (the oldest go first). */
    const val MAX_EVENTS = 500

    fun cleanNote(note: String?): String = note.orEmpty().replace('\n', ' ').replace('\r', ' ').trim().take(MAX_NOTE)

    fun newId(timeMs: Long, random: Int = (Math.random() * 0xFFFF).toInt()): String =
        "ev-" + java.lang.Long.toString(timeMs, 36) + "-" + Integer.toString(random and 0xFFFF, 36)

    /** Newest first. */
    fun sorted(list: List<DriveEvent>): List<DriveEvent> = list.sortedByDescending { it.timeMs }

    /** [list] with [event] added (or replaced), newest first, at most [MAX_EVENTS]. */
    fun add(list: List<DriveEvent>, event: DriveEvent): List<DriveEvent> = sorted(list.filterNot { it.id == event.id } + event).take(MAX_EVENTS)
}

/** JSON of the events list and of the snapshots. */
object EventJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Serializable
    private data class EventsFile(val formatVersion: Int = 1, val events: List<DriveEvent> = emptyList())

    fun encodeList(events: List<DriveEvent>): String = json.encodeToString(EventsFile.serializer(), EventsFile(events = events))

    fun decodeList(text: String): List<DriveEvent> = json.decodeFromString(EventsFile.serializer(), text).events

    fun encodeSnapshot(s: EventSnapshot): String = json.encodeToString(EventSnapshot.serializer(), s)

    fun decodeSnapshot(text: String): EventSnapshot = json.decodeFromString(EventSnapshot.serializer(), text)
}
