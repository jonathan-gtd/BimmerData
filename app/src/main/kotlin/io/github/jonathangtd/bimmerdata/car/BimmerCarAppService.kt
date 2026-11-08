/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.car

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for Android Auto (Car App Library). Declared as a navigation app so the host gives
 * it a full-screen drawing surface: the dashboard is drawn there with the phone's car renderer.
 * BimmerData never starts route guidance, so the user's navigation app keeps running.
 */
class BimmerCarAppService : CarAppService() {
    // The library's allowlist is the one its documentation tells apps to use.
    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Android Auto and the Android Automotive templates host, by package and signature.
            HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(): Session = DashboardCarSession()
}

class DashboardCarSession : Session() {
    private var screen: DashboardCarScreen? = null

    override fun onCreateScreen(intent: Intent): Screen = DashboardCarScreen(carContext).also { screen = it }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        screen?.onDarkModeChanged(carContext.isDarkMode)
    }
}
