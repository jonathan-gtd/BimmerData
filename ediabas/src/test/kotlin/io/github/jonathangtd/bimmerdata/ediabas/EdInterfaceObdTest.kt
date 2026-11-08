/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine.Reply
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** BMW-FAST protocol layer of [EdInterfaceObd] against a [SimulatedKLine] (no ECU files needed). */
class EdInterfaceObdTest {

    private var ediabas: EdiabasNet? = null

    @AfterEach
    fun tearDown() {
        ediabas?.close()
    }

    private fun connect(line: SimulatedKLine): EdInterfaceObd {
        val ed = EdiabasNet("IfhTrace=0")
        ediabas = ed
        val iface = EdInterfaceObd(line)
        ed.edInterfaceClass = iface
        assertTrue(iface.interfaceConnect())
        iface.commParameter = EdInterfaceBase.CommParameterBmwFast // what MSV70 INITIALISIERUNG sets
        return iface
    }

    private fun ecuAnswer(data: String) = BmwFast.buildTelegram(0xF1, 0x12, bytes(data))

    private fun errorOf(block: () -> Unit): ErrorCodes? =
        (runCatching(block).exceptionOrNull() as? EdiabasNetException)?.errorCode

    @Test
    fun `communication parameters configure the cable like EdiabasLib`() {
        val line = SimulatedKLine.withEcu { null }
        val iface = connect(line)
        assertTrue(iface.bmwFastProtocol)
        assertEquals(115200, line.baudRate)
        assertTrue(line.dtr, "DTR on during the session (adapter with echo)")
        assertFalse(line.rts, "RTS off")
    }

    @Test
    fun `checksum is appended and the echo is stripped`() {
        val answer = ecuAnswer("5A 80 00 00 00 75 61 23")
        val line = SimulatedKLine.withEcu { answer }
        val iface = connect(line)
        val response = iface.transmitData(bytes("82 12 F1 1A 80"))
        assertEquals("82 12 F1 1A 80 1F", TestEcu.hex(line.requests.single()), "checksum 0x1F appended")
        assertEquals(0, line.checksumErrors)
        assertArrayEquals(answer, response, "response without the echoed request")
    }

    @Test
    fun `long requests use the length byte`() {
        val line = SimulatedKLine.withEcu { ecuAnswer("6C F0") }
        val iface = connect(line)
        val data = ByteArray(0x45) { (it + 1).toByte() }
        val request = byteArrayOf(0x80.toByte(), 0x12, 0xF1.toByte(), 0x45) + data
        iface.transmitData(request)
        val sent = line.requests.single()
        assertEquals(4 + 0x45 + 1, sent.size)
        assertEquals(EdInterfaceBase.calcChecksumBmwFast(sent, sent.size - 1), sent.last())
    }

    @Test
    fun `short, one byte and two byte length responses`() {
        for (size in listOf(0x3F, 0x50, 0xFF, 0x120)) {
            val data = byteArrayOf(0x61, 0x01) + ByteArray(size - 2) { it.toByte() }
            val answer = BmwFast.buildTelegram(0xF1, 0x12, data)
            val iface = connect(SimulatedKLine.withEcu { answer })
            val response = iface.transmitData(bytes("82 12 F1 21 01"))!!
            assertArrayEquals(answer, response, "data size $size")
            val (length, offset) = EdInterfaceBase.dataLengthBmwFast(response)
            assertEquals(size, length)
            assertEquals(if (size <= 0x3F) 3 else if (size <= 0xFF) 4 else 6, offset)
            ediabas?.close()
        }
    }

    @Test
    fun `7F 78 response pending is followed by the real answer`() {
        val pending = ecuAnswer("7F 18 78")
        val answer = ecuAnswer("58 02 29 CD 24 2A FA 60")
        val line = SimulatedKLine({ listOf(Reply(pending), Reply(pending, 200), Reply(answer, 300)) })
        val iface = connect(line)
        val start = System.nanoTime()
        val response = iface.transmitData(bytes("84 12 F1 18 02 FF FF"))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertArrayEquals(answer, response)
        assertEquals(2, iface.stats.nr78)
        assertTrue(elapsedMs >= 500, "waited for the pending ECU ($elapsedMs ms)")
    }

    @Test
    fun `too many 7F 78 give the last pending response like EdiabasLib`() {
        val pending = ecuAnswer("7F 18 78")
        val line = SimulatedKLine({ List(6) { Reply(pending, 5) } })
        val iface = connect(line)
        // RetryNr78 = 2 (comm parameter 5): the 4th NR78 exceeds the limit and is returned as answer
        val response = iface.transmitData(bytes("84 12 F1 18 02 FF FF"))
        assertArrayEquals(pending, response)
        assertEquals(4, iface.stats.nr78)
    }

