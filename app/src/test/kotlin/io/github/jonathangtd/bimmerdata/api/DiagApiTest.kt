/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpRequest
import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/** The diag scope: free reads, confirmed writes, refused while driving, through [ApiService.handle]. */
class DiagApiTest {
    private val token = ApiTokens.generate()

    private class FakeDiag : DiagBackend {
        var speed: Double? = 0.0
        val cleared = ArrayList<String?>()
        val jobs = ArrayList<String>()
        var faults = listOf(DiagFaultView("2F08", "Ansauglufttemperatursensor, Signal"))

        private fun vehicleView() = DiagVehicleView("Test car", "E89X", 1L, listOf(DiagEcuView("0x12", "DME", sgbd = "msv70", status = "faults", faults = faults)))

        override fun vehicle() = vehicleView()
        override fun ecuName(ecu: String) = if (ecu == "0x12" || ecu == "msv70") "DME (msv70)" else throw ApiException(404, "unknown_ecu", "no $ecu")
        override fun readFaults(ecu: String?) = vehicleView()
        override fun clearFaults(ecu: String?): DiagVehicleView {
            cleared += ecu
            faults = emptyList()
            return vehicleView()
        }
        override fun runJob(sgbd: String, job: String, args: String): DiagJobView {
            jobs += "$sgbd $job $args"
            return DiagJobView(sgbd, job, args, listOf(mapOf("JOB_STATUS" to JsonPrimitive("OKAY"))), null, 42)
        }
        override fun speedKmh() = speed
    }

    private class FakeGuard : CarWriteGuard {
        var answer = CarWriteDecision.Approved
        val asked = ArrayList<CarWrite>()
        override fun authorize(request: CarWrite, timeoutMs: Long): CarWriteDecision {
            asked += request
            return answer
        }
    }

    private val diag = FakeDiag()
    private val guard = FakeGuard()
    private var scopes = setOf(Scope.Read, Scope.Diag)
    private val service = ApiService(FakeApiBackend(), { ApiAccess(token, scopes) }, listOf(DiagApi(diag, guard)))

    private fun call(method: String, path: String, body: String = ""): HttpResponse.Bytes =
        service.handle(HttpRequest(method, path, emptyMap(), mapOf("authorization" to "Bearer $token"), body.toByteArray(), InetAddress.getLoopbackAddress())) as HttpResponse.Bytes

    private fun json(r: HttpResponse.Bytes) = ApiJson.parseToJsonElement(r.body.toString(Charsets.UTF_8)).jsonObject
    private fun code(r: HttpResponse.Bytes) = json(r)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `the diag scope is needed for every diag route`() {
        scopes = setOf(Scope.Read, Scope.Config)
        assertEquals(403, call("GET", "/api/v1/diag/faults").status)
        assertEquals(403, call("POST", "/api/v1/diag/faults/clear", "{}").status)
        assertEquals(403, call("POST", "/api/v1/diag/jobs", """{"sgbd":"msv70","job":"IDENT"}""").status)
        assertTrue(guard.asked.isEmpty())
    }

