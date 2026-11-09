/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.cars.Cars
import io.github.jonathangtd.bimmerdata.cars.CarSpace
import io.github.jonathangtd.bimmerdata.cars.EcuUse
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 404 for an unknown car id. */
internal fun requireCar(space: CarSpace, id: String): Vehicle =
    space.car(id) ?: throw notFound("car_not_found", "No car '$id'; see GET /api/v1/cars")

/**
 * 422 when [ecus] names ECUs that [car] does not have ([Cars.foreignEcus]): the error lists them
 * and the car's list, and says how to add them.
 */
internal fun requireCarEcus(car: Vehicle, ecus: List<String>) {
    val foreign = Cars.foreignEcus(car, ecus)
    if (foreign.isEmpty()) return
    throw ApiException(
        422, "ecu_not_in_car",
        "ECU(s) ${foreign.joinToString()} are not in the ECU list of car '${car.label}' (${car.sgbds().joinToString()}); " +
            "add them with PUT /api/v1/cars/${car.id} {\"ecus\": [...]} or detect its ECUs (POST /api/v1/cars/${car.id}/detect)",
        buildJsonObject {
            put("car", car.id)
            put("notInCar", JsonArray(foreign.map(::JsonPrimitive)))
            put("allowed", JsonArray(car.sgbds().map(::JsonPrimitive)))
        },
    )
}

/**
 * 409 `ecu_in_use`: taking the ECUs of [inUse] out of the car's or the profile's list would leave
 * profiles naming them, or cards and graph values with nothing behind them. The API never removes
 * them silently: the client repeats the request with `"removeCards": true` to take them out of
 * those profiles too ([io.github.jonathangtd.bimmerdata.cars.EcuUsage.remove]).
 */
internal fun ecuInUse(inUse: Map<String, List<EcuUse>>): ApiException {
    val cards = inUse.values.sumOf { list -> list.sumOf { it.cards } }
    val profiles = inUse.values.flatten().map { it.profile.id }.distinct().size
    return ApiException(
        409, "ecu_in_use",
        "ECU(s) ${inUse.keys.joinToString()} are used by $profiles profile(s) ($cards card(s)): send \"removeCards\": true to take them out " +
            "of these profiles too (their cards and graph values are deleted), or keep the ECU(s)",
        JsonArray(inUse.map { (sgbd, uses) ->
            buildJsonObject {
                put("ecu", sgbd)
                putJsonArray("profiles") {
                    for (u in uses) addJsonObject {
                        put("id", u.profile.id); put("name", u.profile.name); put("inEcus", u.inEcus); put("cards", u.cards); put("graphSeries", u.graphSeries)
                    }
                }
            }
        }),
    )
}

/**
 * The user's cars: list, add by hand, change (name, series, model, ECU list), delete with their
 * profiles, select; and the copy of a profile onto another car. Part of [ApiService]; pure JVM.
 * The VIN is never returned (like the diag routes), only whether it is known.
 */
internal class CarRoutes(private val backend: ApiBackend, private val clock: () -> Long) {
    private fun ok(element: JsonElement, status: Int = 200) = json(status, element)

    private fun invalidCar(message: String) = ApiException(422, "invalid_car", message)

    private fun carJson(space: CarSpace, car: Vehicle, withProfiles: Boolean = false): JsonObject = buildJsonObject {
        put("id", car.id)
        put("label", car.label)
        put("vinKnown", car.hasVin)
        car.series?.let { put("series", it) }
        car.model?.let { put("model", it) }
        put("ecus", JsonArray(car.sgbds().map(::JsonPrimitive)))
        put("ecusByHand", JsonArray(car.addedSgbds.map(::JsonPrimitive)))
        car.scannedAtMs?.let { put("scannedAtMs", it) }
        val profiles = space.profilesOf(car.id)
        put("profileCount", profiles.size)
        put("selected", car.id == space.garage.currentId)
        if (withProfiles) putJsonArray("profiles") {
            for (p in profiles) addJsonObject { put("id", p.id); put("name", p.name); put("active", p.id == space.profiles.activeId) }
        }
    }