    @Test
    fun `silent ECU times out with IFH-0009`() {
        val line = SimulatedKLine.withEcu { null }
        val iface = connect(line)
        val start = System.nanoTime()
        assertEquals(ErrorCodes.EDIABAS_IFH_0009, errorOf { iface.transmitData(bytes("82 12 F1 1A 80")) })
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs in 1200..2500, "ParTimeoutStd 1200 ms + AddRecTimeout ($elapsedMs ms)")
        assertEquals(ErrorCodes.EDIABAS_IFH_0009, ediabas!!.errorCodeLast)
    }

    @Test
    fun `repeat counter retries the telegram`() {
        var calls = 0
        val answer = ecuAnswer("5A 80 01")
        val line = SimulatedKLine.withEcu { if (++calls == 1) null else answer }
        val iface = connect(line)
        iface.commRepeats = 1u
        assertArrayEquals(answer, iface.transmitData(bytes("82 12 F1 1A 80")))
        assertEquals(2, line.requests.size)
        assertEquals(1, iface.stats.retries)
    }

    @Test
    fun `wrong echo is an interface error IFH-0003`() {
        val line = SimulatedKLine.withEcu { ecuAnswer("5A 80 01") }
        line.echoTransform = { sent -> sent.copyOf().also { it[2] = 0x00 } }
        val iface = connect(line)
        assertEquals(ErrorCodes.EDIABAS_IFH_0003, errorOf { iface.transmitData(bytes("82 12 F1 1A 80")) })
    }

    @Test
    fun `no echo is an interface error IFH-0003`() {
        val line = SimulatedKLine.withEcu { null }
        line.echo = false
        val iface = connect(line)
        assertEquals(ErrorCodes.EDIABAS_IFH_0003, errorOf { iface.transmitData(bytes("82 12 F1 1A 80")) })
    }

    @Test
    fun `bad response checksum gives IFH-0009`() {
        val answer = ecuAnswer("5A 80 01").also { it[it.size - 1] = (it.last() + 1).toByte() }
        val iface = connect(SimulatedKLine.withEcu { answer })
        assertEquals(ErrorCodes.EDIABAS_IFH_0009, errorOf { iface.transmitData(bytes("82 12 F1 1A 80")) })
    }

    @Test
    fun `blocked services never reach the line`() {
        val line = SimulatedKLine.withEcu { ecuAnswer("6E 01") }
        val iface = connect(line)
        val frames = ArrayList<EdInterfaceObd.Frame>()
        iface.frameListener = { frames.add(it) }
        assertEquals(ErrorCodes.EDIABAS_IFH_0006, errorOf { iface.transmitData(bytes("84 12 F1 3B 01 02 03")) })
        assertEquals(ErrorCodes.EDIABAS_IFH_0006, errorOf { iface.transmitData(bytes("83 12 F1 10 85 00")) })
        assertTrue(line.writes.isEmpty(), "nothing written")
        assertTrue(frames.all { it.direction == EdInterfaceObd.FrameDirection.Error })
        // fault memory clear stays allowed
        iface.transmitData(bytes("83 12 F1 14 FF FF"))
        assertEquals(1, line.requests.size)
    }

    @Test
    fun `transmitCancel aborts a pending receive quickly`() {
        val line = SimulatedKLine.withEcu { null }
        val iface = connect(line)
        val canceller = Thread {
            Thread.sleep(100)
            iface.transmitCancel(true)
        }
        canceller.start()
        val start = System.nanoTime()
        assertEquals(ErrorCodes.EDIABAS_IFH_0009, errorOf { iface.transmitData(bytes("82 12 F1 1A 80")) })
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        canceller.join()
        assertTrue(elapsedMs < 600, "cancelled after $elapsedMs ms")
    }

    @Test
    fun `frames and DSR based voltages`() {
        val answer = ecuAnswer("5A 80 01")
        val line = SimulatedKLine.withEcu { answer }
        val iface = connect(line)
        val frames = ArrayList<EdInterfaceObd.Frame>()
        iface.frameListener = { frames.add(it) }
        iface.transmitData(bytes("82 12 F1 1A 80"))
        assertEquals(listOf("Tx", "Echo", "Rx"), frames.map { it.direction.name })
        assertTrue(frames.zipWithNext().all { (a, b) -> a.timeNanos <= b.timeNanos })
        assertArrayEquals(answer, frames.last().data)

        assertEquals(12000L, iface.ignitionVoltage)
        assertEquals(12000L, iface.batteryVoltage)
        line.dsrState = false
        assertEquals(0L, iface.ignitionVoltage)
    }

    @Test
    fun `unsupported concept reports IFH-0014`() {
        val iface = connect(SimulatedKLine.withEcu { null })
        assertEquals(ErrorCodes.EDIABAS_IFH_0014, errorOf { iface.commParameter = uintArrayOf(0x0006u, 9600u, 0u, 0u, 0u, 0u, 0u, 0u) })
        assertFalse(iface.bmwFastProtocol)
    }

    companion object {
        fun bytes(hex: String): ByteArray = hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    }
}
