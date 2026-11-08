/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.jonathangtd.bimmerdata.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Saves the layout as JSON in app storage (atomic writes, so a crash never corrupts it). */
class DashboardStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "dashboard.json"))

    /**
     * The saved dashboard (an existing layout is never changed), or on first launch (or if the
     * file is unreadable) the default one: an empty page on the phone and one in Android Auto.
     */
    fun load(): Dashboard {
        val text = runCatching { file.readFully().toString(Charsets.UTF_8) }.getOrNull()
        if (text != null) {
            runCatching { return DashboardJson.decode(text) }
                .onFailure { Log.w(TAG, "Unreadable dashboard.json, using the default layout", it) }
        }
        return defaultDashboard(context)
    }

    fun save(dashboard: Dashboard) {
        val out = file.startWrite()
        try {
            out.write(DashboardJson.encode(dashboard).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
            _saved.value = dashboard
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "Could not save the dashboard", e)
        }
    }

    companion object {
        private const val TAG = "DashboardStore"

        private val _saved = MutableStateFlow<Dashboard?>(null)

        /** Last layout saved in this process (the Android Auto screen follows phone edits with it). */
        val saved: StateFlow<Dashboard?> = _saved.asStateFlow()

        fun exampleLabels(context: Context) = with(context) {
            ExampleLabels(
                pageEngine = getString(R.string.default_page_engine),
                pageMixture = getString(R.string.default_page_mixture),
                pageTemperatures = getString(R.string.default_page_temperatures),
                oil = getString(R.string.default_oil),
                coolant = getString(R.string.default_coolant),
                fuelTrim = getString(R.string.default_fuel_trim),
                fuelTrimShort = getString(R.string.default_fuel_trim_short),
                rpm = getString(R.string.default_rpm),
                lambda = getString(R.string.default_lambda),
                lambdaB1 = getString(R.string.default_lambda_b1),
                lambdaB2 = getString(R.string.default_lambda_b2),
                ignition = getString(R.string.default_ignition),
                load = getString(R.string.default_load),
                intakeAir = getString(R.string.default_intake_air),
                battery = getString(R.string.default_battery),
                speed = getString(R.string.default_speed),
                bank1 = getString(R.string.default_bank1),
                bank2 = getString(R.string.default_bank2),
                trimB1 = getString(R.string.default_trim_b1),
                trimB2 = getString(R.string.default_trim_b2),
            )
        }

        fun defaultDashboard(context: Context): Dashboard = DefaultDashboard.create(context.getString(R.string.new_page, 1))

        /** The optional MSV70 (N52) example layout, localized. */
        fun msv70Example(context: Context): Dashboard = Msv70Example.create(exampleLabels(context))
    }
}
