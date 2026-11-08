/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas.deepobd

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceBase
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObdTest
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNetException
import io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import io.github.jonathangtd.bimmerdata.ediabas.TestEcu
import io.github.jonathangtd.bimmerdata.ediabas.sim.DeepObdAdapterSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.DeepObdAdapterSimulator.Kind
import io.github.jonathangtd.bimmerdata.ediabas.sim.EcuSimulator
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * Deep OBD adapter layer: detection over the internal telegrams, refusal of other devices, and
 * real MSV70 jobs through interpreter -> [EdInterfaceObd] -> [DeepObdPort] -> simulated adapter
 * -> simulated K-line -> [EcuSimulator].
 */
class DeepObdTest {

    private val fast = DeepObdPort.Timeouts(adapterMs = 150, shortOffsetMs = 20, longOffsetMs = 60)

    private fun adapter(kind: Kind = Kind.DeepObd, ecu: EcuSimulator = EcuSimulator()) =
        DeepObdAdapterSimulator(SimulatedKLine.withSimulator(ecu), kind)

    @Test
    fun `detection reads type, firmware, id, voltage and CAN mode`() {
        val sim = adapter()
        val port = DeepObdPort(sim, fast)
        port.open()
        val info = port.info!!
        assertEquals(0x0005, info.adapterType)
        assertEquals("0.15", info.firmwareText)
        assertEquals("1122334455667708", info.serialText)
        assertEquals(12.4, info.voltage!!, 1e-9)
        assertEquals(CanMode.Auto, info.canMode)
        assertTrue(info.canMode!!.reachesKLine)
        assertEquals(
            listOf("82 F1 F1 FE FE 60", "84 F1 F1 06 55 AA D5 40", "82 F1 F1 FD FD 5E", "82 F1 F1 FB FB 5A", "82 F1 F1 FC FC 5C", "82 F1 F1 82 00 E6"),
            sim.internalRequests.map { TestEcu.hex(it) },
            "telegrams of EdCustomAdapterCommon.UpdateAdapterInfo + the CAN mode read of Deep OBD",
        )
        assertTrue(port.getDsr(), "EDIABAS sees the battery and ignition on")
        assertFalse(port.hasIgnitionLine, "but the UI must show the ignition as unknown")
        assertNull(EdInterfaceObd(port).readIgnitionLine())
        port.close()
        assertFalse(port.isOpen)
    }

    @Test
    fun `stock ELM327 firmware is refused with a clear message`() {
        val port = DeepObdPort(adapter(Kind.StockElm), fast)
        val ex = assertThrows<NotDeepObdException> { port.open() }
        assertTrue(ex.message!!.contains("ELM327 v1.5"), ex.message)
        assertTrue(ex.message!!.contains("Deep OBD"), ex.message)
        assertFalse(port.isOpen)
    }

    @Test
    fun `silent device and plain echo are refused`() {
        val silent = assertThrows<IOException> { DeepObdPort(adapter(Kind.Silent), fast).open() }
        assertEquals("The adapter does not answer", silent.message)
        val echo = assertThrows<NotDeepObdException> { DeepObdPort(adapter(Kind.EchoOnly), fast).open() }
        assertTrue(echo.message!!.contains("only echoes"), echo.message)
    }

    @Test
    fun `protocol helpers`() {
        assertEquals("82 F1 F1 FD FD 5E", TestEcu.hex(DeepObdProtocol.telegram(0xFD, 0xFD)))
        assertTrue(DeepObdProtocol.isConfigWrite(DeepObdProtocol.telegram(0x02, 0x00)), "CAN mode write")
        assertFalse(DeepObdProtocol.isConfigWrite(DeepObdProtocol.telegram(0x82, 0x00)), "CAN mode read")
        assertTrue(DeepObdProtocol.isConfigWrite(DeepObdProtocol.telegram(0xFF, 0xFF)), "reset")
        assertTrue(DeepObdProtocol.isConfigWrite(byteArrayOf(0x81.toByte(), 0, 0, 0x01, 0x82.toByte())), "legacy CAN mode write")
        assertFalse(DeepObdProtocol.isInternal(EdInterfaceObdTest.bytes("82 12 F1 1A 80")))
        assertTrue(DeepObdProtocol.isLongTelegram(byteArrayOf(0x80.toByte(), 0x12, 0xF1.toByte(), 0, 1, 0)))
        assertNull(CanMode.fromCode(0x42))
        assertFalse(CanMode.DCan500.reachesKLine)
    }

    // ---- through the interpreter ----

    private class Stack(val ediabas: EdiabasNet, val iface: EdInterfaceObd, val port: DeepObdPort, val sim: DeepObdAdapterSimulator)

    private fun stack(kind: Kind = Kind.DeepObd): Stack {
        TestEcu.file("MSV70.PRG")
        val sim = adapter(kind)
        val port = DeepObdPort(sim, fast)
        val iface = EdInterfaceObd(port)
        val ediabas = TestEcu.ediabas("msv70")
        ediabas.edInterfaceClass = iface
        assertTrue(iface.interfaceConnect(), iface.lastConnectError)
        return Stack(ediabas, iface, port, sim)
    }

