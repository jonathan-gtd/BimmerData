/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.SerialPort
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * An adapter that is not there (Bluetooth adapter out of range, not in the car) must not look like
 * a connection that is "reconnecting" forever: the engine gives up after
 * [AcquisitionEngine.Config.firstConnectAttempts] and says so in [AcquisitionEngine.link].
 */
class FirstConnectTest {

    /** A K-line whose port cannot be opened while [present] is false (like an RFCOMM connect to nobody). */
    private class Absent(private val line: SerialPort) : SerialPort by line {
        @Volatile var present = false
        @Volatile var opens = 0
        override fun open() {
            opens++
            if (!present) throw IOException("Cannot connect to the adapter: no answer")
            line.open()
        }
    }

    private fun engine(port: Absent, attempts: Int) = AcquisitionEngine(
        EdInterfaceObd(port),
        EdiabasMeasurementCatalog(LiveTestData.ecuDir()),
        AcquisitionEngine.Config(retryDelayMinMs = 20, retryDelayMaxMs = 50, firstConnectAttempts = attempts),
    )

    private fun port() = Absent(SimulatedKLine.withSimulator(EcuSimulator(), realisticTiming = false))

    @Test
    fun `an adapter that never answers is given up after the first attempts`() = runBlocking {
        val port = port()
        val engine = engine(port, attempts = 3)
        try {
            engine.start()
            val link = withTimeout(10_000) { engine.link.first { it != AcquisitionEngine.LinkState.Pending } }
            assertEquals(AcquisitionEngine.LinkState.Unreachable, link)
            withTimeout(5_000) { while (engine.isRunning) kotlinx.coroutines.delay(10) }
            assertEquals(3, port.opens, "exactly the configured attempts")
            assertEquals(AcquisitionState.Stopped, engine.status.value.state)
            assertTrue(engine.status.value.message.orEmpty().isNotBlank(), "the reason is kept: ${engine.status.value}")
            // nothing waits on a dead engine: one-off operations fail at once
            assertThrows<CableUnavailableException> { runBlocking { engine.exclusive("test") { } } }
            port.present = true
            kotlinx.coroutines.delay(200)
            assertEquals(3, port.opens, "no more tries once given up")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an adapter that answers within the attempts connects`() = runBlocking {
        val port = port()
        val engine = engine(port, attempts = 3)
        try {
            engine.start()
            withTimeout(10_000) { while (port.opens < 1) kotlinx.coroutines.delay(5) }
            port.present = true // plugged in / ignition on after the first try
            val link = withTimeout(10_000) { engine.link.first { it != AcquisitionEngine.LinkState.Pending } }
            assertEquals(AcquisitionEngine.LinkState.Up, link)
            assertTrue(engine.isRunning)
            assertFalse(engine.status.value.state == AcquisitionState.Stopped)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `without a limit the engine keeps trying (the default)`() = runBlocking {
        val port = port()
        val engine = engine(port, attempts = 0)
        try {
            engine.start()
            withTimeout(10_000) { while (port.opens < 6) kotlinx.coroutines.delay(5) }
            assertEquals(AcquisitionEngine.LinkState.Pending, engine.link.value)
            assertTrue(engine.isRunning)
        } finally {
            engine.close()
        }
    }
}
