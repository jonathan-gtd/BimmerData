/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import io.github.jonathangtd.bimmerdata.events.DriveEvent
import io.github.jonathangtd.bimmerdata.events.EventJson
import io.github.jonathangtd.bimmerdata.events.EventSnapshot
import io.github.jonathangtd.bimmerdata.events.EventSnapshots
import io.github.jonathangtd.bimmerdata.events.EventSource
import io.github.jonathangtd.bimmerdata.events.Events
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Driver events (« Événement » button): mark one, list them, read one with the values read
 * around it (JSON or CSV), change its note, delete it. Part of [ApiService]; pure JVM.
 */
internal class EventRoutes(private val backend: ApiBackend) {
    private fun ok(element: JsonElement, status: Int = 200) = json(status, element)

    private fun eventJson(e: DriveEvent): JsonElement = EventJson.json.encodeToJsonElement(DriveEvent.serializer(), e)

    private fun snapshotJson(s: EventSnapshot): JsonElement = EventJson.json.encodeToJsonElement(EventSnapshot.serializer(), s)

    private fun find(id: String): DriveEvent = backend.event(id) ?: throw notFound("event_not_found", "No event '$id'; see GET /api/v1/events")

    private fun note(b: JsonObject): String? {
        val n = b.string("note") ?: return null
        if (n.length > Events.MAX_NOTE) throw ApiException(422, "invalid_note", "'note' is limited to ${Events.MAX_NOTE} characters")
        return n
    }

