/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.profiles

import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardJson
import io.github.jonathangtd.bimmerdata.graph.Graph
import io.github.jonathangtd.bimmerdata.graph.Graphs
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A profile as a file to share with other users: name, car (series and model only), ECUs,
 * pages and graphs. Never the VIN or the link to one of the user's cars ([ProfileVehicle.vehicleId]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProfileExport(
    @EncodeDefault val kind: String = KIND,
    @EncodeDefault val formatVersion: Int = ProfileSet.FORMAT_VERSION,
    val name: String,
    val series: String? = null,
    val model: String? = null,
    val ecus: List<String> = emptyList(),
    val dashboard: Dashboard = Dashboard(),
    val graphs: List<Graph> = emptyList(),
) {
    companion object {
        const val KIND = "bimmerdata-profile"
    }
}

/** JSON of the profile file (app storage) and of exported profiles. */
object ProfileJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(set: ProfileSet): String = json.encodeToString(ProfileSet.serializer(), set)

    /** The app's own file; each layout is repaired like an imported one ([DashboardJson.sanitize]). */
    fun decode(text: String): ProfileSet {
        val parsed = try {
            json.decodeFromString(ProfileSet.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Not a BimmerData profile file: ${e.message}", e)
        }
        require(parsed.formatVersion <= ProfileSet.FORMAT_VERSION) { "Profile format ${parsed.formatVersion} is newer than this app" }
        val seen = HashSet<String>()
        val profiles = parsed.profiles.filter { it.id.isNotBlank() && seen.add(it.id) }.map { p ->
            p.copy(
                name = Profiles.cleanName(p.name).ifEmpty { "?" },
                vehicle = Profiles.cleanVehicle(p.vehicle),
                ecus = Profiles.normalizeEcus(p.ecus),
                dashboard = DashboardJson.sanitize(p.dashboard).copy(name = null),
                graphs = Graphs.sanitize(p.graphs),
            )
        }
        val active = parsed.activeId?.takeIf { id -> profiles.any { it.id == id } }
        return ProfileSet(profiles = profiles, activeId = active)
    }

    /** File for sharing [p]: no VIN, no link to the user's car, no usage time. */
    fun export(p: Profile): String = json.encodeToString(
        ProfileExport.serializer(),
        ProfileExport(
            name = p.name, series = p.vehicle.series, model = p.vehicle.model, ecus = p.ecus,
            dashboard = p.dashboard.copy(name = null), graphs = p.graphs,
        ),
    )

    /**
     * Reads an exported profile; throws [IllegalArgumentException] if it is not one. The layout is
     * repaired like an imported dashboard; a plain dashboard file (older export) is accepted too.
     */
    fun parseExport(text: String): ProfileExport {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Not a BimmerData profile: ${e.message}", e)
        }
        val obj = element as? kotlinx.serialization.json.JsonObject ?: throw IllegalArgumentException("Not a BimmerData profile")
        val kind = (obj["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (kind == null && ("phonePages" in obj || "carPages" in obj)) {
            // a layout file shared before profiles existed
            val d = DashboardJson.decode(text)
            requireValueLimit(d)
            return ProfileExport(name = d.name ?: "", dashboard = d.copy(name = null))
        }
        require(kind == ProfileExport.KIND) { "Not a BimmerData profile (kind '$kind')" }
        val e = try {
            json.decodeFromJsonElement(ProfileExport.serializer(), obj)
        } catch (ex: SerializationException) {
            throw IllegalArgumentException("Not a BimmerData profile: ${ex.message}", ex)
        }
        require(e.formatVersion <= ProfileSet.FORMAT_VERSION) { "Profile format ${e.formatVersion} is newer than this app" }
        requireValueLimit(e.dashboard)
        return e.copy(
            name = Profiles.cleanName(e.name),
            ecus = Profiles.normalizeEcus(e.ecus),
            dashboard = DashboardJson.sanitize(e.dashboard).copy(name = null),
            graphs = Graphs.sanitize(e.graphs),
        )
    }

    /** A profile file with more than [Dashboard.MAX_VALUES] values is refused, with how to fix it. */
    private fun requireValueLimit(d: Dashboard) {
        val n = d.measurementIds().size
        require(n <= Dashboard.MAX_VALUES) {
            "This profile has $n values; a profile holds at most ${Dashboard.MAX_VALUES} (it is read whole at every cycle): split it into several profiles"
        }
    }

    /** Adds an exported profile as a new, active one (named [fallbackName] if it has no name). */
    fun importInto(set: ProfileSet, export: ProfileExport, nowMs: Long, fallbackName: String): Pair<ProfileSet, Profile> =
        Profiles.create(
            set, export.name.ifBlank { fallbackName }, ProfileVehicle(export.series, export.model), export.ecus,
            nowMs, export.dashboard, export.graphs,
        )
}
