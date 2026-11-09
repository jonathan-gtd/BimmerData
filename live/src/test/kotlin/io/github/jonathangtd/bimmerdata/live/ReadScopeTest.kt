/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The whole profile is read, the screen first, unless an ECU would need two dynamic blocks. */
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
    fun `another ECU and jobs on other pages are read too`() {
        val page = listOf(table("dme", 1))
        val rest = listOf(table("egs", 2), job("dme", "STATUS_X"))
        assertEquals(ids(page + rest), ids(ReadScope.select(profile = rest + page, visible = page)))
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
    fun `a page without ECU values still reads the ECU values of the profile`() {
        val gpsPage = listOf(gps)
        assertEquals(ids(gpsPage + table("dme", 1)), ids(ReadScope.select(profile = gpsPage + table("dme", 1), visible = gpsPage)))
    }

    @Test
    fun `nothing else in the profile - the screen as is`() {
        val page = listOf(table("dme", 1), table("dme", 2))
        assertEquals(ids(page), ids(ReadScope.select(profile = page.reversed(), visible = page)))
    }
}
