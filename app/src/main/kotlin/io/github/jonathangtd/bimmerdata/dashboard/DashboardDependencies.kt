/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import android.content.Context
import io.github.jonathangtd.bimmerdata.dashboard.fake.FakeLiveDataSource
import io.github.jonathangtd.bimmerdata.dashboard.fake.FakeMeasurementCatalog
import io.github.jonathangtd.bimmerdata.dashboard.fake.FakeSessionRecorder
import io.github.jonathangtd.bimmerdata.live.LiveDataSource
import io.github.jonathangtd.bimmerdata.live.MeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.SessionRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Everything the dashboard needs from the acquisition side, through the :live contract only. */
interface DashboardDependencies {
    val liveDataSource: LiveDataSource
    val recorder: SessionRecorder
    val catalog: MeasurementCatalog
}

/** The single place that decides where live data comes from. */
fun dashboardDependencies(context: Context): DashboardDependencies = FakeDashboardDependencies.get(context)

/** Simulated data until the real acquisition engine is plugged in. */
class FakeDashboardDependencies private constructor() : DashboardDependencies {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val liveDataSource: LiveDataSource = FakeLiveDataSource(scope)
    override val recorder: SessionRecorder = FakeSessionRecorder(scope, liveDataSource)
    override val catalog: MeasurementCatalog = FakeMeasurementCatalog()

    companion object {
        @Volatile private var instance: FakeDashboardDependencies? = null

        @Suppress("UNUSED_PARAMETER")
        fun get(context: Context): FakeDashboardDependencies =
            instance ?: synchronized(this) { instance ?: FakeDashboardDependencies().also { instance = it } }
    }
}
