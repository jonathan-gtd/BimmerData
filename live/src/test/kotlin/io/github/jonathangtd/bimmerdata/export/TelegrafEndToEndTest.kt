/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.Sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * Sends a simulated 30 s session (3 measurements read 20 times per second, every reading exported: 1800 points) to a real Telegraf
 * listener, "after the trip": the session is queued first, then uploaded on an unmetered network.
 * Runs only with BIMMERDATA_TELEGRAF_URL (and BIMMERDATA_TELEGRAF_TOKEN) set; the rows are then
 * checked in TimescaleDB by bd-scripts/set_e2e_run.sh.
 */
class TelegrafEndToEndTest {

    @Test
    fun `simulated session reaches Telegraf`(@TempDir dir: File) {
        val url = System.getenv("BIMMERDATA_TELEGRAF_URL")
        assumeTrue(!url.isNullOrBlank(), "BIMMERDATA_TELEGRAF_URL not set")
        val token = System.getenv("BIMMERDATA_TELEGRAF_TOKEN").orEmpty()
        val ms = listOf(
            Measurement("msv70/0x4402", "msv70", "Oil temperature", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OELTEMPERATUR_WERT")),
            Measurement("msv70/0x4300", "msv70", "Coolant temperature", "°C", MeasurementSource.MeasurementTable(0x4300, "STAT_KUEHLMITTELTEMPERATUR_WERT")),
            Measurement("msv70/0x4807", "msv70", "Engine speed", "1/min", MeasurementSource.MeasurementTable(0x4807, "STAT_MOTORDREHZAHL_WERT")),
        ).associateBy { it.id }
        val start = Instant.parse("2026-11-08T18:00:00Z").toEpochMilli()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val samples = TestReadings()
        val config = MutableStateFlow(ExportConfig())
        val net = MutableStateFlow(NetworkKind.None)
        val exporter = TelegrafExporter(scope, samples.flow, ms::get, LineQueue(dir), config, net, clock = { start }, maxBatchWaitMs = 500)
        exporter.start()
        try {
            runBlocking {
                withTimeout(60_000) {
                    config.value = ExportConfig(true, url, if (token.isEmpty()) TelegrafAuth.None else TelegrafAuth.Token(token), "e2e-test", ExportMode.AfterTrip)
                    assertEquals(WriteResult.Ok, exporter.test(config.value))
                    delay(100)
                    // the trip: 20 reads/s (the car does about 22), 30 s; every reading is exported: 600 x 3 points
                    for (i in 0 until 30 * 20) {
                        val t = start + i * 50L
                        samples.value = mapOf(
                            "msv70/0x4402" to Sample(80.0 + i / 60.0, null, t),
                            "msv70/0x4300" to Sample(85.0, null, t),
                            "msv70/0x4807" to Sample(800.0 + (i % 20) * 10, null, t),
                        )
                        delay(2)
                    }
                    while (exporter.state.value.queuedLines < 1800) delay(10)
                    assertEquals(1800L, exporter.state.value.queuedLines)
                    assertEquals(0L, exporter.state.value.sentLines)
                    // home Wi-Fi
                    net.value = NetworkKind.Unmetered
                    while (exporter.state.value.sentLines < 1800 && exporter.state.value.lastError == null) delay(20)
                    assertEquals(1800L, exporter.state.value.sentLines, exporter.state.value.lastError ?: "")
                    println("e2e: 1800 points sent, first ${Instant.ofEpochMilli(start)}")
                }
            }
        } finally {
            exporter.stop()
            scope.cancel()
        }
    }
}
