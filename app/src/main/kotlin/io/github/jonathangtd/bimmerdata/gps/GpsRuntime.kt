/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.live.AcquisitionService
import io.github.jonathangtd.bimmerdata.live.GpsFix
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.PhoneSensors
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import io.github.jonathangtd.bimmerdata.settings.GpsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Where the GPS position stands (Settings › GPS position). */
sealed interface GpsState {
    /** Switched off in the settings. */
    data object Off : GpsState

    /** On, waiting for a connection to the car (the position is recorded with the ECU values). */
    data object Idle : GpsState

    /** On, but the location permission is missing. */
    data object NoPermission : GpsState

    /** On, but the phone's location is switched off. */
    data object LocationOff : GpsState

    /** Asking for fixes, none yet. */
    data object Searching : GpsState

    data class Fix(val timeMs: Long, val accuracyM: Double?) : GpsState
}

/**
 * Feeds the phone's GPS position into the live samples as the `gps/...` measurements, while the
 * option is on and the app is connected to the car: they reach the cards, the CSV (always
 * recorded, even without a card) and the Telegraf export like any ECU value. Uses the platform
 * [LocationManager] (GPS provider), no Google Play services.
 */
class GpsRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val settings = AppSettings.get(app)
    private val repo = LiveRepository.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permissionTick = MutableStateFlow(0)
    private val _state = MutableStateFlow<GpsState>(GpsState.Off)
    val state: StateFlow<GpsState> = _state.asStateFlow()

    private var listener: LocationListenerCompat? = null
    private var activeInterval = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                settings.state.map { it.gps }.distinctUntilChanged(),
                repo.connection.map { it is LiveConnection.Connected }.distinctUntilChanged(),
                permissionTick,
            ) { gps, connected, _ -> gps to connected }
                .collect { (gps, connected) -> apply(gps, connected) }
        }
    }

    /** Call after the permission dialog or when coming back from the system settings. */
    fun recheck() {
        permissionTick.value++
    }

    fun hasPermission(): Boolean = hasPermission(app)

    fun locationEnabled(): Boolean {
        val lm = app.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    @SuppressLint("MissingPermission") // checked in hasPermission()
    private fun apply(gps: GpsSettings, connected: Boolean) {
        val wanted = gps.enabled && connected && hasPermission()
        if (!wanted || activeInterval != gps.intervalMs) stopUpdates()
        when {
            !gps.enabled -> _state.value = GpsState.Off
            !hasPermission() -> _state.value = GpsState.NoPermission
            !connected -> _state.value = GpsState.Idle
            !locationEnabled() -> _state.value = GpsState.LocationOff
        }
        if (!wanted) {
            repo.phoneSensors.setSensorMeasurements(emptyList())
            return
        }
        repo.phoneSensors.setSensorMeasurements(PhoneSensorTexts.gpsMeasurements(app))
        if (listener != null) return
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        val l = LocationListenerCompat { loc -> onLocation(loc) }
        val request = LocationRequestCompat.Builder(gps.intervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(gps.intervalMs)
            .build()
        try {
            LocationManagerCompat.requestLocationUpdates(lm, LocationManager.GPS_PROVIDER, request, ContextCompat.getMainExecutor(app), l)
            listener = l
            activeInterval = gps.intervalMs
            if (locationEnabled()) _state.value = GpsState.Searching
            // in USB mode, the running acquisition service takes the location type too (screen off)
            restartServiceIfRunning()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start the GPS", e)
            _state.value = GpsState.LocationOff
        }
    }

    private fun onLocation(loc: Location) {
        val fix = GpsFix(
            timeMs = GpsFix.wallTime(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), loc.elapsedRealtimeNanos),
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitudeM = if (loc.hasAltitude()) loc.altitude else null,
            speedMs = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
        )
        repo.phoneSensors.putSensorSamples(fix.toSamples())
        _state.value = GpsState.Fix(fix.timeMs, fix.accuracyM)
    }

    private fun stopUpdates() {
        val l = listener ?: return
        listener = null
        activeInterval = 0
        try {
            app.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.removeUpdates(it, l) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot stop the GPS updates", e) // permission revoked meanwhile
        }
    }

    private fun restartServiceIfRunning() {
        val c = repo.connection.value
        if (c !is LiveConnection.Connected || c.mode == io.github.jonathangtd.bimmerdata.live.LiveMode.Simulation) return
        runCatching { ContextCompat.startForegroundService(app, Intent(app, AcquisitionService::class.java)) }
            .onFailure { Log.w(TAG, "Cannot update the acquisition service", it) }
    }

    companion object {
        private const val TAG = "GpsRuntime"

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // holds only the application context
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: GpsRuntime? = null

        fun get(context: Context): GpsRuntime =
            instance ?: synchronized(this) { instance ?: GpsRuntime(context).also { instance = it } }
    }
}

/** Translated labels of the GPS measurements. */
object PhoneSensorTexts {
    fun label(context: Context, f: PhoneSensors.GpsField): String = context.getString(
        when (f) {
            PhoneSensors.GpsField.Latitude -> R.string.s3_gps_latitude
            PhoneSensors.GpsField.Longitude -> R.string.s3_gps_longitude
            PhoneSensors.GpsField.Altitude -> R.string.s3_gps_altitude
            PhoneSensors.GpsField.Speed -> R.string.s3_gps_speed
            PhoneSensors.GpsField.Bearing -> R.string.s3_gps_bearing
            PhoneSensors.GpsField.Accuracy -> R.string.s3_gps_accuracy
        },
    )

    fun gpsMeasurements(context: Context): List<Measurement> = PhoneSensors.gpsMeasurements { label(context, it) }
        .map { it.copy(description = context.getString(R.string.s3_gps_measurement_hint)) }
}

/**
 * Puts the acquisition service in the foreground as connectedDevice, plus location while the GPS
 * position is recorded. If Android refuses the location type (e.g. started from the background),
 * it falls back to connectedDevice alone: the ECU reading must not depend on the GPS.
 */
internal fun startAcquisitionForeground(service: Service, id: Int, notification: Notification) {
    val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    val gps = AppSettings.get(service).state.value.gps.enabled && GpsRuntime.hasPermission(service)
    if (gps) {
        try {
            ServiceCompat.startForeground(service, id, notification, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            return
        } catch (e: Exception) {
            Log.w("GpsRuntime", "Location service type refused, GPS only while the app is visible", e)
        }
    }
    ServiceCompat.startForeground(service, id, notification, base)
}
