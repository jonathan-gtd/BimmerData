/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.export.LineEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId

class ComputedValuesTest {
    private val torque = Measurement("ecu/0x58D0", "ecu", "Torque", "Nm", MeasurementSource.MeasurementTable(0x58D0, "STAT_TORQUE_WERT"))
    private val rpm = Measurement("ecu/0x4807", "ecu", "Engine speed", "rpm", MeasurementSource.MeasurementTable(0x4807, "STAT_RPM_WERT"))
    private val oil = Measurement("ecu/0x4402", "ecu", "Oil", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OIL_WERT"))
    private val def = ComputedDef("power", ComputedKind.Power, mapOf("torque" to torque.id, "rpm" to rpm.id), "Power")
    private val power = ComputedValues.measurement(def) { id -> listOf(torque, rpm).firstOrNull { it.id == id } }!!

    @Test
    fun `power in ch from torque and engine speed`() {
        // 300 N·m at 6000 rpm = 188.5 kW = 256.3 ch
        assertEquals(256.29, ComputedKind.Power.compute(listOf(300.0, 6000.0)), 0.01)
        assertEquals(0.0, ComputedKind.Power.compute(listOf(0.0, 6000.0)))
        assertEquals(0.0, ComputedKind.Power.compute(listOf(-40.0, 3000.0)), "engine braking gives 0, not a negative power")
        assertEquals("calc/power", power.id)
        assertEquals("calc", power.sgbd)
        assertEquals("ch", power.unit)
        assertEquals("Power", power.label)
        assertEquals(listOf(torque, rpm), (power.source as MeasurementSource.Computed).inputs)
    }

    @Test
    fun `a sample only from two fresh numbers, at the newer input's time`() {
        val ok = ComputedValues.evaluate(power, mapOf(torque.id to Sample(300.0, null, 10_000), rpm.id to Sample(6000.0, null, 10_400)))!!
        assertEquals(256.29, ok.value!!, 0.01)
        assertEquals(10_400L, ok.timestampMs)
        assertNotNull(ComputedValues.evaluate(power, mapOf(torque.id to Sample(300.0, null, 10_000), rpm.id to Sample(6000.0, null, 12_000))), "2 s apart is still fine")
        assertNull(ComputedValues.evaluate(power, mapOf(torque.id to Sample(300.0, null, 10_000), rpm.id to Sample(6000.0, null, 12_001))), "stale input")
        assertNull(ComputedValues.evaluate(power, mapOf(torque.id to Sample(300.0, null, 10_000))), "missing input")
        assertNull(ComputedValues.evaluate(power, mapOf(torque.id to Sample(null, "n/a", 10_000), rpm.id to Sample(6000.0, null, 10_000))), "text input")
        assertNull(ComputedValues.evaluate(power, mapOf(torque.id to Sample(Double.NaN, null, 10_000), rpm.id to Sample(6000.0, null, 10_000))))
        assertNull(ComputedValues.evaluate(rpm, mapOf(rpm.id to Sample(1.0, null, 1))), "not a computed value")
    }

    @Test
    fun `update computes only what a fresh input touches`() {
        val all = mapOf(torque.id to Sample(200.0, null, 1_000), rpm.id to Sample(3000.0, null, 1_100), oil.id to Sample(90.0, null, 1_100))
        assertEquals(setOf("calc/power"), ComputedValues.update(listOf(power), mapOf(rpm.id to all[rpm.id]!!), all).keys)
        assertTrue(ComputedValues.update(listOf(power), mapOf(oil.id to all[oil.id]!!), all).isEmpty())
        assertTrue(ComputedValues.update(emptyList(), all, all).isEmpty())
        val why = ComputedValues.unavailable(listOf(power), mapOf(torque.id to "refused"))
        assertEquals("Torque: refused", why["calc/power"])
    }

    @Test
    fun `computed values are read through their inputs`() {
        assertEquals(listOf(oil, torque, rpm), ComputedValues.expand(listOf(oil, power, rpm)))
        assertEquals(listOf(oil, power, rpm, torque), ComputedValues.withInputs(listOf(oil, power, rpm)))
        assertEquals(setOf(oil.id, torque.id, rpm.id), ComputedValues.expandIds(listOf(oil.id, "calc/power", rpm.id), listOf(def)))
        assertEquals(setOf("calc/other"), ComputedValues.expandIds(listOf("calc/other"), listOf(def)), "unknown computed id counted as itself")
        assertNull(ComputedValues.measurement(def) { null }, "an input missing from the ECU folder: no value")
        assertNull(ComputedValues.measurement(def.copy(inputs = mapOf("torque" to torque.id))) { torque }, "a role missing")
    }

    @Test
    fun `read scope counts the inputs of a computed value`() {
        // the profile: the power (2 table inputs) + 2 more values of the ECU; a block holds 3
        val extra = Measurement("ecu/0x4300", "ecu", "Coolant", "°C", MeasurementSource.MeasurementTable(0x4300, "STAT_COOL_WERT"))
        assertEquals(listOf(oil), ReadScope.select(listOf(power, oil, extra), listOf(oil), maxPerBlock = 3), "4 table values: only the page shown")
        assertEquals(listOf(oil, power), ReadScope.select(listOf(power, oil), listOf(oil), maxPerBlock = 3))
    }

    @Test
    fun `the catalog offers the computed values whose inputs exist`() {
        val base = object : MeasurementCatalog {
            override fun sgbdsWithMeasurements() = listOf("ecu")
            override fun measurements(sgbd: String) = if (sgbd == "ecu") listOf(torque, rpm, oil) else emptyList()
        }
        val broken = ComputedDef("power_2", ComputedKind.Power, mapOf("torque" to "other/0x1", "rpm" to rpm.id), "")
        val catalog = ComputedCatalog(base, { listOf(def, broken) }, "Computed values")
        assertEquals(listOf("ecu", "calc"), catalog.sgbdsWithMeasurements())
        assertEquals(listOf("calc/power"), catalog.measurements("calc").map { it.id })
        assertEquals(listOf(torque, rpm, oil), catalog.measurements("ecu"))
        assertEquals(2, catalog.sgbdInfos().last().measurementCount)
        assertEquals("Torque · Engine speed", catalog.measurements("calc").single().description)
    }

    @Test
    fun `Telegraf line of a computed value`() {
        val line = LineEncoder("My car").encode(power, Sample(256.3, null, 1_762_633_500_000))!!
        assertTrue(line.contains(",ecu=calc,"), line)
        assertTrue(line.contains(",id=power,"), line)
        assertTrue(line.contains(",unit=ch "), line)
        assertTrue(line.contains(" value=256.3 "), line)
    }

    private class Source : LiveDataSource {
        override val samples = MutableStateFlow<Map<String, Sample>>(emptyMap())
        override val status: StateFlow<AcquisitionStatus> = MutableStateFlow(AcquisitionStatus(AcquisitionState.Running))
        override fun setMeasurements(measurements: List<Measurement>) = Unit
    }

    private class Sink : ByteArrayOutputStream() {
        @Volatile var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `CSV column of a computed value`() = runBlocking {
        val source = Source()
        val sink = Sink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = 1_762_590_652_000L
        val recorder = CsvSessionRecorder(
            source = source, columns = { listOf(rpm, power) }, output = { sink }, scope = scope, pin = {},
            dispatcher = Dispatchers.Default, clock = { now }, zone = ZoneId.of("UTC"),
        )
        try {
            recorder.start()
            delay(50)
            now += 100
            source.samples.value = mapOf(rpm.id to Sample(6000.0, null, now), power.id to Sample(256.29, null, now))
            withTimeout(5000) { while (recorder.state.value.rows < 1L) delay(5) }
            recorder.stop()
            withTimeout(5000) { while (!sink.closed) delay(5) }
            val lines = String(sink.toByteArray(), Charsets.UTF_8).removePrefix("﻿").split("\r\n")
            assertTrue(lines[0].contains(";Power [ch];"), lines[0])
            assertTrue(lines[1].contains(";6000;256.29;"), lines[1])
        } finally {
            scope.cancel()
        }
    }
}
