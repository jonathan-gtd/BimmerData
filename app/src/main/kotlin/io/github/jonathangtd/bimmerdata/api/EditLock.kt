/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app is locked by an API client since [sinceMs] until [untilMs] (epoch ms), for [reason]. */
data class EditLockState(val reason: String, val sinceMs: Long, val untilMs: Long)

/**
 * Lock of the app's screens taken by an API client (a script or an AI agent) while it changes
 * the configuration, so that the user does not edit the same things at the same time
 * (PUT / DELETE /api/v1/lock). While it is held the phone shows a lock screen over the app
 * (ui/EditLockHost): reading and recording go on, the API keeps working. The client decides when
 * to take and release it; it ends by itself at [EditLockState.untilMs] so a client that died
 * cannot leave the app locked, and the user can always break it on the phone.
 * Kept in memory only (a restart of the app releases it). Pure JVM.
 */
class EditLock(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<EditLockState?>(null)

    /** The lock as last set; may be expired, see [current]. */
    val state: StateFlow<EditLockState?> = _state.asStateFlow()

    /** The lock if held now (an expired one is dropped). */
    @Synchronized
    fun current(): EditLockState? {
        val s = _state.value ?: return null
        if (clock() < s.untilMs) return s
        _state.value = null
        return null
    }

    /** Takes the lock for [seconds] from now, or renews it (same start, new reason and end). */
    @Synchronized
    fun lock(reason: String, seconds: Int): EditLockState {
        require(seconds in MIN_SECONDS..MAX_SECONDS) { "seconds must be $MIN_SECONDS..$MAX_SECONDS" }
        val now = clock()
        val since = current()?.sinceMs ?: now
        return EditLockState(reason, since, now + seconds * 1000L).also { _state.value = it }
    }

    /** Releases the lock; false if it was not held. */
    @Synchronized
    fun unlock(): Boolean {
        val held = current() != null
        _state.value = null
        return held
    }

    companion object {
        const val MIN_SECONDS = 10
        const val MAX_SECONDS = 3600
        const val DEFAULT_SECONDS = 600
        const val MAX_REASON = 120

        /** The app's lock, shared by the API server and the screens. */
        val shared = EditLock()
    }
}
