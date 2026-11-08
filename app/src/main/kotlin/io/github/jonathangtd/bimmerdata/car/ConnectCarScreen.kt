/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.DashboardDependencies
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode

/** Connection choice from the car: K+DCAN cable, simulated ECU, or disconnect. */
class ConnectCarScreen(carContext: CarContext, private val deps: DashboardDependencies) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val c = deps.connection.value
        val current = when (c) {
            is LiveConnection.Connected -> c.mode
            is LiveConnection.Connecting -> c.mode
            else -> null
        }
        val list = ItemList.Builder()
        list.addItem(row(R.string.conn_usb, if (current == LiveMode.Usb) R.string.car_connected else R.string.car_usb_hint) { connect(LiveMode.Usb) })
        list.addItem(row(R.string.conn_sim, if (current == LiveMode.Simulation) R.string.car_connected else R.string.car_sim_hint) { connect(LiveMode.Simulation) })
        if (current != null) list.addItem(row(R.string.conn_disconnect, null) { deps.disconnect(); screenManager.pop() })
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_connection))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun row(title: Int, text: Int?, onClick: () -> Unit): Row {
        val b = Row.Builder().setTitle(carContext.getString(title)).setOnClickListener(onClick)
        if (text != null) b.addText(carContext.getString(text))
        return b.build()
    }

    private fun connect(mode: LiveMode) {
        deps.connect(mode)
        screenManager.pop()
    }
}
