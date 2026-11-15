/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.events

import android.content.Context
import android.util.Log
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.gps.PhoneSensorTexts
import io.github.jonathangtd.bimmerdata.graph.SharedHistory
import io.github.jonathangtd.bimmerdata.live.EventPoints
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.PhoneSensors
import io.github.jonathangtd.bimmerdata.live.ReadStatsPoints
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Marks driver events ([mark]) from every place that offers the button (phone dashboard, Android
 * Auto, notification, REST API) and keeps them ([store], `files/events`). At the tap: the event
 * is saved at once with the car, the profile and the last GPS fix; a reading `bimmerdata/event`
 * goes to the Telegraf export and a marker row to the running CSV recording; the snapshot takes
 * the values already in [SharedHistory] (up to [EventSnapshots.BEFORE_MS] back), then is
 * completed [EventSnapshots.AFTER_MS] later, or as soon as the connection ends.
 */
object EventRuntime {
    private const val TAG = "BimmerData.Event"

    /** Snapshots still capturing after this long were cut by the end of the process. */
    private const val STALE_MS = EventSnapshots.AFTER_MS + 60_000L

    @Volatile private var store: EventStore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun store(context: Context): EventStore = store ?: synchronized(this) {
        store ?: EventStore(File(context.applicationContext.filesDir, "events")).also { s ->
            store = s
            // captures cut by the end of the app: nothing more will come
            val now = System.currentTimeMillis()
            s.all().filter { !it.snapshotComplete && now - it.timeMs > STALE_MS }.forEach { e ->
                s.snapshot(e.id)?.let { snap -> s.completeSnapshot(e.id, snap, complete = true) } ?: s.completeSnapshot(e.id, EventSnapshot(e.timeMs, e.timeMs, e.timeMs), complete = true)
            }
        }
    }

    /**
     * A driver event now, with [note] (may be blank); any thread. Returns it as saved (its
     * snapshot is filled in the background).
     */
    fun mark(context: Context, note: String?, source: EventSource): DriveEvent {
        val app = context.applicationContext
        val repo = LiveRepository.get(app)
        val now = System.currentTimeMillis()
        val text = Events.cleanNote(note)
        val profile = ProfileStore.state.value?.active
        val garage = VehicleRepository.get(app).state.value.garage
        val car = profile?.carId?.let { id -> garage.vehicles.firstOrNull { it.id == id } } ?: garage.current
        val samples = repo.liveDataSource.samples.value
        fun gps(f: PhoneSensors.GpsField): Double? = samples[f.id]?.takeIf { now - it.timestampMs <= 30_000 }?.value?.takeIf { it.isFinite() }
        val event = store(app).add(
            DriveEvent(
                id = Events.newId(now), timeMs = now,
                carId = car?.id, carLabel = car?.label, profileId = profile?.id, profileName = profile?.name,
                latitude = gps(PhoneSensors.GpsField.Latitude), longitude = gps(PhoneSensors.GpsField.Longitude), speedKmh = gps(PhoneSensors.GpsField.Speed),
                note = text, source = source,
            ),
            snapshot = null,
        )
        repo.putEvent(now, text.ifBlank { app.getString(R.string.event_label) }, EventPoints.csvMarker(text))
        Log.i(TAG, "Event ${event.id} from $source")
        scope.launch { capture(app, repo, event) }
        return event
    }

    /** The snapshot now, then once [EventSnapshots.AFTER_MS] has passed or the connection ended. */
    private suspend fun capture(app: Context, repo: LiveRepository, event: DriveEvent) {
        val store = store(app)
        save(store, event.id, snapshot(app, repo, event.timeMs), complete = false)
        val left = event.timeMs + EventSnapshots.AFTER_MS - System.currentTimeMillis()
        if (left > 0) withTimeoutOrNull(left) { repo.connection.first { it !is LiveConnection.Connected } }
        delay(300) // the last readings on their way
        save(store, event.id, snapshot(app, repo, event.timeMs), complete = true)
    }

    private suspend fun save(store: EventStore, id: String, snapshot: EventSnapshot, complete: Boolean) = withContext(Dispatchers.IO) {
        runCatching { store.completeSnapshot(id, snapshot, complete) }.onFailure { Log.w(TAG, "Snapshot of $id failed", it) }
    }

    /** Main thread ([SharedHistory]); labels from what is read now, the GPS and the read statistics. */
    private suspend fun snapshot(app: Context, repo: LiveRepository, eventTimeMs: Long): EventSnapshot {
        val known = withContext(Dispatchers.Default) {
            (repo.measurementsRead() + PhoneSensorTexts.gpsMeasurements(app)).associate { it.id to ValueInfo(it.label, it.unit) }
        }
        val (from, to) = EventSnapshots.window(eventTimeMs, System.currentTimeMillis())
        val ids = SharedHistory.ids().filterNot(EventPoints::isEvent)
        return EventSnapshots.build(
            eventTimeMs, from, to, ids,
            info = { id -> known[id] ?: ReadStatsPoints.measurement(id)?.let { ValueInfo(it.label, it.unit) } },
            points = SharedHistory::points,
        )
    }
}
