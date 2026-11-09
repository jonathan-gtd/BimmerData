/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.Locale

/*
 * Diagnostics over the REST API (scope `diag`), pure JVM:
 * - GET  /api/v1/diag/vehicle       last detected vehicle and its ECUs (no car traffic);
 * - GET  /api/v1/diag/faults        fault memory, read from the car (or ?cached=true);
 * - POST /api/v1/diag/faults/clear  clear one ECU or all, after a confirmation on the phone;
 * - POST /api/v1/diag/jobs          run one EDIABAS job, after a confirmation on the phone.
 * The two POST routes are blocking: the call waits (up to [DiagApi.confirmTimeoutMs]) for the
 * user's answer on the phone, then runs and answers 200, or 403 / 408 / 409 / 503.
 */

@Serializable
data class DiagFaultView(
    /** Fault code, hex ("2F08"). */
    val code: String,
    val text: String,
    val symptom: String? = null,
    val presence: String? = null,
    val warning: String? = null,
)

@Serializable
data class DiagEcuView(
    /** Diagnostic address, hex ("0x12"). */
    val address: String,
    val name: String,
    val description: String? = null,
    /** Variant SGBD (ECU description file), e.g. "<sgbd>"; null when unknown. */
    val sgbd: String? = null,
    /** Result of the last fault read (ok, faults, noAnswer, error…); null before any read. */
    val status: String? = null,
    val faults: List<DiagFaultView> = emptyList(),
    val message: String? = null,
    val readAtMs: Long? = null,
)

@Serializable
data class DiagVehicleView(
    val label: String,
    val series: String? = null,
    val scannedAtMs: Long? = null,
    val ecus: List<DiagEcuView>,
)

@Serializable
data class DiagJobView(
    val sgbd: String,
    val job: String,
    val args: String,
    /** Result sets (set 0 = system results); values are numbers, strings or hex strings for binary data. */
    val sets: List<Map<String, JsonElement>>,
    /** null when the job ran to the end. */
    val error: String? = null,
    val elapsedMs: Long,
)

/** What the diagnostic routes need from the app (implemented on Android by the vehicle and cable code). */
interface DiagBackend {
    /** Last detected vehicle with its last fault read, null when the car was never scanned. */
    fun vehicle(): DiagVehicleView?

    /**
     * Display name of the ECU [ecu] (address "0x12", SGBD "<sgbd>" or ECU name), for the
     * confirmation on the phone. Throws [ApiException] 404 when the vehicle has no such ECU.
     */
    fun ecuName(ecu: String): String

    /** Reads the fault memory of every ECU ([ecu] null) or of one, from the car. Blocking. */
    fun readFaults(ecu: String?): DiagVehicleView

    /** Clears the fault memory (already confirmed by the user), then reads it again. Blocking. */
    fun clearFaults(ecu: String?): DiagVehicleView

    /** Runs one EDIABAS job (already confirmed by the user). Blocking. */
    fun runJob(sgbd: String, job: String, args: String): DiagJobView

    /** Speed of the car in km/h (from the car or the phone GPS), null when unknown. */
    fun speedKmh(): Double?
}

