/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.graph.Graph
import io.github.jonathangtd.bimmerdata.graph.GraphSeries
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import io.github.jonathangtd.bimmerdata.profiles.ProfileJson
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** /api/v1/cars and the car side of /api/v1/profiles, through [ApiService.handle] (fake backend). */
class ApiCarsTest {
    private val token = ApiTokens.generate()
    private lateinit var backend: FakeApiBackend
    private lateinit var service: ApiService

    @BeforeEach
    fun start() {
        backend = FakeApiBackend()
        service = ApiService(backend, { ApiAccess(token, setOf(Scope.Read, Scope.Layout, Scope.Diag)) })
    }

    @AfterEach
    fun stop() = backend.close()

    private class Resp(val status: Int, val text: String) {
        fun json(): JsonObject = Json.parseToJsonElement(text).jsonObject
        val code get() = json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        fun str(key: String) = json()[key]!!.jsonPrimitive.content
        override fun toString() = "$status $text"
    }

    private fun call(method: String, path: String, body: String? = null, query: Map<String, String> = emptyMap()): Resp {
        val r = service.handle(io.github.jonathangtd.bimmerdata.api.http.HttpRequest(
            method, path, query.mapValues { listOf(it.value) }, mapOf("authorization" to "Bearer $token"), body?.toByteArray() ?: ByteArray(0), java.net.InetAddress.getLoopbackAddress(),
        ))
        val bytes = (r as io.github.jonathangtd.bimmerdata.api.http.HttpResponse.Bytes).body
        return Resp(r.status, String(bytes, Charsets.UTF_8))
    }

    private fun card(id: String) = Card(type = GaugeType.Dial, measurementIds = listOf(id))

    @Test
    fun `cars - add by hand, list, change, select, never the VIN`() {
        assertEquals(0, call("GET", "/api/v1/cars").json()["cars"]!!.jsonArray.size)
        assertEquals(422, call("POST", "/api/v1/cars", """{"series":"E46"}""").status, "a name is required")
        assertEquals("invalid_car", call("POST", "/api/v1/cars", """{"label":"   "}""").code)

        val daily = call("POST", "/api/v1/cars", """{"label":"Daily","series":"E90","model":"330i"}""")
        assertEquals(201, daily.status, daily.text)
        val dailyId = daily.str("id")
        assertFalse(daily.json()["vinKnown"]!!.jsonPrimitive.boolean)
        assertTrue(daily.json()["selected"]!!.jsonPrimitive.boolean, "the first car is selected")
        val track = call("POST", "/api/v1/cars", """{"label":"Track","ecus":["MS430.prg"]}""").json()
        val trackId = track["id"]!!.jsonPrimitive.content
        assertFalse(track["selected"]!!.jsonPrimitive.boolean)
        assertEquals("ms430", track["ecus"]!!.jsonArray.single().jsonPrimitive.content)

        // a scanned car: its VIN stays on the phone
        backend.garage = backend.garage.copy(vehicles = backend.garage.vehicles + Vehicle("AB12345", "Scanned", vin = "WBAPH11000AB12345", vinShort = "AB12345"))
        val list = call("GET", "/api/v1/cars")
        assertFalse(list.text.contains("WBAPH11000AB12345"), list.text)
        assertTrue(list.json()["cars"]!!.jsonArray.last().jsonObject["vinKnown"]!!.jsonPrimitive.boolean)
        assertEquals(dailyId, list.str("selectedId"))

        val changed = call("PUT", "/api/v1/cars/$dailyId", """{"label":"Daily driver","model":""}""").json()
        assertEquals("Daily driver", changed["label"]!!.jsonPrimitive.content)
        assertEquals(null, changed["model"])
        assertEquals(404, call("PUT", "/api/v1/cars/nope", """{"label":"x"}""").status)
        assertEquals("car_not_found", call("POST", "/api/v1/cars/nope/select").code)

        val sel = call("POST", "/api/v1/cars/$trackId/select")
        assertEquals(200, sel.status, sel.text)
        assertEquals(trackId, backend.garage.currentId)
    }

