/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LiveDataProxyComputedTest {
    private val torque = Measurement("ecu/0x58D0", "ecu", "Torque", "Nm", MeasurementSource.MeasurementTable(0x58D0, "STAT_TORQUE_WERT"))
    private val rpm = Measurement("ecu/0x4807", "ecu", "Engine speed", "rpm", MeasurementSource.MeasurementTable(0x4807, "STAT_RPM_WERT"))
    private val power = ComputedValues.measurement(ComputedDef("power", ComputedKind.Power, mapOf("torque" to torque.id, "rpm" to rpm.id), "Power")) {
        listOf(torque, rpm).firstOrNull { m -> m.id == it }
    }!!

    @Test
    fun `computed samples follow their inputs, in the samples and the readings`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val proxy = LiveDataProxy(scope)
            proxy.setMeasurements(listOf(power))
            assertEquals(listOf(power), proxy.requested, "the CSV column is the computed value")
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { proxy.readings.first { list -> list.any { it.first == power.id } } } }
            proxy.putSensorSamples(mapOf(torque.id to Sample(300.0, null, 1_000)))
            assertNull(proxy.samples.value[power.id], "one input only")
            proxy.putSensorSamples(mapOf(rpm.id to Sample(6000.0, null, 1_200)))
            val s = proxy.samples.value[power.id]!!
            assertEquals(256.29, s.value!!, 0.01)
            assertEquals(1_200L, s.timestampMs)
            assertEquals(256.29, reading.await().first { it.first == power.id }.second.value!!, 0.01)
        } finally {
            scope.cancel()
        }
    }
}