    fun routes(): List<Route> {
        val idParam = Param("id", "path", "Event id (see GET /api/v1/events).", S.str(), example = JsonPrimitive("ev-mhz3k2a1-1x2"))
        val noteBody = S.obj(null, emptyList(), false, null, "note" to S.str("What was felt or heard; at most ${Events.MAX_NOTE} characters.", "jerk at 90 km/h, 3rd gear"))
        val one = S.obj(null, listOf("event"), false, null, "event" to S.ref("DriveEvent"))
        return listOf(
            Route(
                "POST", "/api/v1/events", RouteAccess.Needs(Scope.Config), TAG, "markEvent",
                "Mark an event now",
                "Same as the « Événement » button: saves the instant with the car, the profile and the last GPS fix, writes a point `bimmerdata/event` (value 1, label = the note) to the Telegraf export and a marker row to the running CSV recording, and keeps the values read ${EventSnapshots.BEFORE_MS / 1000} s before and ${EventSnapshots.AFTER_MS / 1000} s after it (completed in the background: `snapshotComplete`). Works connected or not (no values when not).",
                body = Body(noteBody, required = false),
                reply = Reply(201, "The event as saved", one),
                errors = mapOf(422 to "Note too long."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("note")
                ok(buildJsonObject { put("event", eventJson(backend.markEvent(note(b), EventSource.Api))) }, 201)
            },
            Route(
                "GET", "/api/v1/events", RouteAccess.Needs(Scope.Read), TAG, "listEvents",
                "Events",
                "Every event kept on the phone (at most ${Events.MAX_EVENTS}), newest first; `car` keeps one car's.",
                params = listOf(
                    Param("car", "query", "Only this car's events (car id, see GET /api/v1/cars).", S.str(), required = false),
                    Param("limit", "query", "At most this many (default all).", S.int(min = 1, max = Events.MAX_EVENTS.toLong()), required = false),
                ),
                reply = Reply(description = "Events", schema = S.obj(null, listOf("events"), false, null, "events" to S.arr(S.ref("DriveEvent")))),
            ) { call ->
                val car = call.query("car")
                val limit = call.queryInt("limit", Events.MAX_EVENTS, 1..Events.MAX_EVENTS)
                val list = backend.events().filter { car == null || it.carId == car }.take(limit)
                ok(buildJsonObject { put("events", JsonArray(list.map(::eventJson))) })
            },
            Route(
                "GET", "/api/v1/events/{id}", RouteAccess.Needs(Scope.Read), TAG, "getEvent",
                "One event and its values",
                "The event and its snapshot: every value read around it, times in ms from the event (negative before). `?format=csv` gives the snapshot as CSV instead (same format as the recordings, `time_ms` from the event, marker `event` at 0).",
                params = listOf(idParam, Param("format", "query", "`json` (default) or `csv`.", S.str(enum = listOf("json", "csv")), required = false)),
                reply = Reply(description = "Event and snapshot", schema = S.obj(null, listOf("event"), false, null, "event" to S.ref("DriveEvent"), "snapshot" to S.ref("EventSnapshot"))),
                errors = mapOf(404 to "Unknown event."),
            ) { call ->
                val e = find(call.path("id"))
                val snap = backend.eventSnapshot(e.id)
                when (call.query("format")?.lowercase()) {
                    null, "json" -> ok(buildJsonObject {
                        put("event", eventJson(e))
                        if (snap != null) put("snapshot", snapshotJson(snap))
                    })
                    "csv" -> HttpResponse.Bytes(
                        200, "text/csv; charset=utf-8",
                        EventSnapshots.csv(e, snap ?: EventSnapshot(e.timeMs, e.timeMs, e.timeMs)).toByteArray(Charsets.UTF_8),
                        mapOf("Content-Disposition" to "attachment; filename=\"${EventSnapshots.csvName(e)}\""),
                    )
                    else -> throw badRequest("'format' must be json or csv")
                }
            },
            Route(
                "PATCH", "/api/v1/events/{id}", RouteAccess.Needs(Scope.Config), TAG, "updateEvent",
                "Change an event's note",
                "Only the note can change (the Telegraf point already sent keeps its label).",
                params = listOf(idParam),
                body = Body(noteBody, example = buildJsonObject { put("note", "jerk, cruise control off") }),
                reply = Reply(description = "The event", schema = one),
                errors = mapOf(404 to "Unknown event.", 422 to "Note too long."),
            ) { call ->
                val id = call.path("id")
                find(id)
                val b = call.bodyObject()
                b.allowOnly("note")
                val n = note(b) ?: throw badRequest("'note' is required")
                val e = backend.setEventNote(id, n) ?: throw notFound("event_not_found", "No event '$id'")
                ok(buildJsonObject { put("event", eventJson(e)) })
            },
            Route(
                "DELETE", "/api/v1/events/{id}", RouteAccess.Needs(Scope.Config), TAG, "deleteEvent",
                "Delete an event",
                "Deletes the event and its snapshot from the phone (points already exported stay in TimescaleDB).",
                params = listOf(idParam),
                reply = Reply(204, "Deleted"),
                errors = mapOf(404 to "Unknown event."),
            ) { call ->
                val id = call.path("id")
                if (!backend.deleteEvent(id)) throw notFound("event_not_found", "No event '$id'; see GET /api/v1/events")
                HttpResponse.Bytes(204, HttpServer.JSON, ByteArray(0))
            },
        )
    }

    companion object {
        const val TAG = "Events"

        const val TAG_DESCRIPTION = "Driver events: one tap on « Événement » (phone, Android Auto, notification) or POST /api/v1/events marks the instant something was felt or heard. Each keeps the values read around it; Grafana finds them as points `ecu=bimmerdata`, `id=event` (label = note), the CSV recording as a marker row `event`."

        internal fun schemas(): Map<String, JsonObject> = mapOf(
            "DriveEvent" to S.obj("A driver event.", listOf("id", "timeMs"), false, null,
                "id" to S.str(example = "ev-mhz3k2a1-1x2"),
                "timeMs" to S.int("Time of the tap, epoch ms.", format = "int64"),
                "carId" to S.str(), "carLabel" to S.str(), "profileId" to S.str(), "profileName" to S.str(),
                "latitude" to S.num(), "longitude" to S.num(), "speedKmh" to S.num("GPS speed at the tap."),
                "note" to S.str(), "source" to S.str(enum = EventSource.entries.map { it.name }),
                "snapshotFromMs" to S.int(format = "int64"), "snapshotToMs" to S.int(format = "int64"),
                "snapshotComplete" to S.bool("False while the values after the tap are still being kept."),
                "values" to S.int("Values in the snapshot.")),
            "EventSnapshot" to S.obj("Values read around an event.", listOf("eventTimeMs", "fromMs", "toMs", "series"), false, null,
                "eventTimeMs" to S.int(format = "int64"), "fromMs" to S.int(format = "int64"), "toMs" to S.int(format = "int64"),
                "series" to S.arr(S.obj(null, listOf("id", "t", "v"), false, null,
                    "id" to S.str(example = "<sgbd>/<id>"), "label" to S.str(), "unit" to S.str(),
                    "t" to S.arr(S.int(format = "int64"), "Times, ms from the event (negative before)."),
                    "v" to S.arr(S.num(), "Values, same order as t.")))),
        )
    }
}