    @Test
    fun `profiles follow the selected car, copy goes to another car, deleting a car deletes its profiles`() {
        val a = call("POST", "/api/v1/cars", """{"label":"A","ecus":["msv70"]}""").str("id")
        val b = call("POST", "/api/v1/cars", """{"label":"B"}""").str("id")
        val pa = call("POST", "/api/v1/profiles", """{"name":"Lambda","ecus":["msv70"]}""")
        assertEquals(201, pa.status, pa.text)
        assertEquals(a, pa.str("carId"), "default: the selected car")
        val pb = call("POST", "/api/v1/profiles", """{"name":"Other","car":"$b","activate":false}""")
        assertEquals(b, pb.str("carId"))
        assertEquals(404, call("POST", "/api/v1/profiles", """{"name":"x","car":"nope"}""").status)

        assertEquals(listOf("Lambda"), call("GET", "/api/v1/profiles").json()["profiles"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("Other"), call("GET", "/api/v1/profiles", query = mapOf("car" to b)).json()["profiles"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(2, call("GET", "/api/v1/profiles", query = mapOf("car" to "all")).json()["profiles"]!!.jsonArray.size)
        assertEquals("car_not_found", call("GET", "/api/v1/profiles", query = mapOf("car" to "nope")).code)

        // activating a profile of another car selects that car
        call("POST", "/api/v1/profiles/${pb.str("id")}/activate").also { assertEquals(200, it.status, it.text) }
        assertEquals(b, backend.garage.currentId)
        // selecting a car shows its last used profile
        val sel = call("POST", "/api/v1/cars/$a/select").json()
        assertEquals(pa.str("id"), sel["activeProfileId"]!!.jsonPrimitive.content)

        // copy onto B: B (no ECU list) takes the profile's ECUs; the copy is not activated
        val copy = call("POST", "/api/v1/profiles/${pa.str("id")}/copy", """{"car":"$b"}""")
        assertEquals(201, copy.status, copy.text)
        assertEquals(b, copy.str("carId"))
        assertEquals("Lambda (2)", copy.str("name"))
        assertEquals(listOf("msv70"), backend.garage.vehicles.first { it.id == b }.sgbds())
        assertEquals(pa.str("id"), backend.profileSet.activeId)
        assertEquals("car_not_found", call("POST", "/api/v1/profiles/${pa.str("id")}/copy", """{"car":"nope"}""").code)
        assertEquals("profile_not_found", call("POST", "/api/v1/profiles/nope/copy", """{"car":"$b"}""").code)

        // import onto a car given in the query
        val file = ProfileJson.export(backend.profileSet.profile(pa.str("id"))!!)
        val imported = call("POST", "/api/v1/profiles/import", file, query = mapOf("car" to b, "activate" to "false"))
        assertEquals(201, imported.status, imported.text)
        assertEquals(b, imported.json()["profile"]!!.jsonObject["carId"]!!.jsonPrimitive.content)

        // deleting B deletes its three profiles, A stays selected with its profile
        val del = call("DELETE", "/api/v1/cars/$b").json()
        assertEquals(3, del["profilesDeleted"]!!.jsonPrimitive.int)
        assertEquals(listOf(pa.str("id")), backend.profileSet.profiles.map { it.id })
        assertEquals(a, backend.garage.currentId)
        assertEquals(pa.str("id"), del["activeProfileId"]!!.jsonPrimitive.content)
        // deleting the selected car selects the next one (none left: nothing active)
        call("DELETE", "/api/v1/cars/$a")
        assertTrue(backend.profileSet.profiles.isEmpty())
        assertEquals(null, backend.profileSet.activeId)
        assertEquals(null, backend.garage.currentId)
    }

    @Test
    fun `an ECU in use never leaves silently - 409 ecu_in_use, or removeCards`() {
        val carId = call("POST", "/api/v1/cars", """{"label":"A","ecus":["msv70","egs"]}""").str("id")
        val p = call("POST", "/api/v1/profiles", """{"name":"Gearbox","ecus":["msv70","egs"]}""").str("id")
        val layout = Dashboard(
            phonePages = listOf(Page(name = "P", columns = 2, rows = 2, cards = listOf(card("egs/0x01"), card("msv70/0x4402")))),
            carPages = listOf(Page(name = "C", columns = 4, rows = 2, cards = listOf(card("egs/0x01")))),
        )
        backend.editProfiles { s ->
            Profiles.updateGraphs(Profiles.writeDashboard(s, p, layout), p) { listOf(Graph(name = "G", series = listOf(GraphSeries("egs/0x01"), GraphSeries("msv70/0x4402")))) } to Unit
        }

        // the car: 409 with the profile and its cards
        val refused = call("PUT", "/api/v1/cars/$carId", """{"ecus":["msv70"]}""")
        assertEquals(409, refused.status, refused.text)
        assertEquals("ecu_in_use", refused.code)
        val use = refused.json()["error"]!!.jsonObject["details"]!!.jsonArray.single().jsonObject
        assertEquals("egs", use["ecu"]!!.jsonPrimitive.content)
        assertEquals(2, use["profiles"]!!.jsonArray.single().jsonObject["cards"]!!.jsonPrimitive.int, "phone and Android Auto cards")
        assertEquals(listOf("msv70", "egs"), backend.garage.current!!.sgbds(), "nothing changed")

        // the profile: unticking an ECU that still has cards
        val profileRefused = call("PUT", "/api/v1/profiles/$p", """{"ecus":["msv70"]}""")
        assertEquals(409, profileRefused.status, profileRefused.text)
        assertEquals(listOf("msv70", "egs"), backend.profileSet.profile(p)!!.ecus)

        // removeCards: out of the car and its profile, with its cards and graph values
        val removed = call("PUT", "/api/v1/cars/$carId", """{"ecus":["msv70"],"removeCards":true}""")
        assertEquals(200, removed.status, removed.text)
        assertEquals(listOf("msv70"), backend.garage.current!!.sgbds())
        val after = backend.profileSet.profile(p)!!
        assertEquals(listOf("msv70"), after.ecus)
        assertEquals(listOf("msv70/0x4402"), after.dashboard.measurementIds().toList())
        assertTrue(after.dashboard.carPages.single().cards.isEmpty(), "Android Auto keeps the same cards as the phone")
        assertEquals(listOf("msv70/0x4402"), after.graphs.single().measurementIds())

        // an ECU nobody uses just leaves
        call("PUT", "/api/v1/cars/$carId", """{"ecus":["msv70","kombi"]}""")
        assertEquals(200, call("PUT", "/api/v1/cars/$carId", """{"ecus":["msv70"]}""").status)
        // the profile's own removeCards
        call("PUT", "/api/v1/cars/$carId", """{"ecus":["msv70","egs"]}""")
        call("PUT", "/api/v1/profiles/$p", """{"ecus":["msv70","egs"]}""")
        assertEquals(200, call("PUT", "/api/v1/profiles/$p", """{"ecus":["egs"],"removeCards":true}""").status)
        assertTrue(backend.profileSet.profile(p)!!.dashboard.measurementIds().isEmpty())
    }

    @Test
    fun `detection is a car action - started for that car, busy refused`() {
        val carId = call("POST", "/api/v1/cars", """{"label":"A"}""").str("id")
        val other = call("POST", "/api/v1/cars", """{"label":"B"}""").str("id")
        val started = call("POST", "/api/v1/cars/$other/detect")
        assertEquals(202, started.status, started.text)
        assertEquals(listOf(other), backend.detections)
        assertEquals(other, started.str("carId"), "the car is selected first")
        assertTrue(call("GET", "/api/v1/cars/$other/detect").json()["running"]!!.jsonPrimitive.boolean)
        backend.detectionBusy = true
        assertEquals("vehicle_busy", call("POST", "/api/v1/cars/$carId/detect").code)
        assertEquals(404, call("POST", "/api/v1/cars/nope/detect").status)
        // needs the diag scope
        val readOnly = ApiService(backend, { ApiAccess(token, setOf(Scope.Read, Scope.Layout)) })
        val r = readOnly.handle(io.github.jonathangtd.bimmerdata.api.http.HttpRequest(
            "POST", "/api/v1/cars/$carId/detect", emptyMap(), mapOf("authorization" to "Bearer $token"), ByteArray(0), java.net.InetAddress.getLoopbackAddress(),
        ))
        assertEquals(403, r.status)
        // a scanned ECU found later is in the car's list for its profiles
        backend.garage = backend.garage.copy(vehicles = backend.garage.vehicles.map {
            if (it.id == carId) it.copy(ecus = listOf(VehicleEcu(0x12, "DME", sgbd = "msv70"))) else it
        })
        assertEquals("msv70", call("GET", "/api/v1/cars/$carId").json()["ecus"]!!.jsonArray.single().jsonPrimitive.content)
    }
}
