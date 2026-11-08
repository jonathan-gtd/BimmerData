/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EdiabasMeasurementCatalogTest {

    @Test
    fun `MSV70 measurement table`() {
        val catalog = EdiabasMeasurementCatalog(LiveTestData.ecuDir())
        val list = catalog.measurements("MSV70").filter { it.source is MeasurementSource.MeasurementTable }
        assertEquals(465, list.size)
        assertEquals(list.size, list.map { it.id }.toSet().size, "ids are unique")

        val oil = list.first { it.id == "msv70/0x4402" }
        assertEquals("msv70", oil.sgbd)
        assertEquals("Öltemperatur", oil.label)
        assertEquals("°C", oil.unit)
        assertEquals(MeasurementSource.MeasurementTable(0x4402, "STAT_OELTEMPERATUR_WERT"), oil.source)
        assertEquals(-40.0, oil.min)
        assertEquals(150.0, oil.max)

        val lambda = catalog.measurement("msv70/0x5A50")!!
        assertEquals("Lambdawert vor Kat Bank 1", lambda.label)
        assertEquals("", lambda.unit, "EINHEIT '-' means no unit")
        assertEquals(0.7, lambda.min)
        assertEquals(-25.0, catalog.measurement("msv70/0x5A81")!!.min, "integrator")
        assertEquals("rpm", catalog.measurement("msv70/0x4807")!!.unit)
        assertNull(catalog.measurement("msv70/0x4505")!!.min, "degCRK: no default range")

        val tab = catalog.table("msv70")!!
        val state = tab.row(0x5A54)!!
        assertEquals("_CNV_S_7_EGCP_RANGE_711", state.conversionTable)
        assertEquals("LSH_VB_PROT", tab.stateText(state, 5))
        assertEquals("undefiniert", tab.stateText(state, 0xFF))
        assertEquals(DataType.SInt16, tab.row(0x4402)!!.dataType)
        assertEquals(0.100000001490116, tab.row(0x4402)!!.mul)
        assertEquals(-47.9999985694886, tab.row(0x4300)!!.add)
        assertEquals(DataType.UInt32, tab.row(0x4403)!!.dataType)
    }

    @Test
    fun `scan of the whole ECU folder, then from the cache`(@TempDir tmp: File) {
        val dir = LiveTestData.ecuDir()
        val cache = File(tmp, "catalog.tsv")
        var t0 = System.nanoTime()
        val first = EdiabasMeasurementCatalog(dir, cache).sgbdsWithMeasurements()
        val scanMs = (System.nanoTime() - t0) / 1e6
        t0 = System.nanoTime()
        val second = EdiabasMeasurementCatalog(dir, cache).sgbdsWithMeasurements()
        val cachedMs = (System.nanoTime() - t0) / 1e6
        val prgCount = dir.listFiles()!!.count { it.name.endsWith(".prg", true) }
        println("SGBDs with MESSWERTETAB: ${first.size} of $prgCount .prg files; scan %.0f ms, with cache %.0f ms".format(scanMs, cachedMs))
        println("first ones: ${first.take(20)}")
        assertTrue("msv70" in first)
        assertEquals(first, second)
        assertTrue(cache.isFile)
        assertNotNull(EdiabasMeasurementCatalog(dir, cache).table("msv70"))
    }
}
