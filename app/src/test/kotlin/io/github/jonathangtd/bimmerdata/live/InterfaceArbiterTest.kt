/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.diag.DiagSession
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.SerialPort
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** A connected mini Tool32 session on a real cable pauses the live side until it is closed. */
class InterfaceArbiterTest {

    private class Live : InterfaceArbiter.CableUser {
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        override suspend fun releaseCable() {
            events += "release"
        }

        override fun cableAvailable() {
            events += "available"
        }
    }

    /** Looks like a real cable to DiagSession (not a SimulatedKLine). */
    private class CablePort(private val line: SerialPort) : SerialPort by line

    private val dir: File = Files.createTempDirectory("ecu").toFile()

    @AfterEach
    fun cleanup() {
        InterfaceArbiter.liveUser = null
        dir.deleteRecursively()
    }

    @Test
    fun `real cable sessions pause live acquisition, simulated ones do not`() = runBlocking {
        val live = Live()
        InterfaceArbiter.liveUser = live

        val sim = DiagSession(dir, EdInterfaceObd(SimulatedKLine.withEcu(respond = EcuSimulator()::respond)), "sim")
        sim.connect()
        sim.close()
        delay(100)
        assertTrue(live.events.isEmpty(), "simulation does not touch the cable: ${live.events}")

        val first = DiagSession(dir, EdInterfaceObd(CablePort(SimulatedKLine.withEcu(respond = EcuSimulator()::respond))), "usb 1")
        first.connect()
        assertEquals(listOf("release"), live.events)
        assertTrue(InterfaceArbiter.diagUsingCable.value)
        // a second session (reconnect in Tool32) before the first one is closed
        val second = DiagSession(dir, EdInterfaceObd(CablePort(SimulatedKLine.withEcu(respond = EcuSimulator()::respond))), "usb 2")
        second.connect()
        first.close()
        delay(100)
        assertEquals(listOf("release", "release"), live.events, "still held by the second session")
        second.close()
        withTimeout(2000) { while (live.events.size < 3) delay(10) }
        assertEquals(listOf("release", "release", "available"), live.events)
        assertFalse(InterfaceArbiter.diagUsingCable.value)
    }
}