    /** `label` of [b]: trimmed, not empty, at most [Cars.MAX_LABEL_LENGTH]; null when absent. */
    private fun label(b: JsonObject): String? {
        if ("label" !in b) return null
        val raw = b.string("label")?.trim().orEmpty()
        if (raw.isEmpty()) throw invalidCar("'label' may not be empty")
        if (raw.length > Cars.MAX_LABEL_LENGTH) throw invalidCar("'label' has at most ${Cars.MAX_LABEL_LENGTH} characters")
        return raw
    }

    /** `series` / `model` of [b] ("" removes it); null when absent. */
    private fun field(b: JsonObject, key: String): String? {
        if (key !in b) return null
        val v = b.string(key)?.trim().orEmpty()
        if (v.length > Cars.MAX_FIELD_LENGTH) throw invalidCar("'$key' has at most ${Cars.MAX_FIELD_LENGTH} characters")
        return v
    }

    /** `ecus` of [b], normalized; null when absent. */
    private fun ecus(b: JsonObject): List<String>? {
        val list = b.stringList("ecus") ?: return null
        if (list.size > Cars.MAX_ADDED_SGBDS) throw invalidCar("At most ${Cars.MAX_ADDED_SGBDS} ECUs added by hand")
        val bad = list.filter { Cars.cleanSgbds(listOf(it)).isEmpty() }
        if (bad.isNotEmpty()) throw invalidCar("Invalid ECU name(s): ${bad.joinToString()} (SGBD names such as '<sgbd>', see GET /api/v1/ecus)")
        return Cars.cleanSgbds(list)
    }

