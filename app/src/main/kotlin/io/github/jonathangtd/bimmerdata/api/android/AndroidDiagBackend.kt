/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.android

import android.content.Context
import io.github.jonathangtd.bimmerdata.api.ApiException
import io.github.jonathangtd.bimmerdata.api.DiagBackend
import io.github.jonathangtd.bimmerdata.api.DiagEcuView
import io.github.jonathangtd.bimmerdata.api.DiagFaultView
import io.github.jonathangtd.bimmerdata.api.DiagJobView
import io.github.jonathangtd.bimmerdata.api.DiagVehicleView
import io.github.jonathangtd.bimmerdata.api.VehicleSpeed
import io.github.jonathangtd.bimmerdata.diag.DiagSession
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import io.github.jonathangtd.bimmerdata.files.EcuImporter
import io.github.jonathangtd.bimmerdata.live.CableUnavailableException
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.UserConfirmation
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import io.github.jonathangtd.bimmerdata.vehicle.VehicleRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * [DiagBackend] on the app: the vehicle and fault memory of [VehicleRepository] (same lock and
 * same storage as the Faults tab) and jobs through a [DiagSession] on the shared cable. Calls
 * block the API worker thread; the live reading goes on between the jobs.
 */
class AndroidDiagBackend(context: Context) : DiagBackend {
    private val app = context.applicationContext
    private val vehicles get() = VehicleRepository.get(app)
    private val live get() = LiveRepository.get(app)

    override fun vehicle(): DiagVehicleView? {
        val s = vehicles.state.value
        val v = s.current ?: return null
        return view(v, s.currentFaults.orEmpty())
    }

    override fun ecuName(ecu: String): String {
        val e = resolve(ecu)
        return if (e.sgbd != null) "${e.name} (${e.sgbd})" else e.name
    }

    override fun readFaults(ecu: String?): DiagVehicleView {
        val address = ecu?.let { resolve(it).address }
        guarded { runBlocking { vehicles.readFaultsForApi(address) } }
        return vehicle() ?: throw noVehicle()
    }

    override fun clearFaults(ecu: String?): DiagVehicleView {
        val address = ecu?.let { resolve(it).address }
        val what = if (ecu == null) "all ECUs" else ecuName(ecu)
        guarded { runBlocking { vehicles.clearForApi(address, UserConfirmation("REST API, confirmed on the phone: clear $what")) } }
        return vehicle() ?: throw noVehicle()
    }

    override fun runJob(sgbd: String, job: String, args: String): DiagJobView {
        val dir = EcuImporter(app).ecuDir
        val session = DiagSession(live, "API")
        val run = guarded {
            runBlocking {
                val file = when {
                    File(dir, "$sgbd.prg").isFile -> "$sgbd.prg"
                    File(dir, "$sgbd.grp").isFile -> session.resolveVariant("$sgbd.grp")
                    else -> throw ApiException(404, "unknown_sgbd", "No SGBD '$sgbd' in the imported ECU files")
                }
                session.runJob(file, job, args)
            }
        }
        return DiagJobView(
            sgbd = run.sgbd.substringBeforeLast('.').lowercase(Locale.ROOT), job = run.job, args = run.args,
            sets = run.sets.map { set -> set.mapValues { (_, r) -> json(r) } },
            error = run.error, elapsedMs = run.elapsedNanos / 1_000_000,
        )
    }

    override fun speedKmh(): Double? {
        val requests = live.requests.merged().associateBy { it.id }
        val metadata: (String) -> Measurement? = { requests[it] }
        return VehicleSpeed.kmh(live.liveDataSource.samples.value, metadata, System.currentTimeMillis())
    }

    // ---- helpers ----

    /** ECU of the current vehicle by address ("0x12" / "12"), SGBD ("msv70") or name ("DME"). */
    private fun resolve(ecu: String): VehicleEcu {
        val v = vehicles.state.value.current ?: throw noVehicle()
        val key = ecu.trim()
        val address = key.lowercase(Locale.ROOT).removePrefix("0x").takeIf { it.length in 1..2 }?.toIntOrNull(16)
        return v.ecus.firstOrNull { address != null && it.address == address }
            ?: v.ecus.firstOrNull { it.sgbd.equals(key.removeSuffix(".prg"), ignoreCase = true) }
            ?: v.ecus.firstOrNull { it.name.equals(key, ignoreCase = true) }
            ?: throw ApiException(404, "unknown_ecu", "No ECU '$ecu' in the vehicle; see GET /api/v1/diag/vehicle")
    }

    private fun view(v: Vehicle, faults: List<EcuFaults>): DiagVehicleView {
        val byAddress = faults.associateBy { it.address }
        return DiagVehicleView(
            label = v.label, series = v.series, scannedAtMs = v.scannedAtMs,
            ecus = v.ecus.map { e ->
                val f = byAddress[e.address]
                DiagEcuView(
                    address = "0x%02X".format(e.address), name = e.name, description = e.description, sgbd = e.sgbd,
                    status = f?.status?.name?.replaceFirstChar { it.lowercase(Locale.ROOT) },
                    faults = f?.faults.orEmpty().map { DiagFaultView(it.codeHex, it.text, it.symptom, it.presence, it.warning) },
                    message = f?.message, readAtMs = f?.readAtMs?.takeIf { it > 0 },
                )
            },
        )
    }

    private fun json(r: ResultData): JsonElement = when (val v = r.opData) {
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v.trimEnd())
        is ByteArray -> JsonPrimitive(v.joinToString(" ") { "%02X".format(it) })
        else -> JsonPrimitive(v.toString())
    }

    private fun noVehicle() = ApiException(404, "no_vehicle", "No vehicle detected yet: scan the car in the Faults tab first")

    /** Maps the app's errors to API errors. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: CancellationException) {
        throw ApiException(503, "cancelled", "The operation was cancelled")
    } catch (e: CableUnavailableException) {
        throw ApiException(503, "not_connected", e.message ?: "Not connected to the car")
    } catch (e: IllegalStateException) {
        throw ApiException(409, "not_ready", e.message ?: "Not ready")
    } catch (e: Exception) {
        throw ApiException(502, "diag_failed", e.message ?: e.javaClass.simpleName)
    }
}