    @Test
    fun `reading the faults needs no confirmation`() {
        val r = call("GET", "/api/v1/diag/faults")
        assertEquals(200, r.status)
        val ecu = json(r)["ecus"]!!.jsonArray[0].jsonObject
        assertEquals("2F08", ecu["faults"]!!.jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(200, call("GET", "/api/v1/diag/vehicle").status)
        assertTrue(guard.asked.isEmpty())
    }

    @Test
    fun `clearing runs only once the user allowed it on the phone`() {
        val r = call("POST", "/api/v1/diag/faults/clear", """{"ecu":"0x12"}""")
        assertEquals(200, r.status, r.body.toString(Charsets.UTF_8))
        assertEquals(CarWrite.ClearFaults("DME (msv70)"), guard.asked.single())
        assertEquals(listOf<String?>("0x12"), diag.cleared)
        // every call asks again: nothing is remembered
        assertEquals(200, call("POST", "/api/v1/diag/faults/clear", "{}").status)
        assertEquals(CarWrite.ClearFaults(null), guard.asked.last())
        assertEquals(2, guard.asked.size)
    }

    @Test
    fun `refused or unanswered confirmations never touch the car`() {
        guard.answer = CarWriteDecision.Rejected
        val refused = call("POST", "/api/v1/diag/faults/clear", "{}")
        assertEquals(403, refused.status)
        assertEquals("refused_on_phone", code(refused))
        guard.answer = CarWriteDecision.Timeout
        val late = call("POST", "/api/v1/diag/jobs", """{"sgbd":"msv70","job":"STATUS_OEL"}""")
        assertEquals(408, late.status)
        assertEquals("confirmation_timeout", code(late))
        guard.answer = CarWriteDecision.Unavailable
        assertEquals(503, call("POST", "/api/v1/diag/faults/clear", "{}").status)
        assertTrue(diag.cleared.isEmpty())
        assertTrue(diag.jobs.isEmpty())
    }

    @Test
    fun `writes are refused without asking while the car moves`() {
        diag.speed = 42.0
        val r = call("POST", "/api/v1/diag/faults/clear", "{}")
        assertEquals(409, r.status)
        assertEquals("vehicle_moving", code(r))
        assertTrue(guard.asked.isEmpty())
        // unknown speed: allowed, but still confirmed
        diag.speed = null
        assertEquals(200, call("POST", "/api/v1/diag/faults/clear", "{}").status)
        assertEquals(1, guard.asked.size)
        // reading stays allowed while driving
        diag.speed = 90.0
        assertEquals(200, call("GET", "/api/v1/diag/faults").status)
    }

    @Test
    fun `jobs are validated, confirmed and run`() {
        val r = call("POST", "/api/v1/diag/jobs", """{"sgbd":"MSV70.prg","job":"status_oel","args":"1;2"}""")
        assertEquals(200, r.status, r.body.toString(Charsets.UTF_8))
        assertEquals(CarWrite.RunJob("msv70", "STATUS_OEL", "1;2"), guard.asked.single())
        assertEquals(listOf("msv70 STATUS_OEL 1;2"), diag.jobs)
        assertEquals("OKAY", json(r)["sets"]!!.jsonArray[0].jsonObject["JOB_STATUS"]!!.jsonPrimitive.content)
        // the fault clear has its own route (and its own confirmation text)
        assertEquals(400, call("POST", "/api/v1/diag/jobs", """{"sgbd":"msv70","job":"FS_LOESCHEN"}""").status)
        assertEquals(400, call("POST", "/api/v1/diag/jobs", """{"sgbd":"../etc/x","job":"IDENT"}""").status)
        assertEquals(400, call("POST", "/api/v1/diag/jobs", """{"sgbd":"msv70"}""").status)
        assertEquals(400, call("POST", "/api/v1/diag/jobs", """{"sgbd":"msv70","job":"IDENT","what":1}""").status)
        assertEquals(1, guard.asked.size)
    }

    @Test
    fun `unknown ECU answers 404 before asking`() {
        assertEquals(404, call("POST", "/api/v1/diag/faults/clear", """{"ecu":"0x99"}""").status)
        assertTrue(guard.asked.isEmpty())
    }

    @Test
    fun `openapi documents the diag routes and the scope`() {
        val doc = OpenApi.document(service.routes, null, "test")
        val paths = doc["paths"]!!.jsonObject
        for (p in listOf("/api/v1/diag/vehicle", "/api/v1/diag/faults", "/api/v1/diag/faults/clear", "/api/v1/diag/jobs")) assertTrue(p in paths, p)
        assertTrue("DiagVehicle" in doc["components"]!!.jsonObject["schemas"]!!.jsonObject)
        assertTrue(Scope.Diag.description.contains("confirmed"))
    }

    @Test
    fun `vehicle speed comes from the GPS or a speed measurement in km_h`() {
        val speed = Measurement("dme/0x580D", "dme", "Geschwindigkeit", "km/h", MeasurementSource.MeasurementTable(0x580D, "STAT_GESCHWINDIGKEIT_WERT"))
        val oil = Measurement("dme/0x4402", "dme", "Öltemperatur", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OELTEMPERATUR_WERT"))
        val meta = mapOf(speed.id to speed, oil.id to oil)::get
        assertEquals(12.0, VehicleSpeed.kmh(mapOf("gps/speed" to Sample(12.0, null, 1_000)), meta, 2_000))
        assertEquals(50.0, VehicleSpeed.kmh(mapOf(speed.id to Sample(50.0, null, 1_000), oil.id to Sample(90.0, null, 1_000)), meta, 2_000))
        assertNull(VehicleSpeed.kmh(mapOf(speed.id to Sample(50.0, null, 1_000)), meta, 60_000), "stale value")
        assertNull(VehicleSpeed.kmh(mapOf(oil.id to Sample(90.0, null, 1_000)), meta, 2_000))
    }
}
