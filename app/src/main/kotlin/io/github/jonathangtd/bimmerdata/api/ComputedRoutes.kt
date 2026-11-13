/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import io.github.jonathangtd.bimmerdata.live.ComputedKind
import io.github.jonathangtd.bimmerdata.live.ComputedValues
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.profiles.ComputedValue
import io.github.jonathangtd.bimmerdata.profiles.Computeds
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Computed values of a profile (ids `calc/<key>`, e.g. the power from a torque and an engine
 * speed): list, create, replace, delete. Cards and graphs then show them like any measurement.
 * Part of [ApiService]; pure JVM.
 */
internal class ComputedRoutes(
    private val backend: ApiBackend,
    private val meta: (String) -> Measurement?,
) {
    private fun ok(element: JsonElement, status: Int = 200) = json(status, element)

    private fun requireProfile(set: ProfileSet, id: String): Profile =
        set.profile(id) ?: throw notFound("profile_not_found", "No profile '$id'; see GET /api/v1/profiles")

    private fun find(p: Profile, key: String): ComputedValue =
        p.computed.firstOrNull { it.key == key } ?: throw notFound("computed_not_found", "No computed value '$key' in this profile; see GET …/computed")

    private fun invalid(message: String) = ApiException(422, "invalid_computed", message)

    private fun valueJson(c: ComputedValue) = buildJsonObject {
        put("id", c.id); put("key", c.key); put("kind", c.kind); put("label", c.label)
        put("unit", ComputedKind.of(c.kind)?.unit.orEmpty())
        put("inputs", JsonObject(c.inputs.mapValues { JsonPrimitive(it.value) }))
    }

    private fun warnings(c: ComputedValue): JsonArray = JsonArray(c.inputs.values.filter { meta(it) == null }.distinct().map {
        JsonPrimitive("'$it' is not in the imported ECU folder: '${c.id}' gives no value until its SGBD is imported")
    })

    /** A computed value from [b] (strict); [key] is kept when given. */
    private fun parse(b: JsonObject, existing: List<ComputedValue>, key: String?): ComputedValue {
        b.allowOnly("key", "kind", "inputs", "label")
        val kindName = b.string("kind") ?: throw invalid("'kind' is required (${ComputedKind.entries.joinToString { it.name }})")
        val kind = ComputedKind.of(kindName) ?: throw invalid("Unknown kind '$kindName' (known: ${ComputedKind.entries.joinToString { it.name }})")
        val inputsEl = b["inputs"] as? JsonObject ?: throw invalid("'inputs' is required: an object role -> measurement id, roles ${kind.roles.joinToString()}")
        val inputs = inputsEl.mapValues { (role, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: throw invalid("'inputs.$role' must be a measurement id") }
        val newKey = when {
            key != null -> {
                b.string("key")?.let { if (it != key) throw badRequest("A computed value key cannot be changed") }
                key
            }
            else -> b.string("key")?.trim()?.lowercase() ?: Computeds.newKey(existing, kind)
        }
        val c = ComputedValue(key = newKey, kind = kind.name, inputs = inputs, label = (b.string("label") ?: "").trim())
        Computeds.problem(c)?.let { throw invalid(it) }
        return c
    }

    fun routes(): List<Route> {
        val profileParam = Param("profile", "path", "Profile id (see GET /api/v1/profiles).", S.str(), example = JsonPrimitive("a1b2c3d4e5"))
        val keyParam = Param("key", "path", "Key of the computed value (its id is `calc/<key>`).", S.str(), example = JsonPrimitive("power"))
        val body = S.obj(null, listOf("kind", "inputs"), false, null,
            "key" to S.str("Only on POST: 1..24 of a-z, 0-9, _ (default: the kind, e.g. `power`, `power_2`…).", "power"),
            "kind" to S.ref("ComputedKind"),
            "inputs" to S.obj("Measurement id per role of the kind (Power: `torque` in N·m, `rpm`).", emptyList(), true, null),
            "label" to S.str("Shown on cards; at most ${ComputedValues.MAX_LABEL_LENGTH} characters (default: the kind).", "Power"))
        val reply = S.obj(null, listOf("computed", "warnings"), false, null, "computed" to S.ref("ComputedValue"), "warnings" to S.arr(S.str()))
        val errors = mapOf(400 to "Malformed body.", 404 to "Unknown profile or computed value.", 422 to "Invalid computed value (code invalid_computed) or too many (too_many_computed).")
        val example = buildJsonObject {
            put("kind", "Power"); put("label", "Power")
            put("inputs", buildJsonObject { put("torque", "<sgbd>/<id>"); put("rpm", "<sgbd>/<id2>") })
        }
        return listOf(
            Route(
                "GET", "/api/v1/profiles/{profile}/computed", RouteAccess.Needs(Scope.Read), "Profiles", "listComputed",
                "Computed values of a profile",
                "Values the app computes from other values of the profile (measurement ids `calc/<key>`), and the kinds it knows. " +
                    "Put one on a card or a graph like any measurement: its inputs are then read too, and count toward the profile's ${io.github.jonathangtd.bimmerdata.dashboard.Dashboard.MAX_VALUES} values.",
                params = listOf(profileParam),
                reply = Reply(description = "Computed values", schema = S.obj(null, listOf("computed", "kinds"), false, null,
                    "computed" to S.arr(S.ref("ComputedValue")),
                    "kinds" to S.arr(S.obj(null, listOf("name", "unit", "roles"), false, null,
                        "name" to S.str(), "unit" to S.str(), "roles" to S.arr(S.str()))),
                    "max" to S.int())),
                errors = mapOf(404 to "Unknown profile."),
            ) { call ->
                val p = requireProfile(backend.profiles(), call.path("profile"))
                ok(buildJsonObject {
                    put("computed", JsonArray(p.computed.map(::valueJson)))
                    put("kinds", JsonArray(ComputedKind.entries.map { k ->
                        buildJsonObject { put("name", k.name); put("unit", k.unit); put("roles", JsonArray(k.roles.map(::JsonPrimitive))) }
                    }))
                    put("max", Computeds.MAX)
                })
            },
            Route(
                "POST", "/api/v1/profiles/{profile}/computed", RouteAccess.Needs(Scope.Layout), "Profiles", "createComputed",
                "Add a computed value",
                "Adds a computed value to the profile, e.g. kind `Power` (ch) from a torque (N·m) and an engine speed (rpm): " +
                    "power = torque × rpm / 9549.3 × 1.35962, 0 when the torque is negative, no value when the two inputs are more than ${ComputedValues.MAX_SKEW_MS / 1000} s apart. " +
                    "Inputs missing from the ECU folder come back as `warnings`.",
                params = listOf(profileParam),
                body = Body(body, example = example),
                reply = Reply(201, "The new computed value", reply),
                errors = errors,
            ) { call ->
                val b = call.bodyObject()
                val id = call.path("profile")
                val created = backend.editProfiles { set ->
                    val p = requireProfile(set, id)
                    if (p.computed.size >= Computeds.MAX) throw ApiException(422, "too_many_computed", "At most ${Computeds.MAX} computed values per profile")
                    val c = parse(b, p.computed, null)
                    if (p.computed.any { it.key == c.key }) throw ApiException(409, "duplicate_key", "Key '${c.key}' is already used in this profile")
                    Profiles.updateComputed(set, id) { it + c } to c
                }
                ok(buildJsonObject { put("computed", valueJson(created)); put("warnings", warnings(created)) }, 201)
            },
            Route(
                "PUT", "/api/v1/profiles/{profile}/computed/{key}", RouteAccess.Needs(Scope.Layout), "Profiles", "replaceComputed",
                "Replace a computed value",
                "Replaces kind, inputs and label (same body as POST, key kept). Cards and graphs showing it keep it.",
                params = listOf(profileParam, keyParam),
                body = Body(body, example = example),
                reply = Reply(description = "The computed value", schema = reply),
                errors = errors,
            ) { call ->
                val b = call.bodyObject()
                val id = call.path("profile")
                val key = call.path("key")
                val replaced = backend.editProfiles { set ->
                    val p = requireProfile(set, id)
                    find(p, key)
                    val c = parse(b, p.computed, key)
                    Profiles.updateComputed(set, id) { list -> list.map { if (it.key == key) c else it } } to c
                }
                ok(buildJsonObject { put("computed", valueJson(replaced)); put("warnings", warnings(replaced)) })
            },
            Route(
                "DELETE", "/api/v1/profiles/{profile}/computed/{key}", RouteAccess.Needs(Scope.Layout), "Profiles", "deleteComputed",
                "Delete a computed value",
                "Removes the computed value. One that cards or graphs show is only removed with `removeCards=true`, which also deletes those cards (phone and Android Auto) and takes it out of the graphs; otherwise 409 `computed_in_use` with the counts.",
                params = listOf(profileParam, keyParam,
                    Param("removeCards", "query", "Also delete the cards and graph values showing it (default false).", S.bool(default = false), required = false)),
                reply = Reply(204, "Deleted"),
                errors = mapOf(404 to "Unknown profile or computed value.", 409 to "Shown by cards or graphs (computed_in_use); repeat with removeCards=true."),
            ) { call ->
                val id = call.path("profile")
                val key = call.path("key")
                val removeCards = call.queryBool("removeCards", false)
                backend.editProfiles { set ->
                    val p = requireProfile(set, id)
                    val c = find(p, key)
                    val (cards, series) = Computeds.uses(p, c.id)
                    if ((cards > 0 || series > 0) && !removeCards) {
                        throw ApiException(409, "computed_in_use", "'${c.id}' is shown by $cards card(s) and $series graph value(s); repeat with removeCards=true to delete them too",
                            buildJsonObject { put("cards", cards); put("graphSeries", series) })
                    }
                    Profiles.updateProfile(set, id) { Computeds.remove(it, key) } to Unit
                }
                HttpResponse.Bytes(204, HttpServer.JSON, ByteArray(0))
            },
        )
    }

    companion object {
        internal fun schemas(): Map<String, JsonObject> = mapOf(
            "ComputedKind" to S.str("Power: engine power in ch from inputs `torque` (N·m) and `rpm`.", enum = ComputedKind.entries.map { it.name }),
            "ComputedValue" to S.obj("A value computed by the app from other values of the profile; measurement id `calc/<key>`.", listOf("id", "key", "kind", "inputs"), false, null,
                "id" to S.str(example = "calc/power"), "key" to S.str(example = "power"),
                "kind" to S.ref("ComputedKind"), "label" to S.str(example = "Power"), "unit" to S.str(example = "ch"),
                "inputs" to S.obj("Measurement id per role.", emptyList(), true, null)),
        )
    }
}