    private fun Stack.run(job: String, args: String = ""): List<Map<String, ResultData>> {
        sim.car.requests.clear()
        ediabas.argString = args
        ediabas.executeJob(job)
        val sets = ediabas.resultSets!!
        assertEquals("OKAY", sets.last()["JOB_STATUS"]!!.opData, "$job JOB_STATUS")
        return sets
    }

    private fun withChecksum(hex: String): String {
        val b = EdInterfaceObdTest.bytes(hex)
        return TestEcu.hex(b + EdInterfaceBase.calcChecksumBmwFast(b, b.size))
    }

    @Test
    fun `IDENT, fast path 2C and 21 F0, FS_LESEN through the adapter`() {
        val s = stack()
        s.ediabas.use {
            val ident = s.run("IDENT")
            assertEquals("0756123", ident[1]["ID_BMW_NR"]!!.opData)
            assertEquals(0, s.sim.car.checksumErrors)

            val status = s.run("STATUS_MESSWERTBLOCK_LESEN", "3;0x4402;0x4300")
            assertEquals(
                listOf("83 12 F1 2C F0 04", "8E 12 F1 2C F0 02 01 02 44 02 01 02 03 01 43 00 01", "82 12 F1 21 F0").map(::withChecksum),
                s.sim.car.requests.map { TestEcu.hex(it) },
                "the same frames as over the FTDI cable",
            )
            assertEquals(90.0, status.last()["STAT_OELTEMPERATUR_WERT"]!!.opData as Double, 1e-4)

            // fast path: the block stays defined, only 21 F0 is repeated
            s.sim.car.requests.clear()
            val read = s.iface.transmitData(EdInterfaceObdTest.bytes("82 12 F1 21 F0"))!!
            assertEquals(0x61, read[3].toInt() and 0xFF, "positive answer to 21 F0")
            assertEquals(listOf(withChecksum("82 12 F1 21 F0")), s.sim.car.requests.map { TestEcu.hex(it) })

            val fs = s.run("FS_LESEN")
            val faults = fs.drop(1).filter { it.containsKey("F_ORT_NR") }
            assertEquals(2, faults.size)
            assertEquals("29CD Verbrennungsaussetzer, Zylinder 1", faults[0]["F_ORT_TEXT"]!!.opData)
        }
    }

    @Test
    fun `a job cannot reconfigure the adapter`() {
        val s = stack()
        s.ediabas.use {
            s.run("IDENT")
            s.sim.internalRequests.clear()
            val response = runCatching { s.iface.transmitData(EdInterfaceObdTest.bytes("82 F1 F1 02 01")) }.getOrNull()
            assertNull(response, "refused")
            assertEquals(CanMode.Auto.code, s.sim.canMode, "CAN mode unchanged")
            assertTrue(s.sim.internalRequests.isEmpty(), "nothing reached the adapter")
            // reading is fine
            val read = s.iface.transmitData(EdInterfaceObdTest.bytes("82 F1 F1 FC FC"))!!
            assertEquals(124, read[4].toInt() and 0xFF)
        }
    }

    @Test
    fun `CAN mode change through the settings API`() {
        val sim = adapter()
        val port = DeepObdPort(sim, fast)
        port.open()
        assertEquals(CanMode.KLineOnly, port.writeCanMode(CanMode.KLineOnly))
        assertEquals(CanMode.KLineOnly.code, sim.canMode)
        assertEquals(CanMode.KLineOnly, port.info!!.canMode)
        assertEquals(CanMode.KLineOnly.code, port.readCanMode())
        assertEquals(12.4, port.readVoltage()!!, 1e-9)
    }

    @Test
    fun `adapter set to D-CAN does not reach a K-line car`() {
        val s = stack()
        s.ediabas.use {
            s.run("IDENT")
            s.sim.canMode = CanMode.DCan500.code
            val start = System.nanoTime()
            val ex = runCatching { s.ediabas.executeJob("IDENT") }.exceptionOrNull()
            val ms = (System.nanoTime() - start) / 1e6
            assertTrue(ex is EdiabasNetException, "got $ex")
            assertEquals(ErrorCodes.EDIABAS_IFH_0009, (ex as EdiabasNetException).errorCode, "no answer")
            assertTrue(ms < 20_000, "bounded by the protocol timeouts: $ms ms")
        }
    }

    @Test
    fun `silent ECU after connecting gives IFH-0009`() {
        val s = stack()
        s.ediabas.use {
            s.run("IDENT")
            s.sim.car.responder = { emptyList() }
            val ex = runCatching { s.ediabas.executeJob("IDENT") }.exceptionOrNull()
            assertEquals(ErrorCodes.EDIABAS_IFH_0009, (ex as EdiabasNetException).errorCode)
        }
    }

    @Test
    fun `other baud rates and old firmware long telegrams are refused`() {
        val sim = adapter()
        sim.firmwareVersion = 0x000B
        val port = DeepObdPort(sim, fast)
        port.open()
        port.setParameters(10400)
        assertThrows<IOException> { port.write(EdInterfaceObdTest.bytes("82 12 F1 1A 80 1F")) }
        port.setParameters(115200)
        port.write(EdInterfaceObdTest.bytes("82 12 F1 1A 80 1F"))
        val long = byteArrayOf(0x80.toByte(), 0x12, 0xF1.toByte(), 0, 0, 1, 0x1A, 0)
        val ex = assertThrows<IOException> { port.write(long) }
        assertTrue(ex.message!!.contains("0.12"))
    }
}
