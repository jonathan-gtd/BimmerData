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
}
