/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import io.github.jonathangtd.bimmerdata.cars.Cars
import io.github.jonathangtd.bimmerdata.dashboard.newId
import io.github.jonathangtd.bimmerdata.gauges.TrendWindow
import io.github.jonathangtd.bimmerdata.graph.Graph
import io.github.jonathangtd.bimmerdata.graph.GraphAxes
import io.github.jonathangtd.bimmerdata.graph.GraphPalette
import io.github.jonathangtd.bimmerdata.graph.GraphSeries
import io.github.jonathangtd.bimmerdata.graph.Graphs
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileJson
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Profile file import (onto a car) and the graphs of a profile (Graph tab). Graph changes go through
 * [Profiles.updateGraphs]: the pages of the profile are never touched, so the dashboard editor
 * and these routes never overwrite each other. Part of [ApiService]; pure JVM.
 */
internal class ProfileGraphRoutes(
    private val backend: ApiBackend,
    private val clock: () -> Long,
    private val meta: (String) -> Measurement?,
) {
    private fun ok(element: JsonElement, status: Int = 200) = json(status, element)

    private fun requireProfile(set: ProfileSet, id: String): Profile =
        set.profile(id) ?: throw notFound("profile_not_found", "No profile '$id'; see GET /api/v1/profiles")

    private fun graphJson(g: Graph) = ApiJson.encodeToJsonElement(Graph.serializer(), g)

    private fun invalidGraph(message: String) = ApiException(422, "invalid_graph", message)

    private fun warnings(ids: Collection<String>): JsonArray = JsonArray(ids.filter { it.contains('/') && !io.github.jonathangtd.bimmerdata.live.ComputedValues.isComputedId(it) && meta(it) == null }.distinct().map {
        JsonPrimitive("'$it' is not in the imported ECU folder: it will show no data until its SGBD is imported")
    })

    /** [position] of [graph] in [list], or 404. */
    private fun findGraph(list: List<Graph>, id: String): Graph =
        list.firstOrNull { it.id == id } ?: throw notFound("graph_not_found", "No graph '$id' in this profile; see GET …/graphs")

    /**
     * A whole graph from [b] (strict: every problem is an error, nothing is silently fixed);
     * [id] is kept. `series` gives colors and visibility, `measurementIds` takes free colors.
     */
    private fun parseGraph(b: JsonObject, id: String): Graph {
        b["id"]?.let { if (it !is JsonPrimitive || it.content != id) throw badRequest("A graph id cannot be changed") }
        val rawName = b.string("name") ?: throw invalidGraph("'name' is required")
        val name = Graphs.cleanName(rawName)
        if (name.isEmpty()) throw invalidGraph("'name' may not be empty")
        if (rawName.trim().length > Graphs.MAX_NAME_LENGTH) throw invalidGraph("'name' has at most ${Graphs.MAX_NAME_LENGTH} characters")
        if ("series" in b && "measurementIds" in b) throw badRequest("Send either 'series' or 'measurementIds', not both")
        val series: List<GraphSeries> = when {
            b["series"] != null && b["series"] !is JsonNull -> {
                val arr = b["series"] as? JsonArray ?: throw badRequest("'series' must be an array")
                val parsed = arr.mapIndexed { i, el ->
                    val o = el as? JsonObject ?: throw badRequest("'series[$i]' must be an object")
                    o.allowOnly("measurementId", "color", "hidden")
                    val mid = o.string("measurementId")?.trim()?.takeIf { it.isNotEmpty() } ?: throw invalidGraph("'series[$i].measurementId' is required")
                    val color = o.int("color")?.also { if (it !in 0 until GraphPalette.size) throw invalidGraph("'series[$i].color' must be 0..${GraphPalette.size - 1} (index in the palette)") }
                    Triple(mid, color, o.bool("hidden") ?: false)
                }
                val used = parsed.mapNotNull { it.second }.toMutableList()
                parsed.map { (mid, color, hidden) -> GraphSeries(mid, color ?: Graphs.freeColor(used).also { used += it }, hidden) }
            }
            else -> {
                val ids = b.stringList("measurementIds").orEmpty().map { it.trim() }
                if (ids.any { it.isEmpty() }) throw invalidGraph("'measurementIds' may not contain empty ids")
                val used = ArrayList<Int>()
                ids.map { GraphSeries(it, Graphs.freeColor(used).also { c -> used += c }) }
            }
        }
        if (series.size > Graphs.MAX_SERIES) throw invalidGraph("At most ${Graphs.MAX_SERIES} values per graph (one color each)")
        val dup = series.groupBy { it.measurementId }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) throw invalidGraph("Values listed twice: ${dup.joinToString()}")
        val window = b.int("windowSeconds")?.also {
            if (!TrendWindow.isValid(it)) throw invalidGraph("'windowSeconds' must be ${TrendWindow.MIN_SECONDS}..${TrendWindow.MAX_SECONDS} (or null for the default ${TrendWindow.DEFAULT_SECONDS})")
        }
        val axes = b.string("axes")?.let { a ->
            GraphAxes.entries.firstOrNull { it.name.equals(a, ignoreCase = true) } ?: throw badRequest("'axes' must be Overlay or Bands")
        } ?: GraphAxes.Overlay
        return Graph(id = id, name = name, series = series, windowSeconds = window, axes = axes)
    }

    fun routes(): List<Route> {
        val profileParam = Param("profile", "path", "Profile id (see GET /api/v1/profiles).", S.str(), example = JsonPrimitive("a1b2c3d4e5"))
        val graphParam = Param("graph", "path", "Graph id (see GET …/graphs).", S.str(), example = JsonPrimitive("g1h2i3j4k5"))
        val graphBody = S.obj(null, listOf("name"), false, null,
            "name" to S.str("At most ${Graphs.MAX_NAME_LENGTH} characters.", "Temperatures"),
            "measurementIds" to S.arr(S.str(example = "<sgbd>/<id>"), "Values (colors picked automatically). Or give `series`.", 0, Graphs.MAX_SERIES),
            "series" to S.arr(S.ref("GraphSeries"), "Values with their color / visibility. Or give `measurementIds`.", 0, Graphs.MAX_SERIES),
            "windowSeconds" to S.int("Visible time span, ${TrendWindow.MIN_SECONDS}..${TrendWindow.MAX_SECONDS}; absent / null = default ${TrendWindow.DEFAULT_SECONDS}.", TrendWindow.MIN_SECONDS.toLong(), TrendWindow.MAX_SECONDS.toLong()),
            "axes" to S.ref("GraphAxes"))
        val graphReply = S.obj(null, listOf("graph", "warnings"), false, null, "graph" to S.ref("Graph"), "warnings" to S.arr(S.str()))
        val graphErrors = mapOf(400 to "Malformed body.", 404 to "Unknown profile or graph.", 422 to "Invalid graph (code invalid_graph) or too many graphs (too_many_graphs).")
        return listOf(
            Route(
                "POST", "/api/v1/profiles/import", RouteAccess.Needs(Scope.Layout), "Profiles", "importProfile",
                "Import a profile file",
                "Adds the profile of a profile file (the JSON of GET /api/v1/profiles/{profile}/export or of the phone's Share, with its pages and `graphs`) as a new profile of car `car` (default: the selected car), like Profiles › Import on the phone: " +
                    "the layout is repaired the same way, a name already used gets a \" (2)\" suffix, the car's ECU list gets the file's ECUs it lacks (as added by hand). It becomes the active profile unless `activate=false`. A plain dashboard file (older exports) is accepted too.",
                params = listOf(
                    Param("activate", "query", "Make it the active profile (default true). With false the active profile stays, except on a car that had no profile: a car with profiles always shows one.", S.bool(default = true), required = false),
                    Param("car", "query", "Id of the car it goes to (GET /api/v1/cars); default: the selected car (with no car at all: 409 `no_car`, add one first with POST /api/v1/cars).", S.str(), required = false),
                ),
                body = Body(S.obj("BimmerData profile file", listOf("kind", "name"), true, null,
                    "kind" to S.str(enum = listOf("bimmerdata-profile")), "formatVersion" to S.int(), "name" to S.str(), "series" to S.str(), "model" to S.str(),
                    "ecus" to S.arr(S.str()), "dashboard" to S.ref("Dashboard"), "graphs" to S.arr(S.ref("Graph")), "computed" to S.arr(S.ref("ComputedValue")))),
                reply = Reply(201, "The new profile", S.obj(null, listOf("profile", "warnings"), false, null, "profile" to S.ref("Profile"), "warnings" to S.arr(S.str()))),
                errors = mapOf(404 to "Unknown car.", 409 to "No car yet (no_car): POST /api/v1/cars first.", 422 to "Not a profile file (code invalid_profile_file) or too many profiles."),
            ) { call ->
                val export = try {
                    ProfileJson.parseExport(call.request.bodyText())
                } catch (e: IllegalArgumentException) {
                    throw ApiException(422, "invalid_profile_file", e.message?.lineSequence()?.firstOrNull()?.take(300) ?: "Not a BimmerData profile file")
                }
                val activate = call.queryBool("activate", true)
                val carId = call.query("car")
                val created = backend.editCars { start ->
                    if (start.profiles.profiles.size >= Profiles.MAX_PROFILES) throw ApiException(422, "invalid_profile", "At most ${Profiles.MAX_PROFILES} profiles")
                    val (space, car) = if (carId != null) start to requireCar(start, carId) else (Cars.selectedOrFirst(start, clock()) ?: throw noCar())
                    Cars.importProfile(space, export, car.id, clock(), "Imported", activate)
                }
                // as stored: Android Auto brought to the phone's cards (DashboardRules)
                val stored = backend.profiles().profile(created.id) ?: created
                val carAdjusted = !io.github.jonathangtd.bimmerdata.dashboard.DashboardRules.follows(created.dashboard)
                ok(buildJsonObject {
                    put("profile", ApiJson.encodeToJsonElement(Profile.serializer(), stored))
                    val list = warnings(stored.dashboard.measurementIds() + stored.graphs.flatMap { it.measurementIds() })
                    put("warnings", if (!carAdjusted) list else JsonArray(list + JsonPrimitive("The Android Auto pages of the file did not show exactly the phone cards: they were brought to them (Android Auto always shows the phone's cards)")))
                }, 201)
            },
            Route(
                "GET", "/api/v1/profiles/{profile}/graphs", RouteAccess.Needs(Scope.Read), "Profiles", "listGraphs",
                "Graphs of a profile",
                "The graphs of the Graph tab (several values on one time axis), in order, and the limits.",
                params = listOf(profileParam),
                reply = Reply(description = "Graphs", schema = S.obj(null, listOf("graphs", "limits"), false, null,
                    "graphs" to S.arr(S.ref("Graph")),
                    "limits" to S.obj(null, emptyList(), false, null,
                        "maxGraphs" to S.int(), "maxSeries" to S.int(), "maxNameLength" to S.int(),
                        "palette" to S.arr(S.str(example = "#3987E5"), "Colors, by index (`color`)."),
                        "minWindowSeconds" to S.int(), "maxWindowSeconds" to S.int(), "defaultWindowSeconds" to S.int()))),
                errors = mapOf(404 to "Unknown profile."),
            ) { call ->
                val p = requireProfile(backend.profiles(), call.path("profile"))
                ok(buildJsonObject {
                    put("graphs", JsonArray(p.graphs.map(::graphJson)))
                    put("limits", buildJsonObject {
                        put("maxGraphs", Graphs.MAX_GRAPHS); put("maxSeries", Graphs.MAX_SERIES); put("maxNameLength", Graphs.MAX_NAME_LENGTH)
                        put("palette", JsonArray(GraphPalette.colors.map { JsonPrimitive("#%06X".format(Locale.ROOT, it and 0xFFFFFF)) }))
                        put("minWindowSeconds", TrendWindow.MIN_SECONDS); put("maxWindowSeconds", TrendWindow.MAX_SECONDS); put("defaultWindowSeconds", TrendWindow.DEFAULT_SECONDS)
                    })
                })
            },
            Route(
                "POST", "/api/v1/profiles/{profile}/graphs", RouteAccess.Needs(Scope.Layout), "Profiles", "createGraph",
                "Add a graph",
                "Adds a graph (at the end, or at `position`). Values are measurement ids, usually those shown on the profile's cards; ids missing from the ECU folder come back as `warnings`.",
                params = listOf(profileParam),
                body = Body(S.obj(null, listOf("name"), false, null, *(graphBody["properties"] as JsonObject).entries.map { it.key to it.value as JsonObject }.toTypedArray(),
                    "position" to S.int("Insert at this index (default: last).", min = 0)),
                    example = buildJsonObject { put("name", "Temperatures"); put("measurementIds", JsonArray(listOf(JsonPrimitive("<sgbd>/<id>"), JsonPrimitive("<sgbd>/<id2>")))); put("windowSeconds", 300) }),
                reply = Reply(201, "The new graph", graphReply),
                errors = graphErrors,
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("name", "measurementIds", "series", "windowSeconds", "axes", "position")
                val position = b.int("position")?.also { if (it < 0) throw badRequest("'position' must be ≥ 0") }
                val graph = parseGraph(JsonObject(b - "position"), newId())
                val id = call.path("profile")
                backend.editProfiles { set ->
                    val p = requireProfile(set, id)
                    if (p.graphs.size >= Graphs.MAX_GRAPHS) throw ApiException(422, "too_many_graphs", "At most ${Graphs.MAX_GRAPHS} graphs per profile")
                    Profiles.updateGraphs(set, id) { list ->
                        list.toMutableList().apply { add((position ?: size).coerceAtMost(size), graph) }
                    } to Unit
                }
                ok(buildJsonObject { put("graph", graphJson(graph)); put("warnings", warnings(graph.measurementIds())) }, 201)
            },
            Route(
                "GET", "/api/v1/profiles/{profile}/graphs/{graph}", RouteAccess.Needs(Scope.Read), "Profiles", "getGraph",
                "One graph",
                "The graph with its values, colors, window and axes.",
                params = listOf(profileParam, graphParam),
                reply = Reply(description = "Graph", schema = S.ref("Graph")),
                errors = mapOf(404 to "Unknown profile or graph."),
            ) { call ->
                ok(graphJson(findGraph(requireProfile(backend.profiles(), call.path("profile")).graphs, call.path("graph"))))
            },
            Route(
                "PUT", "/api/v1/profiles/{profile}/graphs/{graph}", RouteAccess.Needs(Scope.Layout), "Profiles", "replaceGraph",
                "Replace a graph",
                "Replaces the whole graph (same body as POST, id kept, place in the list kept unless `position` is given). Fields left out take their defaults.",
                params = listOf(profileParam, graphParam),
                body = Body(S.obj(null, listOf("name"), false, null, *(graphBody["properties"] as JsonObject).entries.map { it.key to it.value as JsonObject }.toTypedArray(),
                    "position" to S.int("Move to this index.", min = 0)),
                    example = buildJsonObject { put("name", "Temperatures"); put("axes", "Bands"); put("windowSeconds", 600) }),
                reply = Reply(description = "The graph", schema = graphReply),
                errors = graphErrors,
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("id", "name", "measurementIds", "series", "windowSeconds", "axes", "position")
                val position = b.int("position")?.also { if (it < 0) throw badRequest("'position' must be ≥ 0") }
                val gid = call.path("graph")
                val graph = parseGraph(JsonObject(b - "position"), gid)
                val id = call.path("profile")
                backend.editProfiles { set ->
                    findGraph(requireProfile(set, id).graphs, gid)
                    Profiles.updateGraphs(set, id) { list ->
                        val replaced = list.map { if (it.id == gid) graph else it }
                        if (position == null) replaced
                        else Graphs.move(replaced, replaced.indexOfFirst { it.id == gid }, position.coerceAtMost(replaced.size - 1))
                    } to Unit
                }
                ok(buildJsonObject { put("graph", graphJson(graph)); put("warnings", warnings(graph.measurementIds())) })
            },
            Route(
                "DELETE", "/api/v1/profiles/{profile}/graphs/{graph}", RouteAccess.Needs(Scope.Layout), "Profiles", "deleteGraph",
                "Delete a graph",
                "Removes the graph (the cards and the values are not affected).",
                params = listOf(profileParam, graphParam),
                reply = Reply(204, "Deleted"),
                errors = mapOf(404 to "Unknown profile or graph."),
            ) { call ->
                val id = call.path("profile")
                val gid = call.path("graph")
                backend.editProfiles { set ->
                    findGraph(requireProfile(set, id).graphs, gid)
                    Profiles.updateGraphs(set, id) { Graphs.delete(it, gid) } to Unit
                }
                HttpResponse.Bytes(204, HttpServer.JSON, ByteArray(0))
            },
        )
    }

    companion object {
        internal fun schemas(): Map<String, JsonObject> = mapOf(
            "GraphAxes" to S.str("Overlay: every curve on one plot, one axis per unit; Bands: one band per value with its own scale.", enum = GraphAxes.entries.map { it.name }),
            "GraphSeries" to S.obj("One value of a graph.", listOf("measurementId"), false, null,
                "measurementId" to S.str(example = "<sgbd>/<id>"),
                "color" to S.int("Index in the palette (GET …/graphs › limits.palette); default: the first free one.", 0, (GraphPalette.size - 1).toLong()),
                "hidden" to S.bool("Hidden from the plot, still in the legend.")),
            "Graph" to S.obj("A graph of the Graph tab: values of the profile on one time axis.", listOf("id", "name", "series", "axes"), false, null,
                "id" to S.str(example = "g1h2i3j4k5"), "name" to S.str(example = "Temperatures"),
                "series" to S.arr(S.ref("GraphSeries")),
                "windowSeconds" to S.int("Visible time span; absent = default ${TrendWindow.DEFAULT_SECONDS} s.", TrendWindow.MIN_SECONDS.toLong(), TrendWindow.MAX_SECONDS.toLong()),
                "axes" to S.ref("GraphAxes")),
        )
    }
}
