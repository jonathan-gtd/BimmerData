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
import androidx.lifecycle.lifecycleScope
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Profile choice from the car: the profiles of the active profile's car (all of them when it is
 * linked to no car). Creating and editing profiles happens on the phone.
 */
class ProfilesCarScreen(carContext: CarContext) : Screen(carContext) {
    private val store = ProfileStore.get(carContext)

    override fun onGetTemplate(): Template {
        val set = ProfileStore.state.value ?: store.load()
        val list = ItemList.Builder()
        val shown = Profiles.sameCar(set).take(MAX_ROWS)
        if (shown.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.car_no_profile))
        for (p in shown) {
            val details = listOfNotNull(
                p.vehicle.label.takeIf { it.isNotEmpty() },
                p.ecus.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.uppercase() },
            ).joinToString(" · ")
            val row = Row.Builder().setTitle(p.name).setOnClickListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.activate(p.id) }
                    screenManager.pop()
                }
            }
            val text = if (p.id == set.activeId) carContext.getString(R.string.car_profile_active) + (if (details.isNotEmpty()) " · $details" else "") else details
            if (text.isNotEmpty()) row.addText(text)
            list.addItem(row.build())
        }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.profiles_title))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private companion object {
        /** Lists in Android Auto are limited (6 items on many hosts while driving). */
        const val MAX_ROWS = 6
    }
}