/** The diagnostic routes; see the file header. */
class DiagApi(
    private val backend: DiagBackend,
    override val guard: CarWriteGuard,
    /** How long a POST waits for the user's answer on the phone (refused when nobody answers). */
    val confirmTimeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
) : ApiExtension {

    override fun routes(context: ExtensionContext): List<Route> = listOf(
        Route(
            "GET", "/api/v1/diag/vehicle", RouteAccess.Needs(Scope.Diag), TAG, "getDiagVehicle",
            "Vehicle and ECUs",
            "The last detected vehicle (Faults tab › scan) with its ECUs and the result of the last fault read. No traffic on the car. The VIN is never returned.",
            reply = Reply(description = "Vehicle", schema = S.ref("DiagVehicle")),
            errors = mapOf(404 to "No vehicle detected yet: scan the car in the Faults tab first."),
        ) { ok(backend.vehicle() ?: throw notFound("no_vehicle", "No vehicle detected yet: scan the car in the Faults tab first")) },
        Route(
            "GET", "/api/v1/diag/faults", RouteAccess.Needs(Scope.Diag), TAG, "readFaults",
            "Read the fault memory",
            "Reads the fault memory of every ECU of the vehicle, or of one (`ecu`), from the car: read-only, no confirmation needed. It may take a while (about 1 s per ECU); the live reading goes on between the jobs. With `cached=true`, answers the last read without talking to the car.",
            params = listOf(
                Param("ecu", "query", "One ECU: address (`0x12`), SGBD (`<sgbd>`) or name; absent = every ECU.", S.str(), required = false),
                Param("cached", "query", "Answer the last read without talking to the car.", S.bool(default = false), required = false),
            ),
            reply = Reply(description = "Vehicle with its faults", schema = S.ref("DiagVehicle")),
            errors = mapOf(404 to "No vehicle or no such ECU.", 503 to "Not connected to the car and no connection possible."),
        ) { call ->
            val ecu = call.query("ecu")
            if (call.queryBool("cached", false)) ok(backend.vehicle() ?: throw notFound("no_vehicle", "No vehicle detected yet"))
            else ok(backend.readFaults(ecu))
        },
        Route(
            "POST", "/api/v1/diag/faults/clear", RouteAccess.Needs(Scope.Diag), TAG, "clearFaults",
            "Clear the fault memory (writes to the car)",
            "**Writes to the car.** Clears the fault memory of one ECU (`ecu`) or of every ECU (body `{}`), then reads it again. " + CONFIRM_TEXT,
            body = Body(S.obj(null, emptyList(), false, null, "ecu" to S.str("Address (`0x12`), SGBD (`<sgbd>`) or ECU name; absent = every ECU.")),
                example = buildJsonObject { put("ecu", "0x12") }, required = false),
            reply = Reply(description = "Vehicle with its faults after the clear", schema = S.ref("DiagVehicle")),
            errors = CONFIRM_ERRORS + (404 to "No vehicle or no such ECU."),
        ) { call ->
            val b = call.bodyObject()
            b.allowOnly("ecu")
            val ecu = b.string("ecu")?.trim()?.takeIf { it.isNotEmpty() }
            val name = ecu?.let(backend::ecuName)
            authorize(CarWrite.ClearFaults(name))
            ok(backend.clearFaults(ecu))
        },
        Route(
            "POST", "/api/v1/diag/jobs", RouteAccess.Needs(Scope.Diag), TAG, "runJob",
            "Run an EDIABAS job (may write to the car)",
            "**May write to the car.** Runs one job of an SGBD, like the in-app Tool32: the same safety guard blocks flashing, coding and security access at the interface whatever the job. " +
                "Fault clearing has its own route (`/diag/faults/clear`). " + CONFIRM_TEXT,
            body = Body(S.obj(null, listOf("sgbd", "job"), false, null,
                "sgbd" to S.str("SGBD (ECU file) name, e.g. `<sgbd>`; a group file (`d_motor`) is resolved by talking to the ECU."),
                "job" to S.str("Job name, e.g. `STATUS_OEL`."),
                "args" to S.str("Job arguments, `;`-separated, as in Tool32.")),
                example = buildJsonObject { put("sgbd", "<sgbd>"); put("job", "STATUS_OEL") }),
            reply = Reply(description = "Job results", schema = S.ref("DiagJob")),
            errors = CONFIRM_ERRORS + (400 to "Missing or invalid sgbd / job / args."),
        ) { call ->
            val b = call.bodyObject()
            b.allowOnly("sgbd", "job", "args")
            val sgbd = b.string("sgbd")?.trim()?.lowercase(Locale.ROOT)?.removeSuffix(".prg")?.removeSuffix(".grp")
                ?.takeIf { NAME.matches(it) } ?: throw badRequest("'sgbd' must be an SGBD name such as <sgbd>")
            val job = b.string("job")?.trim()?.uppercase(Locale.ROOT)?.takeIf { NAME.matches(it) } ?: throw badRequest("'job' must be a job name such as STATUS_OEL")
            if (job in CLEAR_JOBS) throw badRequest("Use POST /api/v1/diag/faults/clear to clear the fault memory")
            val args = b.string("args").orEmpty()
            if (args.length > MAX_ARGS) throw badRequest("'args' is longer than $MAX_ARGS characters")
            authorize(CarWrite.RunJob(sgbd, job, args))
            ok(backend.runJob(sgbd, job, args))
        },
    )

    /** Refuses while the car moves, else asks on the phone; returns only when the user approved. */
    internal fun authorize(request: CarWrite) = confirmCarWrite(guard, request, confirmTimeoutMs, backend.speedKmh())

    private inline fun <reified T> ok(value: T): HttpResponse = json(200, ApiJson.encodeToJsonElement(value))

    companion object {
        const val TAG = "Diagnostics"
        const val DEFAULT_CONFIRM_TIMEOUT_MS = 60_000L

        /** Above this speed, writes are refused without asking. */
        const val MAX_SPEED_KMH = 3.0
        private const val MAX_ARGS = 1000
        private val NAME = Regex("[A-Za-z0-9_\\-]{1,64}")
        private val CLEAR_JOBS = setOf("FS_LOESCHEN", "FS_LOESCHEN_FUNKTIONAL")

        private const val CONFIRM_TEXT =
            "The call waits (up to 60 s) for the user to accept or refuse on the phone (dialog or notification), for every single call; " +
                "nobody answering means refused. Refused without asking while the car is moving (speed above 3 km/h from the car or the phone GPS)."

        private val CONFIRM_ERRORS = mapOf(
            403 to "The user refused on the phone (code refused_on_phone), or the diag scope is not enabled.",
            408 to "Nobody confirmed on the phone in time (code confirmation_timeout).",
            409 to "The car is moving (code vehicle_moving).",
            503 to "The phone cannot ask (confirmation_unavailable) or no connection to the car.",
        )

        /** OpenAPI schemas of the diagnostic routes. */
        internal fun schemas(): Map<String, kotlinx.serialization.json.JsonObject> {
            val fault = S.obj("One fault.", listOf("code", "text"), false, null,
                "code" to S.str("Fault code, hex.", "2F08"), "text" to S.str("Fault text from the ECU file (German, or translated)."),
                "symptom" to S.str(), "presence" to S.str(), "warning" to S.str())
            val ecu = S.obj("One ECU.", listOf("address", "name"), false, null,
                "address" to S.str("Diagnostic address, hex.", "0x12"), "name" to S.str(example = "DME"), "description" to S.str(),
                "sgbd" to S.str(example = "<sgbd>"), "status" to S.str("Result of the last fault read; null before any read."),
                "faults" to S.arr(fault), "message" to S.str(), "readAtMs" to S.int(format = "int64"))
            return mapOf(
                "DiagVehicle" to S.obj("Detected vehicle (the VIN is never returned).", listOf("label", "ecus"), false, null,
                    "label" to S.str(), "series" to S.str(example = "E89X"), "scannedAtMs" to S.int(format = "int64"), "ecus" to S.arr(ecu)),
                "DiagJob" to S.obj("Results of one job.", listOf("sgbd", "job", "args", "sets", "elapsedMs"), false, null,
                    "sgbd" to S.str(), "job" to S.str(), "args" to S.str(),
                    "sets" to S.arr(S.obj("One result set: result name → value.", emptyList(), true, null), "Set 0 holds the system results."),
                    "error" to S.str("null when the job ran to the end."), "elapsedMs" to S.int(format = "int64")),
            )
        }
    }
}

/**
 * Speed of the car for the "refused while driving" rule: the phone GPS (`gps/speed`) or an ECU
 * measurement in km/h that looks like the vehicle speed, read within the last [maxAgeMs].
 */
object VehicleSpeed {
    private val SPEED_WORDS = Regex("geschwindigkeit|fahrzeuggeschw|vehicle speed|^speed|_v_fzg|vfzg|v_fzg", RegexOption.IGNORE_CASE)

    fun kmh(samples: Map<String, Sample>, metadata: (String) -> Measurement?, nowMs: Long, maxAgeMs: Long = 5_000): Double? {
        fun fresh(s: Sample) = s.value != null && nowMs - s.timestampMs <= maxAgeMs
        samples["gps/speed"]?.takeIf(::fresh)?.let { return it.value }
        for ((id, s) in samples) {
            if (!fresh(s)) continue
            val m = metadata(id) ?: continue
            if (m.unit.replace(" ", "").lowercase(Locale.ROOT) != "km/h") continue
            if (SPEED_WORDS.containsMatchIn(m.label) || SPEED_WORDS.containsMatchIn(m.id)) return s.value
        }
        return null
    }
}
