/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

/*
 * Extension point for diagnostic operations (fault memory, ECU jobs), to be added by the
 * fault-memory feature. Nothing is registered today.
 *
 * Rules for every extension route (enforced by ApiService where possible):
 * - it needs the `diag` scope ([RouteAccess.Needs] with [Scope.Diag]) and lives under /api/v1/diag/;
 * - read-only diagnostics (e.g. reading the fault memory) may answer directly;
 * - anything that writes to the car (clearing faults, coding, actuator tests, adaptations) must call
 *   [CarWriteGuard.authorize] for every single call and refuse when it returns anything but
 *   [CarWriteDecision.Approved]: the guard asks the user on the phone (explicit confirmation,
 *   per call, never remembered) and refuses while the car is moving ([BackendStatus.driving]);
 * - document the operation in the route (it lands in openapi.json) and say clearly that it writes.
 *
 * To plug a feature in: implement [ApiExtension] and pass it to [ApiService] (see
 * io.github.jonathangtd.bimmerdata.api.android.ApiRuntime), with a [CarWriteGuard] that shows the
 * confirmation on the phone.
 */

/** A feature that adds routes to the API (only diagnostics for now). */
interface ApiExtension {
    /** Guard used for operations that write to the car; the default refuses everything. */
    val guard: CarWriteGuard get() = CarWriteGuard.DenyAll

    fun routes(context: ExtensionContext): List<Route>
}

class ExtensionContext(val backend: ApiBackend, val guard: CarWriteGuard)

enum class CarWriteDecision { Approved, Rejected, Driving, Unavailable }

/** Gatekeeper for API calls that would write to an ECU. */
fun interface CarWriteGuard {
    /**
     * Asks the user on the phone to allow [action] (a human-readable sentence such as "Clear the
     * fault memory of the DME (MSV70)"), blocking up to [timeoutMs]. Must return
     * [CarWriteDecision.Driving] without asking while the car moves.
     */
    fun authorize(action: String, timeoutMs: Long): CarWriteDecision

    companion object {
        val DenyAll = CarWriteGuard { _, _ -> CarWriteDecision.Unavailable }
    }
}
