/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.profiles

import android.content.Context
import android.util.Log
import io.github.jonathangtd.bimmerdata.cars.CarBook
import io.github.jonathangtd.bimmerdata.cars.VinOutcome
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the checks at connection have to tell the user (shown by the dashboard). */
sealed interface ProfileNotice {
    /** The car was identified: [car] is now selected, with its last used [profile] (null: it has none yet). */
    data class CarSwitched(val car: String, val profile: String?) : ProfileNotice

    /** An unknown VIN: a new car [car] was added (and selected unless a recording runs). */
    data class NewCar(val car: String) : ProfileNotice

    /** The car matches profile [id] (of another car) but a recording runs: only proposed. */
    data class Suggest(val id: String, val name: String) : ProfileNotice

    /** These ECUs of the active profile do not answer (absent, asleep, or a protocol the app lacks). */
    data class EcusNotResponding(val profile: String, val ecus: List<String>) : ProfileNotice
}

/**
 * Checks made each time a connection is established, a moment after it (the reading starts first):
 *
 * 1. **The car**: the short VIN is read (one read-only functional job, about a second) and
 *    [CarBook.onVin] decides ([io.github.jonathangtd.bimmerdata.cars.Cars.onVin]): a known car
 *    becomes the selected one with its last used profile, the selected car added by hand gets the
 *    VIN, an unknown VIN adds a new car. Never during a recording: another car's profile is then
 *    only proposed ([ProfileNotice.Suggest]).
 * 2. **ECUs of the profile**: each one gets an IDENT (read-only); those that do not answer are
 *    reported ([ProfileNotice.EcusNotResponding]), nothing is blocked.
 */
object ProfileRuntime {
    private const val TAG = "ProfileRuntime"

    /** Lets the live reading start before the one-off jobs of the checks. */
    private const val SETTLE_MS = 1_500L

    private val _notices = MutableSharedFlow<ProfileNotice>(extraBufferCapacity = 4)
    val notices: SharedFlow<ProfileNotice> = _notices.asSharedFlow()

    @Volatile private var started = false
    private var check: Job? = null

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            var wasConnected = false
            LiveRepository.get(app).connection.collect { c ->
                val connected = c is LiveConnection.Connected
                if (connected && !wasConnected) {
                    check?.cancel()
                    check = scope.launch { runCatching { onConnected(app) }.onFailure { Log.w(TAG, "Profile checks failed", it) } }
                } else if (!connected) {
                    check?.cancel()
                }
                wasConnected = connected
            }
        }
    }

    private suspend fun onConnected(app: Context) {
        delay(SETTLE_MS)
        val vehicles = VehicleRepository.get(app)
        vehicles.readShortVin()?.let { vin ->
            val recording = LiveRepository.get(app).recorder.state.value.recording
            when (val o = withContext(Dispatchers.IO) { CarBook.get(app).onVin(vin, recording) }) {
                is VinOutcome.Switched -> _notices.tryEmit(ProfileNotice.CarSwitched(o.car.label, o.profile?.name))
                is VinOutcome.Suggested -> _notices.tryEmit(ProfileNotice.Suggest(o.profile.id, o.profile.name))
                is VinOutcome.Created -> _notices.tryEmit(ProfileNotice.NewCar(o.car.label))
                is VinOutcome.Attached -> Log.i(TAG, "VIN of the car added by hand read at its first connection")
                VinOutcome.Unchanged -> Unit
            }
        }
        val active = withContext(Dispatchers.IO) { ProfileStore.get(app).load() }.active ?: return
        if (active.ecus.isEmpty()) return
        val silent = vehicles.probeEcus(active.ecus)
        if (silent.isNotEmpty()) _notices.tryEmit(ProfileNotice.EcusNotResponding(active.name, silent))
    }
}
