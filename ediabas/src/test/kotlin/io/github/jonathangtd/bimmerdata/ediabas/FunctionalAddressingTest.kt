/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.sim.VehicleSimulator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Functional addressing on BMW-FAST: one request to a functional address (format byte 0xC0,
 * target 0xEF = all ECUs), one answer per ECU. As in EdiabasLib, the interface returns one answer
 * per exchange and the job collects the others with receive-only exchanges (send_and_receive with
 * an empty request: EdInterfaceObd.cs TransKwp2000 with sendDataLength 0 skips sending and only
 * waits for the next telegram) until the bus stays silent (IFH-0009).
 */
class FunctionalAddressingTest {

    private val identAll = EdInterfaceObdTest.bytes("C2 EF F1 1A 80")

    private fun obd(sim: VehicleSimulator): Pair<EdInterfaceObd, io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine> {
        val line = sim.kLine(realisticTiming = false)
        val iface = EdInterfaceObd(line)
        assertTrue(iface.interfaceConnect())
        iface.commParameter = EdInterfaceBase.CommParameterBmwFast
        return iface to line
    }

    @Test
    fun `parse recognises functional telegrams`() {
        val tel = BmwFast.parse(identAll)!!
        assertTrue(tel.functional)
        assertEquals(0xEF, tel.target)
        assertEquals(0xF1, tel.source)
        assertArrayEquals(EdInterfaceObdTest.bytes("1A 80"), tel.data)
        assertFalse(BmwFast.parse(EdInterfaceObdTest.bytes("82 12 F1 1A 80"))!!.functional)
    }

    @Test
    fun `interface returns one answer per exchange, then receive-only exchanges give the others`() {
        val sim = VehicleSimulator.e90(engine = null)
        val (iface, line) = obd(sim)
        val first = iface.transmitData(identAll)!!
        assertEquals(0x00, first[2].toInt() and 0xFF, "lowest address answers first")
        val sources = mutableListOf(first[2].toInt() and 0xFF)
        while (true) {
            val next = iface.transmitData(ByteArray(0)) ?: break
            assertEquals(0xF1, next[1].toInt() and 0xFF)
            assertEquals(EdInterfaceBase.calcChecksumBmwFast(next, next.size - 1), next.last())
            sources += next[2].toInt() and 0xFF
        }
        assertEquals(sim.ecus.map { it.address }, sources)
        // only the functional request went on the line, with its checksum
        assertEquals(listOf("C2 EF F1 1A 80 3C"), line.requests.map { TestEcu.hex(it) })
        assertEquals(0, line.checksumErrors)
    }

    @Test
    fun `response pending of one ECU does not hide the other answers`() {
        val base = VehicleSimulator.e90(engine = null)
        val ecus = base.ecus.map { e ->
            if (e.address != 0x12) e else VehicleSimulator.SimEcu(e.address, e.name, e.diagIndex, e.variantIndex, e.bus, e.currentFaults, e.storesVin, e.partNumber, responsePending = 2)
        }
        val sim = VehicleSimulator(ecus, base.vin)
        val (iface, _) = obd(sim)
        val answers = mutableListOf(iface.transmitData(identAll)!!)
        while (true) answers += iface.transmitData(ByteArray(0)) ?: break
        // the 7F 1A 78 telegrams are consumed by the interface (NR78 handling per device address)
        assertTrue(answers.none { it[3] == 0x7F.toByte() })
        assertEquals(sim.ecus.size, answers.size)
        assertEquals(0x12, answers.last()[2].toInt() and 0xFF, "the DME answers last, after its NR78")
        assertEquals(2L, iface.stats.nr78)
    }

    @Test
    fun `FakeInterface multiResponder queues the other answers`() {
        val sim = VehicleSimulator.e90(engine = null)
        val fake = FakeInterface()
        fake.multiResponder = sim::respond
        fake.interfaceConnect()
        val got = mutableListOf(fake.transmitData(identAll)!!)
        while (true) got += fake.transmitData(ByteArray(0)) ?: break
        assertEquals(sim.ecus.size, got.size)
        assertNull(fake.exchanges.last().response)
        // a physical request after that is answered by one ECU only
        val dme = fake.transmitData(EdInterfaceObdTest.bytes("82 12 F1 1A 80"))!!
        assertEquals(0x12, dme[2].toInt() and 0xFF)
        assertNull(fake.transmitData(ByteArray(0)))
    }

    @Test
    fun `E89X IDENT_FUNKTIONAL collects every ECU through the real interface`() {
        TestEcu.file("E89X.PRG")
        TestEcu.file("T_GRTB.PRG")
        val sim = VehicleSimulator.e90(engine = null)
        val (iface, line) = obd(sim)
        TestEcu.ediabas("e89x").use { ediabas ->
            ediabas.edInterfaceClass = iface
            ediabas.executeJob("IDENT_FUNKTIONAL")
            val sets = ediabas.resultSets!!.drop(1).filter { it.containsKey("ID_SG_ADR") }
            assertEquals(sim.ecus.map { it.address.toLong() }, sets.map { it["ID_SG_ADR"]!!.opData })
            val dme = sets.first { it["ID_SG_ADR"]!!.opData == 0x12L }
            assertEquals("MSV70", dme["ECU_SGBD"]!!.opData)
            assertEquals("D_MOTOR", dme["ECU_GRUPPE"]!!.opData)
            assertEquals("OKAY", ediabas.resultSets!!.last()["JOB_STATUS"]!!.opData)
            assertTrue(line.requests.any { TestEcu.hex(it).startsWith("C2 EF F1 1A 80") })
        }
    }

    @Test
    fun `E89X FS_LESEN_FUNKTIONAL and FS_LOESCHEN_FUNKTIONAL through the real interface`() {
        TestEcu.file("E89X.PRG")
        val sim = VehicleSimulator.e90(engine = null)
        val (iface, line) = obd(sim)
        TestEcu.ediabas("e89x").use { ediabas ->
            ediabas.edInterfaceClass = iface
            ediabas.executeJob("FS_LESEN_FUNKTIONAL")
            val counts = ediabas.resultSets!!.drop(1).filter { it.containsKey("F_ANZ") }
                .associate { (it["ID_SG_ADR"]!!.opData as Long).toInt() to it["F_ANZ"]!!.opData as Long }
            assertEquals(sim.ecus.associate { it.address to it.currentFaults.size.toLong() }, counts)
            // the DME answer is the real MSV70 frame of the user's Tool32 trace
            val dme = ediabas.resultSets!!.first { it["ID_SG_ADR"]?.opData == 0x12L }
            assertEquals("85 F1 12 58 01 2F 08 21 39", TestEcu.hex(dme["_TEL_ANTWORT"]!!.opData as ByteArray))
            assertEquals(0x2F08L, dme["F_ORT1_NR"]!!.opData)

            ediabas.executeJob("FS_LOESCHEN_FUNKTIONAL")
            assertTrue(line.requests.any { TestEcu.hex(it).startsWith("C3 EF F1 14 FF FF") })
            assertTrue(sim.ecus.all { it.currentFaults.isEmpty() && it.clears == 1 })
        }
    }
}
