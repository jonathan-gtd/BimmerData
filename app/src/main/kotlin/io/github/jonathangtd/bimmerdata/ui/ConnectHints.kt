/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.settings.AppSettings

/**
 * Instructions about the diagnostic interface, worded for the mode the user actually uses
 * (the last one chosen), never for one kind of hardware only. A new interface mode only needs
 * its own branch here: every `when` is exhaustive, so the compiler points at what to add.
 */
object ConnectHints {
    /** The interface the instructions are about: last mode chosen (null = never chosen) and Bluetooth adapter name. */
    data class Target(val mode: LiveMode?, val adapterName: String?)

    /** What to do before connecting or scanning; null when there is nothing to prepare (simulation). */
    fun prepare(res: Resources, t: Target): String? = when (t.mode) {
        null -> res.getString(R.string.fb3_prepare_any)
        LiveMode.Usb -> res.getString(R.string.fb3_prepare_usb)
        LiveMode.Bluetooth -> t.adapterName?.let { res.getString(R.string.fb3_prepare_bt, it) } ?: res.getString(R.string.fb3_prepare_bt_unnamed)
        LiveMode.Simulation -> null
    }

    /** What to check when no ECU answers; empty in simulation (nothing to plug). */
    fun check(res: Resources, t: Target): String = when (t.mode) {
        null -> res.getString(R.string.fb3_check_any)
        LiveMode.Usb -> res.getString(R.string.fb3_check_usb)
        LiveMode.Bluetooth -> t.adapterName?.let { res.getString(R.string.fb3_check_bt, it) } ?: res.getString(R.string.fb3_check_bt_unnamed)
        LiveMode.Simulation -> ""
    }

    /** The interface of the user's settings, followed live. */
    @Composable
    fun rememberTarget(): Target {
        val context = LocalContext.current
        val store = remember { AppSettings.get(context) }
        val s by store.state.collectAsStateWithLifecycle()
        return Target(s.lastMode, s.bluetooth.name?.takeIf { it.isNotBlank() })
    }

    @Composable
    fun prepare(t: Target = rememberTarget()): String? = prepare(LocalResources.current, t)

    @Composable
    fun check(t: Target = rememberTarget()): String = check(LocalResources.current, t)
}
