/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MeasurementRequestsTest {
    private fun m(id: String) = Measurement(id, "msv70", id, "", MeasurementSource.MeasurementTable(0, id))

    private val applied = mutableListOf<List<String>>()
    private val requests = MeasurementRequests { list -> applied += list.map { it.id } }

    @Test
    fun `foreground clients are merged without duplicates, phone first`() {
        requests.set(MeasurementClient.Car, listOf(m("rpm"), m("oil")), foreground = true)
        requests.set(MeasurementClient.Phone, listOf(m("oil"), m("water")), foreground = true)
        assertEquals(listOf("oil", "water", "rpm"), requests.merged().map { it.id })
    }

    @Test
    fun `a phone in the background does not steal read cycles from the car`() {
        requests.set(MeasurementClient.Phone, listOf(m("water")), foreground = false)
        requests.set(MeasurementClient.Car, listOf(m("rpm")), foreground = true)
        assertEquals(listOf("rpm"), requests.merged().map { it.id })
    }

    @Test
    fun `background requests are kept when nobody is in the foreground`() {
        requests.set(MeasurementClient.Phone, listOf(m("water")), foreground = true)
        requests.set(MeasurementClient.Car, listOf(m("rpm")), foreground = true)
        requests.clear(MeasurementClient.Car)
        requests.set(MeasurementClient.Phone, listOf(m("water")), foreground = false)
        assertEquals(listOf("water"), requests.merged().map { it.id })
    }

    @Test
    fun `the engine is only told about changes`() {
        requests.set(MeasurementClient.Phone, listOf(m("water")), foreground = true)
        requests.set(MeasurementClient.Phone, listOf(m("water")), foreground = false)
        requests.clear(MeasurementClient.Car)
        assertEquals(listOf(listOf("water")), applied)
    }

    @Test
    fun `the Graph tab on screen replaces the dashboard page, and asks for nothing once closed`() {
        // dashboard page shown, then the user opens the Graph tab: the dashboard goes to the background
        requests.set(MeasurementClient.Phone, listOf(m("water"), m("oil")), foreground = false)
        requests.set(MeasurementClient.Graph, listOf(m("rpm"), m("oil")), foreground = true)
        assertEquals(listOf("rpm", "oil"), requests.merged().map { it.id })
        // with Android Auto on screen too: both, car first
        requests.set(MeasurementClient.Car, listOf(m("speed")), foreground = true)
        assertEquals(listOf("speed", "rpm", "oil"), requests.merged().map { it.id })
        // Graph tab closed (screen off): back to what the dashboard and the car read
        requests.clear(MeasurementClient.Graph)
        requests.clear(MeasurementClient.Car)
        assertEquals(listOf("water", "oil"), requests.merged().map { it.id })
    }
}
