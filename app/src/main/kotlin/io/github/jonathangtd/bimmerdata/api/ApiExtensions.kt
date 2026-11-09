/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

/*
 * Extension point for diagnostic operations (fault memory, ECU jobs): see [DiagApi].
 *
 * Rules for every extension route (enforced by ApiService where possible):
 * - it needs the `diag` scope ([RouteAccess.Needs] with [Scope.Diag]) and lives under /api/v1/diag/;
 * - read-only diagnostics (e.g. reading the fault memory) may answer directly;
 * - anything that writes to the car or runs an arbitrary job must call [CarWriteGuard.authorize]
 *   for every single call and refuse when it returns anything but [CarWriteDecision.Approved]:
 *   the guard asks the user on the phone (explicit confirmation, per call, never remembered);
 *   requests are refused without asking while the car is moving;
 * - document the operation in the route (it lands in openapi.json) and say clearly that it writes.
 */

/** A feature that adds routes to the API (only diagnostics for now). */
interface ApiExtension {
    /** Guard used for operations that write to the car; the default refuses everything. */
    val guard: CarWriteGuard get() = CarWriteGuard.DenyAll

    fun routes(context: ExtensionContext): List<Route>
}

class ExtensionContext(val backend: ApiBackend, val guard: CarWriteGuard)

/** What an API client asks to do in the car; the phone shows it to the user (localized). */
sealed interface CarWrite {
    /** Short English sentence, for logs and API errors. */
    val summary: String

    /** Clear the fault memory of [ecu] (display name, e.g. "DME (<sgbd>)"), or of every ECU. */
    data class ClearFaults(val ecu: String?) : CarWrite {
        override val summary: String get() = if (ecu == null) "Clear the fault memory of every ECU" else "Clear the fault memory of $ecu"
    }

    /** Run EDIABAS job [job] of [sgbd] with [args]. */
    data class RunJob(val sgbd: String, val job: String, val args: String) : CarWrite {
        override val summary: String get() = "Run the job $job of $sgbd" + if (args.isEmpty()) "" else " with \"$args\""
    }

    /** Store the CAN mode [code] ([name]: auto, kLineOnly, dCan500) in the Deep OBD Bluetooth adapter. */
    data class AdapterCanMode(val code: Int, val name: String) : CarWrite {
        override val summary: String get() = "Change the CAN mode of the Bluetooth adapter to $name"
    }
}

/**
 * Refuses while the car moves (speed above [maxSpeedKmh]), else asks the user through [guard];
 * returns only when the user approved, otherwise throws the matching [ApiException].
 */
internal fun confirmCarWrite(guard: CarWriteGuard, request: CarWrite, timeoutMs: Long, speedKmh: Double?, maxSpeedKmh: Double = DiagApi.MAX_SPEED_KMH) {
    speedKmh?.let { v ->
        if (v > maxSpeedKmh) throw ApiException(409, "vehicle_moving", "Refused: the car is moving (${"%.0f".format(java.util.Locale.ROOT, v)} km/h). ${request.summary} only when stopped.")
    }
    when (guard.authorize(request, timeoutMs)) {
        CarWriteDecision.Approved -> Unit
        CarWriteDecision.Rejected -> throw ApiException(403, "refused_on_phone", "The user refused on the phone: ${request.summary}")
        CarWriteDecision.Timeout -> throw ApiException(408, "confirmation_timeout", "Nobody confirmed on the phone within ${timeoutMs / 1000} s: ${request.summary}")
        CarWriteDecision.Unavailable -> throw ApiException(503, "confirmation_unavailable", "The phone cannot ask for a confirmation right now")
    }
}

enum class CarWriteDecision { Approved, Rejected, Timeout, Unavailable }

/** Gatekeeper for API calls that would write to an ECU or run a job. */
fun interface CarWriteGuard {
    /**
     * Asks the user on the phone to allow [request], blocking up to [timeoutMs]: [CarWriteDecision.Timeout]
     * when nobody answered (refused by default), [CarWriteDecision.Unavailable] when it cannot ask.
     */
    fun authorize(request: CarWrite, timeoutMs: Long): CarWriteDecision

    companion object {
        val DenyAll = CarWriteGuard { _, _ -> CarWriteDecision.Unavailable }
    }
}
