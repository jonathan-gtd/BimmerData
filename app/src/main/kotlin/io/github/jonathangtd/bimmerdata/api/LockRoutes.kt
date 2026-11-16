/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lock of the app's screens while an API client works ([EditLock]): read, take or renew, release.
 * Part of [ApiService]; pure JVM.
 */
internal class LockRoutes(private val lock: EditLock, private val clock: () -> Long) {
    private fun lockJson(s: EditLockState?): JsonElement = if (s == null) JsonNull else buildJsonObject {
        put("reason", s.reason)
        put("sinceMs", s.sinceMs)
        put("untilMs", s.untilMs)
        put("secondsLeft", ((s.untilMs - clock() + 999) / 1000).coerceAtLeast(0))
    }

    private fun reply(s: EditLockState?, extra: Pair<String, Boolean>? = null) = json(200, buildJsonObject {
        put("lock", lockJson(s))
        extra?.let { put(it.first, it.second) }
    })

    fun routes(): List<Route> {
        val one = S.obj(null, listOf("lock"), false, null, "lock" to S.refd("EditLock", "The lock, or null when the app is not locked."))
        val body = S.obj(null, listOf("reason"), false, null,
            "reason" to S.str("Shown on the phone: what is being changed, at most ${EditLock.MAX_REASON} characters.", "Adding the profile « Cold start »"),
            "seconds" to S.int("How long the lock lasts from now if it is not released or renewed before.", EditLock.MIN_SECONDS.toLong(), EditLock.MAX_SECONDS.toLong(), default = EditLock.DEFAULT_SECONDS.toLong()))
        return listOf(
            Route(
                "GET", "/api/v1/lock", RouteAccess.Needs(Scope.Read), TAG, "getLock",
                "Is the app locked?",
                "The lock taken by an API client (PUT /api/v1/lock), or `null`.",
                reply = Reply(description = "The lock", schema = one),
            ) { _ -> reply(lock.current()) },
            Route(
                "PUT", "/api/v1/lock", RouteAccess.Needs(Scope.Layout), TAG, "lockApp",
                "Lock the app while you work",
                "Locks the phone's screens so that the user does not change the same things at the same time: the app shows a lock screen with `reason` and the time left over every tab. Reading, recording, Android Auto and the API go on. Take it before a series of changes (profiles, pages, graphs, settings) and release it with DELETE /api/v1/lock when done. Calling it again while locked renews it (new `reason` and end, same `sinceMs`). It ends by itself after `seconds` (default ${EditLock.DEFAULT_SECONDS}), and the user can break it on the phone at any time: check it with GET /api/v1/lock during long work.",
                body = Body(body),
                reply = Reply(description = "The lock as taken", schema = one),
                errors = mapOf(400 to "Missing or too long `reason`, `seconds` out of range."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("reason", "seconds")
                val reason = b.string("reason")?.trim()?.takeIf { it.isNotEmpty() } ?: throw badRequest("'reason' is required: say on the phone what is being changed")
                if (reason.length > EditLock.MAX_REASON) throw badRequest("'reason' is limited to ${EditLock.MAX_REASON} characters")
                val seconds = b.int("seconds") ?: EditLock.DEFAULT_SECONDS
                if (seconds !in EditLock.MIN_SECONDS..EditLock.MAX_SECONDS) throw badRequest("'seconds' must be ${EditLock.MIN_SECONDS}..${EditLock.MAX_SECONDS} (got $seconds)")
                reply(lock.lock(reason, seconds))
            },
            Route(
                "DELETE", "/api/v1/lock", RouteAccess.Needs(Scope.Layout), TAG, "unlockApp",
                "Unlock the app",
                "Releases the lock: the phone's screens are usable again. `released` is false when the app was not locked (already released, expired or broken by the user).",
                reply = Reply(description = "No lock any more", schema = S.obj(null, listOf("lock", "released"), false, null, "lock" to S.refd("EditLock", "Always null."), "released" to S.bool("True if the lock was held."))),
            ) { _ -> reply(null, "released" to lock.unlock()) },
        )
    }

    companion object {
        const val TAG = "Lock"

        const val TAG_DESCRIPTION = "An agent that changes the configuration (profiles, pages, graphs, settings) locks the phone's screens meanwhile, so that the user does not edit the same things at the same time. The agent decides when; the lock ends by itself and the user can break it on the phone."

        internal fun schemas(): Map<String, JsonObject> = mapOf(
            "EditLock" to S.obj("The app locked by an API client.", listOf("reason", "sinceMs", "untilMs", "secondsLeft"), false, null,
                "reason" to S.str("Shown on the phone."),
                "sinceMs" to S.int("Locked since, epoch ms.", format = "int64"),
                "untilMs" to S.int("Ends by itself at, epoch ms.", format = "int64"),
                "secondsLeft" to S.int("Seconds until it ends.")),
        )
    }
}
