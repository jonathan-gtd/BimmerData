/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.vehicle

import io.github.jonathangtd.bimmerdata.live.vehicle.DetectedEcuSource
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.Fault
import io.github.jonathangtd.bimmerdata.live.vehicle.NamedValue
import io.github.jonathangtd.bimmerdata.live.vehicle.ScanMethod
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import io.github.jonathangtd.bimmerdata.live.vehicle.ecuSummaries
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GarageJsonTest {

    private val e90 = Vehicle(
        id = "VA12345", label = "My E90", vin = "WBAVB71070VA12345", vinShort = "VA12345", series = "E8x/E9x", seriesSgbd = "e89x",
        seriesByUser = true,
        ecus = listOf(
            VehicleEcu(0x12, "DME/DDE", "Motorelektronik MS 70", "d_motor", "msv70", true, "PT-CAN", listOf(NamedValue("ID_BMW_NR", "7561234"))),
            VehicleEcu(0x63, "MASK/CCC", null, "d_mmi", null, false),
        ),
        scannedAtMs = 1_762_600_000_000, scanMethod = ScanMethod.Functional, busTopology = true,
    )
    private val mini = Vehicle(id = "car-1", label = "Mini", ecus = emptyList())

    @Test
    fun `round trip keeps vehicles, current vehicle, faults and mode`() {
        val garage = Garage(
            vehicles = listOf(e90, mini),
            currentId = "VA12345",
            faults = mapOf(
                "VA12345" to listOf(
                    EcuFaults(0x12, EcuFaultStatus.Faults, listOf(Fault(0x2F08, "Ansauglufttemperatursensor, Signal", "Kurzschluss", hex = "2F 08 21")), readAtMs = 5),
                    EcuFaults(0x63, EcuFaultStatus.NotResponding, message = "IFH_0009", readAtMs = 5),
                ),
            ),
            mode = LinkMode.Usb,
        )
        assertEquals(garage, GarageJson.decode(GarageJson.encode(garage)))
    }

    @Test
    fun `unknown keys, missing current vehicle and orphan faults are tolerated`() {
        val text = """{"version":1,"future":true,"vehicles":[{"id":"A","label":"A","ecus":[{"address":1,"name":"MRS","x":2}]}],
            "currentId":"gone","faults":{"gone":[{"address":1,"status":"Ok"}],"A":[{"address":1,"status":"Weird"}]},"mode":"Bluetooth"}"""
        val g = GarageJson.decode(text)
        assertEquals("A", g.currentId)
        assertEquals(listOf("A"), g.faults.keys.toList())
        assertEquals(emptyList<EcuFaults>(), g.faults["A"])
        assertEquals(LinkMode.Simulation, g.mode)
        assertEquals("MRS", g.current!!.ecus.single().name)
    }

    @Test
    fun `detected ECU summaries for the other parts of the app`() {
        val summaries = e90.ecuSummaries()
        assertEquals(listOf(0x12, 0x63), summaries.map { it.address })
        assertEquals("msv70", summaries.first().sgbd)
        assertNull(summaries.last().sgbd)
        assertEquals(emptyList<Any>(), (null as Vehicle?).ecuSummaries())
        val source = object : DetectedEcuSource {
            override val detectedEcus = MutableStateFlow(summaries)
        }
        assertEquals(listOf("msv70"), source.sgbds(), "only variants present in the Ecu folder")
    }
}
