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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.ZoneId

class PhoneSensorsTest {

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `a fix gives one sample per known value, speed in km h`() {
        val fix = GpsFix(1_000, 48.8584, 2.2945, altitudeM = 35.0, speedMs = 25.0, bearingDeg = 90.0, accuracyM = 4.5)
        val s = fix.toSamples()
        assertEquals(6, s.size)
        assertEquals(48.8584, s["gps/latitude"]!!.value)
        assertEquals(90.0, s["gps/speed"]!!.value!!, 1e-9)
        assertTrue(s.values.all { it.timestampMs == 1_000L })
        // no altitude / speed / heading / accuracy from the provider: no sample, not a fake 0
        val bare = GpsFix(2_000, 1.0, 2.0).toSamples()
        assertEquals(setOf("gps/latitude", "gps/longitude"), bare.keys)
        assertNull(GpsFix(3_000, 1.0, 2.0, speedMs = Double.NaN).toSamples()["gps/speed"])
    }

    @Test
    fun `fix time is taken on the wall clock from the elapsed realtime stamp`() {
        // the fix is 250 ms old
        assertEquals(9_750L, GpsFix.wallTime(nowWallMs = 10_000, nowElapsedNanos = 5_000_000_000, fixElapsedNanos = 4_750_000_000))
        // a stamp in the future is clamped
        assertEquals(10_000L, GpsFix.wallTime(10_000, 5_000_000_000, 6_000_000_000))
    }

    @Test
    fun `gps measurements are sensors, ids gps slash key`() {
        val all = PhoneSensors.gpsMeasurements()
        assertEquals(listOf("gps/latitude", "gps/longitude", "gps/altitude", "gps/speed", "gps/bearing", "gps/accuracy"), all.map { it.id })
        assertTrue(all.all(PhoneSensors::isSensor))
        assertTrue(all.all { it.sgbd == PhoneSensors.GPS })
        assertEquals("km/h", PhoneSensors.measurement("gps/speed")!!.unit)
        assertNull(PhoneSensors.measurement("msv70/0x4402"))
        assertTrue(PhoneSensors.isSensorId("gps/speed"))
        assertFalse(PhoneSensors.isSensorId("msv70/0x4402"))
        // labels can be translated by the app
        assertEquals("Vitesse GPS", PhoneSensors.gpsMeasurements { if (it == PhoneSensors.GpsField.Speed) "Vitesse GPS" else it.defaultLabel }[3].label)
    }

    @Test
    fun `sensor catalog lists gps first and keeps the ECUs`() {
        val ecu = Measurement("msv70/0x4402", "msv70", "Öltemperatur", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OELTEMPERATUR_WERT"))
        val base = object : MeasurementCatalog {
            override fun sgbdsWithMeasurements() = listOf("msv70")
            override fun measurements(sgbd: String) = if (sgbd == "msv70") listOf(ecu) else emptyList()
            override fun sgbdInfos() = listOf(SgbdInfo("msv70", "MSV70", 1))
        }
        val c = SensorCatalog(base, description = "GPS du téléphone")
        assertEquals(listOf("gps", "msv70"), c.sgbdsWithMeasurements())
        assertEquals(6, c.measurements("GPS").size)
        assertEquals(listOf(ecu), c.measurements("msv70"))
        assertEquals(SgbdInfo("gps", "GPS du téléphone", 6), c.sgbdInfos().first())
    }

    @Test
    fun `gps points go to Telegraf with tag ecu=gps`() {
        val m = PhoneSensors.measurement("gps/speed")!!
        val line = LineEncoder("E90").encode(m, Sample(87.5, null, 1_762_633_500_000))!!
        assertTrue(line.contains(",ecu=gps,"), line)
        assertTrue(line.contains(",id=speed,"), line)
        assertTrue(line.endsWith(" 1762633500000000000"), "UTC epoch nanoseconds: $line")
    }

    /** Source whose samples are ECU values and GPS values, as LiveDataProxy merges them. */
    private class MergedSource : LiveDataSource {
        val flow = MutableStateFlow<Map<String, Sample>>(emptyMap())
        override val samples: StateFlow<Map<String, Sample>> = flow
        override val status: StateFlow<AcquisitionStatus> = MutableStateFlow(AcquisitionStatus(AcquisitionState.Running))
        override fun setMeasurements(measurements: List<Measurement>) = Unit
    }

    @Test
    fun `csv rows carry gps columns and local time with its UTC offset`() = runBlocking {
        val oil = Measurement("msv70/0x4402", "msv70", "Oil", "°C", MeasurementSource.MeasurementTable(0x4402, "X"))
        val speed = PhoneSensors.measurement("gps/speed")!!
        val src = MergedSource()
        val out = ByteArrayOutputStream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = 1_782_000_000_000L // 2026-06-21T00:00:00Z: summer time in Paris
        val rec = CsvSessionRecorder(
            source = src, columns = { listOf(oil, speed) }, scope = scope, dispatcher = Dispatchers.Default,
            output = { _ -> object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            } },
            clock = { now }, zone = paris,
        )
        rec.start()
        delay(100)
        now += 100
        src.flow.value = mapOf(oil.id to Sample(90.0, null, now), speed.id to Sample(50.4, null, now))
        delay(200)
        rec.stop()
        delay(200)
        scope.cancel()
        val lines = out.toString(Charsets.UTF_8).removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
        assertEquals("time_ms;iso_time;Oil [°C];GPS speed [km/h];marker", lines[0])
        assertEquals("100;2026-06-21T02:00:00.100+02:00;90;50.4;", lines[1])
    }
}
