/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import io.github.jonathangtd.bimmerdata.live.CsvSessionRecorder
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.Sample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId

/** The active profile in exported points and in CSV file names. */
class ProfileTagsTest {
    private val oil = Measurement("msv70/0x4402", "msv70", "Oil", "°C", MeasurementSource.JobResult("", "", ""))

    @Test
    fun `points carry the profile tag, left out when there is no profile`() {
        val s = Sample(87.5, null, 1_000)
        assertEquals(
            "bimmerdata,car=E90\\ 330i,profile=E90\\ 330i\\ ·\\ Lambda,ecu=msv70,id=0x4402,label=Oil,unit=°C value=87.5 1000000000",
            LineEncoder("E90 330i", profile = "E90 330i · Lambda").encode(oil, s),
        )
        assertEquals("bimmerdata,car=E90\\ 330i,ecu=msv70,id=0x4402,label=Oil,unit=°C value=87.5 1000000000", LineEncoder("E90 330i").encode(oil, s))
    }

    @Test
    fun `csv file names carry the profile`() {
        val utc = ZoneId.of("UTC")
        assertEquals("bimmerdata_E90-330i-Lambda_19700101_000000.csv", CsvSessionRecorder.fileName(0, utc, profile = "E90 330i · Lambda"))
        assertEquals("trip_Mini-R50-Kuhlung_19700101_000000.csv", CsvSessionRecorder.fileName(0, utc, "trip", "Mini R50 / Kühlung"))
        assertEquals("bimmerdata_19700101_000000.csv", CsvSessionRecorder.fileName(0, utc, profile = null))
        assertEquals("bimmerdata_19700101_000000.csv", CsvSessionRecorder.fileName(0, utc, profile = " ·· "))
    }
}
