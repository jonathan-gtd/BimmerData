/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.cars

import android.annotation.SuppressLint
import android.content.Context
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileJson
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The app's cars and profiles as one: the garage lives in [VehicleRepository] (`files/vehicles.json`),
 * the profiles in [ProfileStore] (`files/profiles.json`). Every change that touches both runs
 * [Cars] operations in [edit], under the profile store's lock, the garage being swapped only if no
 * scan changed it meanwhile (else the operation runs again on the new garage).
 *
 * The store passes each set it reads or writes through [repair] (migration, active profile kept on
 * the selected car), and a change of selected car made elsewhere (a scan in the Faults tab) makes
 * the store read again, so the active profile follows the car.
 *
 * Blocking (file writes): call it off the main thread.
 */
class CarBook private constructor(context: Context) {
    private val app: Context = context.applicationContext
    private val vehicles = VehicleRepository.get(app)

    // not in the constructor: ProfileStore.get builds this book first (its carCheck)
    private val profiles: ProfileStore get() = ProfileStore.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            vehicles.state.map { it.garage.currentId }.distinctUntilChanged().drop(1).collect { profiles.load() }
        }
    }

    /** Cars and profiles as they are now. */
    fun snapshot(): CarSpace = CarSpace(vehicles.garage(), profiles.load())

    /**
     * Applies [op] to the cars and profiles with no other profile write in between; returns its
     * result. Exceptions of [op] go through unchanged (nothing is written); [IllegalStateException]
     * when the profiles could not be written.
     */
    fun <T> edit(op: (CarSpace) -> Pair<CarSpace, T>): T {
        var result: Result<T>? = null
        val written = profiles.update { set ->
            val (next, r) = swapGarage { garage -> op(CarSpace(garage, set)).let { (space, r) -> space.garage to (space.profiles to r) } }
            result = Result.success(r)
            next
        }
        checkNotNull(written) { "the profiles could not be written" }
        return result!!.getOrThrow()
    }

    /** Runs [compute] on the garage and swaps in its result, again on the newer garage if a scan changed it meanwhile. */
    private inline fun <R> swapGarage(compute: (Garage) -> Pair<Garage, R>): R {
        while (true) {
            val garage = vehicles.garage()
            val (next, r) = compute(garage)
            if (next == garage || vehicles.replaceGarage(garage, next)) return r
        }
    }

    private fun change(op: (CarSpace) -> CarSpace) = edit { op(it) to Unit }

    private fun now() = System.currentTimeMillis()

    /** For [ProfileStore]: migration and active profile on the selected car ([Cars.repair]). */
    fun repair(set: ProfileSet): ProfileSet = swapGarage { garage ->
        Cars.repair(CarSpace(garage, set), defaultLabel(), now()).let { it.garage to it.profiles }
    }

    fun defaultLabel(): String = app.getString(R.string.cars_default_name)

    // ---- what the screens and the connection checks do ----

    fun select(id: String) = change { Cars.select(it, id, now()) }

    /** Adds a car by hand and selects it. */
    fun create(label: String, series: String?, model: String?): Vehicle = edit { space ->
        val (withCar, car) = Cars.create(space, label, series, model, nowMs = now())
        Cars.select(withCar, car.id, now()) to car
    }

    fun update(id: String, label: String? = null, sgbds: List<String>? = null) = change { Cars.update(it, id, label = label, sgbds = sgbds) }

    /** Deletes a car with its profiles and fault read (after the user's confirmation). */
    fun delete(id: String) = change { Cars.delete(it, id, now()) }

    /** Activates a profile, switching to its car if needed. */
    fun activateProfile(id: String) = change { Cars.activateProfile(it, id, now()) }

    /** New profile of the selected car; [NoCarException] when there is no car yet. */
    fun createProfile(name: String, vehicle: ProfileVehicle, ecus: List<String>): Profile = edit { space ->
        val (withCar, car) = Cars.selectedOrFirst(space, now()) ?: throw NoCarException()
        Cars.createProfile(withCar, car.id, name, vehicle, ecus, now())
    }

    /** Changes a profile; [dropCards]: ECUs unticked whose cards and graph values the user chose to delete too. */
    fun editProfile(id: String, name: String, vehicle: ProfileVehicle, ecus: List<String>, dropCards: Collection<String>) =
        change { Cars.editProfile(it, id, name, vehicle, ecus, dropCards) }

    /** Which profiles of car [carId] use [sgbd] ([Cars.ecusInUse]); empty when none. */
    fun ecuUses(carId: String, sgbd: String): List<EcuUse> = Cars.ecusInUse(snapshot(), carId, listOf(sgbd))[sgbd].orEmpty()

    /** The user's "Remove" ([Cars.removeEcu]); returns the state before, for [restoreCar] (undo). */
    fun removeEcu(carId: String, sgbd: String): CarSpace = edit { space -> Cars.removeEcu(space, carId, sgbd) to space }

    /** Undo of [removeEcu]. */
    fun restoreCar(before: CarSpace, carId: String) = change { Cars.restoreCar(it, before, carId) }

    /** Copy of a profile onto car [carId]; null when either is gone. */
    fun copyProfile(id: String, carId: String): Profile? = edit { space ->
        Cars.copyProfile(space, id, carId, now()) ?: (space to null)
    }

    /** A profile file as a new, active profile of the selected car; throws [IllegalArgumentException] when it is not one, [NoCarException] without car. */
    fun importProfile(text: String, fallbackName: String): Profile {
        val export = ProfileJson.parseExport(text)
        return edit { space ->
            val (withCar, car) = Cars.selectedOrFirst(space, now()) ?: throw NoCarException()
            Cars.importProfile(withCar, export, car.id, now(), fallbackName)
        }
    }

    /** The VIN read at connection ([Cars.onVin]). */
    fun onVin(vinShort: String, recording: Boolean): VinOutcome = edit { space ->
        Cars.onVin(space, vinShort, recording, app.getString(R.string.cars_vin_name, vinShort), now())
    }

    companion object {
        // holds only the application context
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: CarBook? = null

        fun get(context: Context): CarBook =
            instance ?: synchronized(this) { instance ?: CarBook(context).also { instance = it } }
    }
}

/** A profile was asked for while there is no car: a profile belongs to a car, add one first. */
class NoCarException : IllegalStateException("No car yet: add a car first, or connect to it (its VIN adds it)")
