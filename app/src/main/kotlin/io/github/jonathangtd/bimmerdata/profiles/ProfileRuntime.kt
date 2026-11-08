/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.profiles

import android.content.Context
import android.util.Log
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

/** What the profile checks at connection have to tell the user (shown by the dashboard). */
sealed interface ProfileNotice {
    /** The car was identified and its profile [name] is now active. */
    data class Switched(val name: String) : ProfileNotice

    /** The car matches profile [id] but a recording runs: only proposed. */
    data class Suggest(val id: String, val name: String) : ProfileNotice

    /** These ECUs of the active profile do not answer (absent, asleep, or a protocol the app lacks). */
    data class EcusNotResponding(val profile: String, val ecus: List<String>) : ProfileNotice
}

/**
 * Checks made each time a connection is established, a moment after it (the reading starts first):
 *
 * 1. **Profile of the car**: when at least one profile is linked to a car, the short VIN is read
 *    (one read-only functional job) and, if profiles are linked to that car and the active one is
 *    not among them, the one used last becomes active. Never during a recording: then it is only
 *    proposed ([ProfileNotice.Suggest]).
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
        val store = ProfileStore.get(app)
        val vehicles = VehicleRepository.get(app)
        var set = store.load()
        if (set.profiles.any { it.vehicle.vehicleId != null }) {
            val vin = vehicles.readShortVin()
            val carId = vin?.let { vehicles.vehicleIdForVin(it) ?: it }
            val pick = carId?.let { Profiles.autoSelect(set, it) }
            if (pick != null) {
                if (LiveRepository.get(app).recorder.state.value.recording) {
                    _notices.tryEmit(ProfileNotice.Suggest(pick.id, pick.name))
                } else {
                    store.activate(pick.id)?.let { set = it }
                    _notices.tryEmit(ProfileNotice.Switched(pick.name))
                }
            }
        }
        val active = set.active ?: return
        if (active.ecus.isEmpty()) return
        val silent = vehicles.probeEcus(active.ecus)
        if (silent.isNotEmpty()) _notices.tryEmit(ProfileNotice.EcusNotResponding(active.name, silent))
    }
}
