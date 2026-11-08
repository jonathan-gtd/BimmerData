/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import android.content.Context
import io.github.jonathangtd.bimmerdata.dashboard.fake.FakeLiveDataSource
import io.github.jonathangtd.bimmerdata.dashboard.fake.FakeMeasurementCatalog
import io.github.jonathangtd.bimmerdata.dashboard.fake.FakeSessionRecorder
import io.github.jonathangtd.bimmerdata.live.DetectedEcus
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveDataSource
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.MeasurementClient
import io.github.jonathangtd.bimmerdata.live.MeasurementRequests
import io.github.jonathangtd.bimmerdata.live.SessionRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Everything the dashboard needs from the acquisition side. */
interface DashboardDependencies {
    val liveDataSource: LiveDataSource
    val recorder: SessionRecorder
    val catalog: MeasurementCatalog
    val connection: StateFlow<LiveConnection>
    fun connect(mode: LiveMode)
    fun disconnect()

    /** Measurements [client] shows (see [MeasurementRequests] for how phone and car share the engine). */
    fun requestMeasurements(client: MeasurementClient, measurements: List<Measurement>, foreground: Boolean)
    fun clearMeasurements(client: MeasurementClient)
}

/**
 * Hooks other features plug into the dashboard, process-wide (set them once, e.g. at startup or
 * when a vehicle has been identified).
 */
object DashboardHooks {
    /**
     * SGBDs found in the connected car by the vehicle identification (lower case, without
     * extension, e.g. "msv70", "kombi87"). The card picker lists them first under "Detected in
     * your car". Called off the main thread each time the picker opens; default: none.
     */
    @Volatile var detectedEcus: DetectedEcus = DetectedEcus.None
}

/** The single place that decides where live data comes from: the real acquisition engine. */
fun dashboardDependencies(context: Context): DashboardDependencies {
    // The card picker lists the ECUs found by the vehicle scan first ("Detected in your car").
    val vehicles = io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository.get(context)
    DashboardHooks.detectedEcus = io.github.jonathangtd.bimmerdata.live.DetectedEcus { vehicles.sgbds() }
    return LiveDashboardDependencies(LiveRepository.get(context))
}

/** Real data: K+DCAN cable or the built-in simulated ECU, through [LiveRepository]. */
class LiveDashboardDependencies(private val repo: LiveRepository) : DashboardDependencies {
    override val liveDataSource: LiveDataSource get() = repo.liveDataSource
    override val recorder: SessionRecorder get() = repo.recorder
    override val catalog: MeasurementCatalog get() = repo.catalog
    override val connection: StateFlow<LiveConnection> get() = repo.connection
    override fun connect(mode: LiveMode) = repo.connect(mode)
    override fun disconnect() = repo.disconnect()
    override fun requestMeasurements(client: MeasurementClient, measurements: List<Measurement>, foreground: Boolean) =
        repo.requests.set(client, measurements, foreground)
    override fun clearMeasurements(client: MeasurementClient) = repo.requests.clear(client)
}

/** Fully in-process fake data (UI development and previews), no ECU file needed. */
class FakeDashboardDependencies private constructor() : DashboardDependencies {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val liveDataSource: LiveDataSource = FakeLiveDataSource(scope)
    override val recorder: SessionRecorder = FakeSessionRecorder(scope, liveDataSource)
    override val catalog: MeasurementCatalog = FakeMeasurementCatalog()
    private val _connection = MutableStateFlow<LiveConnection>(LiveConnection.Connected(LiveMode.Simulation, "Fake data"))
    override val connection: StateFlow<LiveConnection> = _connection
    override fun connect(mode: LiveMode) { _connection.value = LiveConnection.Connected(mode, "Fake data") }
    override fun disconnect() { _connection.value = LiveConnection.Disconnected }
    private val requests = MeasurementRequests(liveDataSource::setMeasurements)
    override fun requestMeasurements(client: MeasurementClient, measurements: List<Measurement>, foreground: Boolean) =
        requests.set(client, measurements, foreground)
    override fun clearMeasurements(client: MeasurementClient) = requests.clear(client)

    companion object {
        @Volatile private var instance: FakeDashboardDependencies? = null

        @Suppress("UNUSED_PARAMETER")
        fun get(context: Context): FakeDashboardDependencies =
            instance ?: synchronized(this) { instance ?: FakeDashboardDependencies().also { instance = it } }
    }
}
