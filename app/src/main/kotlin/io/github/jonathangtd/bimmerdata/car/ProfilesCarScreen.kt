/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.lifecycleScope
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Profile choice from the car: the profiles of the selected car (that of the active profile), the
 * active one first, then the most recently used ([Profiles.carChoice]), as many as the host lists.
 * Creating and editing profiles, and changing car, happen on the phone.
 */
class ProfilesCarScreen(carContext: CarContext) : Screen(carContext) {
    private val store = ProfileStore.get(carContext)

    override fun onGetTemplate(): Template {
        val set = ProfileStore.state.value ?: store.load()
        val list = ItemList.Builder()
        val shown = Profiles.carChoice(set).take(maxRows())
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

    /** The host's list limit (car API 2+; often 6 while driving, more when parked), else [DEFAULT_ROWS]. */
    private fun maxRows(): Int {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_2) return DEFAULT_ROWS
        return runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(DEFAULT_ROWS).coerceAtLeast(1)
    }

    private companion object {
        /** Lists in Android Auto are limited (6 items on many hosts while driving). */
        const val DEFAULT_ROWS = 6
    }
}
