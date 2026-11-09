/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The whole profile is read when it costs no extra read cycle, else only the screen. */
class ReadScopeTest {
    private fun table(sgbd: String, id: Int) =
        Measurement("$sgbd/0x%04X".format(id), sgbd, "v$id", "", MeasurementSource.MeasurementTable(id, "STAT_$id"))

    private fun job(sgbd: String, name: String) = Measurement("$sgbd/$name", sgbd, name, "", MeasurementSource.JobResult(name, "", "STAT"))
    private val gps = Measurement("gps/speed", "gps", "GPS speed", "km/h", MeasurementSource.Sensor("gps", "speed"))

    private fun ids(l: List<Measurement>) = l.map { it.id }

    @Test
    fun `other pages of the same ECU are read too, the screen first`() {
        val page1 = (0 until 6).map { table("dme", it) }
        val page2 = (6 until 16).map { table("dme", it) }
        assertEquals(ids(page2 + page1), ids(ReadScope.select(profile = page1 + page2, visible = page2)))
    }

    @Test
    fun `phone sensors are always free`() {
        val page = listOf(table("dme", 1))
        assertEquals(ids(page + gps), ids(ReadScope.select(profile = page + gps, visible = page)))
    }

    @Test
    fun `another ECU on another page would cost a telegram per cycle - screen only`() {
        val page = listOf(table("dme", 1))
        assertEquals(ids(page), ids(ReadScope.select(profile = page + table("egs", 2), visible = page)))
    }

    @Test
    fun `a job on another page would cost about 100 ms per cycle - screen only`() {
        val page = listOf(table("dme", 1))
        assertEquals(ids(page), ids(ReadScope.select(profile = page + job("dme", "STATUS_X"), visible = page)))
    }

    @Test
    fun `more values than one block for an ECU - screen only`() {
        val all = (0 until 43).map { table("dme", it) }
        val page = all.take(6)
        assertEquals(ids(page), ids(ReadScope.select(profile = all, visible = page)))
        val fits = all.take(42)
        assertEquals(42, ReadScope.select(profile = fits, visible = page).size)
    }

    @Test
    fun `a screen without ECU values does not start reading the ECU`() {
        val gpsPage = listOf(gps)
        assertEquals(ids(gpsPage), ids(ReadScope.select(profile = gpsPage + table("dme", 1), visible = gpsPage)))
    }

    @Test
    fun `nothing else in the profile - the screen as is`() {
        val page = listOf(table("dme", 1), table("dme", 2))
        assertEquals(ids(page), ids(ReadScope.select(profile = page.reversed(), visible = page)))
    }
}
