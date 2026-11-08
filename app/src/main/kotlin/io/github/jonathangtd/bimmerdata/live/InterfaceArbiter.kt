/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who may use the K+DCAN cable. The live acquisition and the mini Tool32
 * ([io.github.jonathangtd.bimmerdata.diag.DiagSession]) each open their own port on the same USB
 * device, which cannot work at the same time, so the rule is: **a connected Tool32 session on a
 * real cable has priority**. While one is connected, live acquisition on USB is paused (its port
 * is closed) and it resumes by itself when the Tool32 session is closed. Simulated K-lines are
 * independent and never pause anything.
 */
object InterfaceArbiter {

    /** The live side (LiveRepository): gives the cable up and takes it back. */
    interface CableUser {
        /** Stops using the cable; returns once the port is closed. */
        suspend fun releaseCable()

        /** No Tool32 session holds the cable any more. */
        fun cableAvailable()
    }

    @Volatile var liveUser: CableUser? = null

    private val lock = Any()
    private var holds = 0
    private val _diagUsingCable = MutableStateFlow(false)

    /** True while a Tool32 session holds the real cable. */
    val diagUsingCable: StateFlow<Boolean> = _diagUsingCable.asStateFlow()

    /** Called by a Tool32 session before it opens a real cable. */
    suspend fun acquireForDiag() {
        synchronized(lock) {
            holds++
            _diagUsingCable.value = true
        }
        liveUser?.releaseCable()
    }

    /** Called once the Tool32 session closed its port. */
    fun releaseFromDiag() {
        val free = synchronized(lock) {
            holds = (holds - 1).coerceAtLeast(0)
            (holds == 0).also { if (it) _diagUsingCable.value = false }
        }
        if (free) liveUser?.cableAvailable()
    }
}
