/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VehicleTreeTest {

    private fun ecu(address: Int, name: String, group: String?, bus: String? = null) = VehicleEcu(address, name, group = group, bus = bus)

    private val e90 = Vehicle(
        id = "VA12345", label = "E90",
        ecus = listOf(
            ecu(0x00, "JBBF", "d_zgm"), ecu(0x01, "MRS", "d_sim", "SI"), ecu(0x12, "DME/DDE", "d_motor", "PT-CAN"),
            ecu(0x29, "DSC", "d_dsc", "PT-CAN"), ecu(0x40, "CAS", "d_cas", "K-CAN"), ecu(0x60, "KOMBI", "d_kombi", "K-CAN"),
            ecu(0x63, "MASK/CCC", "d_mmi", "MOST"), ecu(0x78, "KLIMA", "d_klima", "K-CAN"), ecu(0x72, "FRM", "d_kbm", "K-CAN"),
        ),
    )

    @Test
    fun `function areas come from the group files`() {
        val groups = VehicleTree.byFunction(e90).associate { it.category to it.ecus.map { e -> e.address } }
        assertEquals(
            mapOf(
                EcuCategory.Gateway to listOf(0x00), EcuCategory.Powertrain to listOf(0x12), EcuCategory.Chassis to listOf(0x29),
                EcuCategory.Body to listOf(0x40, 0x72), EcuCategory.Climate to listOf(0x78), EcuCategory.Infotainment to listOf(0x60, 0x63),
                EcuCategory.Safety to listOf(0x01),
            ),
            groups,
        )
        // areas in a fixed order
        assertEquals(EcuCategory.entries.filter { it in groups.keys }, VehicleTree.byFunction(e90).map { it.category })
    }

    @Test
    fun `unknown groups fall back on the name, then on the address`() {
        assertEquals(EcuCategory.Powertrain, VehicleTree.categoryOf(ecu(0x13, "DDE", null)))
        assertEquals(EcuCategory.Chassis, VehicleTree.categoryOf(ecu(0x2A, "XYZ", "d_unknown")))
        assertEquals(EcuCategory.Other, VehicleTree.categoryOf(ecu(0xA4, "XYZ", null)))
    }

    @Test
    fun `bus view only with topology, gateway first`() {
        assertNull(VehicleTree.byBus(e90))
        val groups = VehicleTree.byBus(e90.copy(busTopology = true))!!
        assertEquals(listOf(null, "PT-CAN", "K-CAN", "MOST", "SI"), groups.map { it.bus })
        assertEquals(EcuCategory.Gateway, groups.first().category)
        assertEquals(listOf(0x40, 0x60, 0x72, 0x78), groups.first { it.bus == "K-CAN" }.ecus.map { it.address })
    }

    @Test
    fun `series names map to the vehicle SGBDs`() {
        mapOf(
            "E87" to "e89x", "E90" to "e89x", "E92" to "e89x", "E82e" to "e89x", "E89" to "e89x", "E84" to "e89x",
            "E60" to "e60", "E63" to "e60", "E65" to "e65", "E70" to "e70", "E71" to "e70", "R56" to "r56", "R60" to "r56",
        ).forEach { (series, sgbd) -> assertEquals(sgbd, VehicleTables.vehicleSgbdForSeries(series), series) }
        assertNull(VehicleTables.vehicleSgbdForSeries("E46"))
        assertNull(VehicleTables.vehicleSgbdForSeries("R50"))
        assertNull(VehicleTables.vehicleSgbdForSeries("RR1"))
    }

    @Test
    fun `fault texts lose their code prefix`() {
        assertEquals("Ansauglufttemperatursensor, Signal", FaultService.stripCodePrefix("2F08 Ansauglufttemperatursensor, Signal", 0x2F08))
        assertEquals("Pumpenmotor defekt.", FaultService.stripCodePrefix("5DF0 - Pumpenmotor defekt.", 0x5DF0))
        assertEquals("AUSSENTEMPERATURSENSOR", FaultService.stripCodePrefix("AUSSENTEMPERATURSENSOR", 0x931B))
    }
}