    fun routes(): List<Route> {
        val carParam = Param("car", "path", "Car id (see GET /api/v1/cars).", S.str(), example = JsonPrimitive("car-a1b2c3d4e5"))
        val profileParam = Param("profile", "path", "Profile id (see GET /api/v1/profiles).", S.str(), example = JsonPrimitive("a1b2c3d4e5"))
        val carFields = arrayOf(
            "label" to S.str("Name shown in My cars (≤ ${Cars.MAX_LABEL_LENGTH}).", "My car"),
            "series" to S.str("Series (free text, ≤ ${Cars.MAX_FIELD_LENGTH}); \"\" removes it.", "<series>"),
            "model" to S.str("Model (free text, ≤ ${Cars.MAX_FIELD_LENGTH}); \"\" removes it.", "<model>"),
            "ecus" to S.arr(S.str(example = "<sgbd>"), "The car's ECU list by hand (SGBD names of the ECU folder): its profiles may only name these. The ECUs found by the last scan always stay in the list.", 0, Cars.MAX_ADDED_SGBDS),
        )
        return listOf(
            Route(
                "GET", "/api/v1/cars", RouteAccess.Needs(Scope.Read), "Cars", "listCars",
                "The user's cars and the selected one",
                "Every car (one per VIN; added at connection, by a scan or by hand) with `selected` set on the one the app shows: the profile routes, the Faults tab and the Telegraf `car` tag follow it. The VIN itself is never returned, only `vinKnown`.",
                reply = Reply(description = "Cars", schema = S.obj(null, listOf("cars"), false, null,
                    "selectedId" to S.str("Id of the selected car; absent when there is none."),
                    "cars" to S.arr(S.ref("Car")))),
            ) {
                val space = backend.cars()
                ok(buildJsonObject {
                    space.garage.currentId?.let { put("selectedId", it) }
                    put("cars", JsonArray(space.cars.map { carJson(space, it) }))
                })
            },
            Route(
                "POST", "/api/v1/cars", RouteAccess.Needs(Scope.Layout), "Cars", "createCar",
                "Add a car by hand",
                "Adds a car without VIN, e.g. before its first connection: the VIN read at the first connection is attached to it if it is the selected car then. It is selected when `select` is true (or when it is the first car); selecting a car activates its last used profile (none for a new car).",
                body = Body(S.obj(null, listOf("label"), false, null, *carFields, "select" to S.bool("Make it the selected car (default false).")),
                    example = buildJsonObject { put("label", "My car"); put("series", "<series>"); put("model", "<model>"); put("select", true) }),
                reply = Reply(201, "The new car", S.ref("Car")),
                errors = mapOf(400 to "Malformed body.", 422 to "Invalid field (code invalid_car) or too many cars."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("label", "series", "model", "ecus", "select")
                val name = label(b) ?: throw invalidCar("'label' is required")
                val series = field(b, "series")
                val model = field(b, "model")
                val sgbds = ecus(b).orEmpty()
                val select = b.bool("select") ?: false
                val (space, car) = backend.editCars { space ->
                    if (space.cars.size >= Cars.MAX_CARS) throw invalidCar("At most ${Cars.MAX_CARS} cars")
                    val (withCar, car) = Cars.create(space, name, series, model, sgbds, clock())
                    val next = if (select) Cars.select(withCar, car.id, clock()) else withCar
                    next to (next to car)
                }
                ok(carJson(space, car), 201)
            },
            Route(
                "GET", "/api/v1/cars/{car}", RouteAccess.Needs(Scope.Read), "Cars", "getCar",
                "One car with its profiles",
                "The car, its ECU list and the id and name of each of its profiles.",
                params = listOf(carParam),
                reply = Reply(description = "Car", schema = S.ref("Car")),
                errors = mapOf(404 to "Unknown car."),
            ) { call ->
                val space = backend.cars()
                ok(carJson(space, requireCar(space, call.path("car")), withProfiles = true))
            },
            Route(
                "PUT", "/api/v1/cars/{car}", RouteAccess.Needs(Scope.Layout), "Cars", "updateCar",
                "Change a car",
                "Changes the fields given (others are kept). `ecus` sets the ECU list by hand (a car not detected yet, or ECUs a detection did not identify); a later detection replaces the ECUs it finds and keeps those added by hand. " +
                    "Taking out of the list an ECU that profiles of the car still use (main ECUs, cards, graphs) answers 409 `ecu_in_use` with those profiles and their card counts, unless `removeCards` is true: the ECU then also leaves these profiles, with its cards (phone and Android Auto) and graph values.",
                params = listOf(carParam),
                body = Body(S.obj(null, emptyList(), false, null, *carFields,
                    "removeCards" to S.bool("Take the ECUs removed from `ecus` out of the car's profiles too, deleting their cards and graph values (default false: 409 when they are used).")),
                    example = buildJsonObject { putJsonArray("ecus") { add("<engine-sgbd>"); add("<gearbox-sgbd>") } }),
                reply = Reply(description = "The car", schema = S.ref("Car")),
                errors = mapOf(400 to "Malformed body.", 404 to "Unknown car.", 409 to "An ECU taken out is used by profiles (code ecu_in_use; details: the profiles and cards).", 422 to "Invalid field (code invalid_car)."),
            ) { call ->
                val id = call.path("car")
                val b = call.bodyObject()
                b.allowOnly("label", "series", "model", "ecus", "removeCards")
                val name = label(b)
                val series = field(b, "series")
                val model = field(b, "model")
                val sgbds = ecus(b)
                val removeCards = b.bool("removeCards") ?: false
                val space = backend.editCars { space ->
                    val car = requireCar(space, id)
                    var next = space
                    if (sgbds != null) {
                        val scanned = car.ecus.mapNotNull { it.sgbd }.toSet()
                        val inUse = Cars.ecusInUse(space, id, car.addedSgbds.filter { it !in sgbds && it !in scanned })
                        if (inUse.isNotEmpty() && !removeCards) throw ecuInUse(inUse)
                        for (sgbd in inUse.keys) next = Cars.removeEcu(next, id, sgbd)
                    }
                    Cars.update(next, id, name, series, model, sgbds).let { it to it }
                }
                ok(carJson(space, space.car(id)!!, withProfiles = true))
            },
            Route(
                "DELETE", "/api/v1/cars/{car}", RouteAccess.Needs(Scope.Layout), "Cars", "deleteCar",
                "Delete a car, its profiles and its fault read",
                "Removes the car with **all its profiles** (pages and graphs) and its last fault read; nothing is sent to the car. When it was selected, the first car left is selected with its last used profile. Consider GET /api/v1/profiles/{profile}/export first to keep a copy of the profiles.",
                params = listOf(carParam),
                reply = Reply(description = "Deleted", schema = S.obj(null, listOf("deleted", "profilesDeleted"), false, null,
                    "deleted" to S.bool(), "profilesDeleted" to S.int("Number of profiles deleted with the car."),
                    "selectedId" to S.str("The selected car now; absent when there is none."),
                    "activeProfileId" to S.str("The active profile now; absent when there is none."))),
                errors = mapOf(404 to "Unknown car."),
            ) { call ->
                val id = call.path("car")
                val (after, count) = backend.editCars { space ->
                    requireCar(space, id)
                    val next = Cars.delete(space, id, clock())
                    next to (next to space.profilesOf(id).size)
                }
                ok(buildJsonObject {
                    put("deleted", true)
                    put("profilesDeleted", count)
                    after.garage.currentId?.let { put("selectedId", it) }
                    after.profiles.activeId?.let { put("activeProfileId", it) }
                })
            },
            Route(
                "POST", "/api/v1/cars/{car}/select", RouteAccess.Needs(Scope.Layout), "Cars", "selectCar",
                "Show a car",
                "Makes it the selected car and activates its last used profile (none when it has no profile): the phone and Android Auto switch at once, and the profile and dashboard routes then act on that car.",
                params = listOf(carParam),
                reply = Reply(description = "The selected car", schema = S.obj(null, listOf("car"), false, null,
                    "car" to S.ref("Car"), "activeProfileId" to S.str("Its active profile; absent when it has none."))),
                errors = mapOf(404 to "Unknown car."),
            ) { call ->
                val id = call.path("car")
                val space = backend.editCars { space ->
                    requireCar(space, id)
                    Cars.select(space, id, clock()).let { it to it }
                }
                ok(buildJsonObject {
                    put("car", carJson(space, space.car(id)!!))
                    space.profiles.activeId?.let { put("activeProfileId", it) }
                })
            },
            Route(
                "POST", "/api/v1/cars/{car}/detect", RouteAccess.Needs(Scope.Diag), "Cars", "detectCarEcus",
                "Detect the car's ECUs",
                "Selects the car and starts the detection of its ECUs (full scan: functional identification, VIN, variants, buses; read-only jobs, about 20–60 s), like My cars › Detect ECUs on the phone. Connects in the last mode used when nothing is connected. Asynchronous: follow it with GET /api/v1/cars/{car}/detect. " +
                    "The ECUs found replace the scanned part of the car's ECU list (those added by hand stay). An ECU no longer found while profiles use it stays in the list (reported in `keptInUse`); remove it with PUT /api/v1/cars/{car} and `removeCards`. A different VIN goes to its own car (or a new one). The fault memories are then read with GET /api/v1/diag/faults.",
                params = listOf(carParam),
                reply = Reply(202, "Detection started", S.ref("Detection")),
                errors = mapOf(404 to "Unknown car.", 409 to "Another operation on the car runs (code vehicle_busy)."),
            ) { call ->
                val id = call.path("car")
                requireCar(backend.cars(), id)
                if (!backend.startDetection(id)) throw ApiException(409, "vehicle_busy", "Another operation on the car runs (detection, fault read or clear); retry when GET /api/v1/cars/$id/detect says it is done")
                ok(ApiJson.encodeToJsonElement(DetectionView.serializer(), backend.detection()), 202)
            },
            Route(
                "GET", "/api/v1/cars/{car}/detect", RouteAccess.Needs(Scope.Diag), "Cars", "getCarDetection",
                "Progress of the ECU detection",
                "Whether a detection runs, its step and progress, and the outcome of the last one (`result`; `message` when it failed). `carId` is the car it went to: a different VIN goes to its own car.",
                params = listOf(carParam),
                reply = Reply(description = "Detection", schema = S.ref("Detection")),
                errors = mapOf(404 to "Unknown car."),
            ) { call ->
                requireCar(backend.cars(), call.path("car"))
                ok(ApiJson.encodeToJsonElement(DetectionView.serializer(), backend.detection()))
            },
            Route(
                "POST", "/api/v1/profiles/{profile}/copy", RouteAccess.Needs(Scope.Layout), "Profiles", "copyProfile",
                "Copy a profile to a car",
                "Duplicates the profile (pages, graphs, series and model, ECUs) onto car `car`, e.g. to start another car from a tuned profile; that car's ECU list gets the profile's ECUs it lacks (as added by hand). The copy is not activated; a name already used gets a \" (2)\" suffix.",
                params = listOf(profileParam),
                body = Body(S.obj(null, listOf("car"), false, null, "car" to S.str("Id of the car to copy it to (GET /api/v1/cars); may be the profile's own car.")),
                    example = buildJsonObject { put("car", "car-a1b2c3d4e5") }),
                reply = Reply(201, "The copy", S.ref("Profile")),
                errors = mapOf(400 to "Malformed body.", 404 to "Unknown profile or car.", 422 to "Too many profiles."),
            ) { call ->
                val id = call.path("profile")
                val b = call.bodyObject()
                b.allowOnly("car")
                val carId = b.string("car")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ApiException(422, "invalid_profile", "'car' is required")
                val copy: Profile = backend.editCars { space ->
                    space.profiles.profile(id) ?: throw notFound("profile_not_found", "No profile '$id'; see GET /api/v1/profiles")
                    requireCar(space, carId)
                    if (space.profiles.profiles.size >= Profiles.MAX_PROFILES) throw ApiException(422, "invalid_profile", "At most ${Profiles.MAX_PROFILES} profiles")
                    Cars.copyProfile(space, id, carId, clock())!!
                }
                ok(ApiJson.encodeToJsonElement(Profile.serializer(), copy), 201)
            },
        )
    }

    companion object {
        internal fun schemas(): Map<String, JsonObject> = mapOf(
            "Car" to S.obj(
                "One of the user's cars (the VIN is never returned).", listOf("id", "label", "vinKnown", "ecus", "profileCount", "selected"), false, null,
                "id" to S.str("Stable id: the short VIN when the car was first seen with it, else a generated one.", "car-a1b2c3d4e5"),
                "label" to S.str(example = "My car"),
                "vinKnown" to S.bool("Its VIN was read (scan or connection); false for a car added by hand not connected yet."),
                "series" to S.str(example = "<series>"), "model" to S.str(example = "<model>"),
                "ecus" to S.arr(S.str(example = "<sgbd>"), "The car's ECU list (last scan, then those added by hand): its profiles may only name these. Empty: not set yet, the ECUs of the first profile given an ECU list become it."),
                "ecusByHand" to S.arr(S.str(example = "<sgbd>"), "The part of `ecus` added by hand (kept by scans)."),
                "scannedAtMs" to S.int("Last full scan (Faults tab), epoch ms.", format = "int64"),
                "profileCount" to S.int(),
                "selected" to S.bool("The car the app shows."),
                "profiles" to S.arr(S.obj(null, listOf("id", "name", "active"), false, null, "id" to S.str(), "name" to S.str(), "active" to S.bool()),
                    "Its profiles (GET /api/v1/cars/{car} only)."),
            ),
            "Detection" to S.obj(
                "ECU detection of the selected car (My cars › Detect ECUs).", listOf("running"), false, null,
                "running" to S.bool(),
                "carId" to S.str("The selected car (where the detection goes)."),
                "step" to S.str("Running step.", enum = listOf("connecting", "identifying", "vin", "variants", "buses", "sequential")),
                "done" to S.int(), "total" to S.int(), "detail" to S.str("ECU or SGBD being identified."),
                "result" to S.obj("Last detection that found the car.", listOf("carId", "ecus", "keptInUse"), false, null,
                    "carId" to S.str("The car it went to."), "ecus" to S.int("ECUs found."),
                    "keptInUse" to S.arr(S.str(example = "<sgbd>"), "ECUs not found but kept in the list because profiles use them (the phone asks the user).")),
                "message" to S.str("Why the last operation failed or what it reported (e.g. noCable, scanFailed: …)."),
            ),
        )
    }
}
