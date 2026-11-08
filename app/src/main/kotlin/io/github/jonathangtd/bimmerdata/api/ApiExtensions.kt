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
