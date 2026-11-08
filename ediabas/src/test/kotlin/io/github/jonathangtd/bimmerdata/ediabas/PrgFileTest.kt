/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests against a real MSV70.PRG. ECU files are proprietary and never in the repo:
 * set BIMMERDATA_ECU_DIR to your own EDIABAS\Ecu folder, otherwise these tests are skipped.
 */
class PrgFileTest {

    private fun msv70(): PrgFile {
        val dir = System.getenv("BIMMERDATA_ECU_DIR")
        assumeTrue(dir != null, "BIMMERDATA_ECU_DIR not set")
        val file = File(dir!!).listFiles()?.firstOrNull { it.name.equals("MSV70.PRG", ignoreCase = true) }
        assumeTrue(file != null, "MSV70.PRG not found in $dir")
        return PrgFile.open(file!!)
    }

    @Test
    fun `reads header jobs and tables`() {
        val prg = msv70()
        assertTrue(prg.isObjectFile)
        assertEquals(331, prg.jobs.size)
        val names = prg.jobs.map { it.name }.toSet()
        listOf("IDENT", "FS_LESEN", "FS_LOESCHEN", "STATUS_MESSWERTBLOCK_LESEN", "STATUS_OEL")
            .forEach { assertTrue(it in names, "missing job $it") }
        assertEquals(82, prg.tables.size)
    }

    @Test
    fun `decodes measurement table`() {
        val table = msv70().table("MESSWERTETAB")
        assertNotNull(table)
        table!!
        assertEquals(
            listOf("ARG", "ID", "RESULTNAME", "INFO", "EINHEIT", "LABEL", "L/H", "DATENTYP", "NAME", "MUL", "DIV", "ADD"),
            table.header,
        )
        assertEquals(465, table.rows.size)
        val oil = table.records().first { it["ID"] == "0x4402" }
        assertEquals("STAT_OELTEMPERATUR_WERT", oil["RESULTNAME"])
        assertEquals("Öltemperatur", oil["INFO"])
        assertEquals("°C", oil["EINHEIT"])
        assertEquals("signed integer", oil["DATENTYP"])
    }

    @Test
    fun `decodes fault text table`() {
        val table = msv70().table("FORTTEXTE")
        assertNotNull(table)
        table!!
        assertEquals(listOf("ORT", "ORTTEXT"), table.header)
        assertEquals(322, table.rows.size)
    }
}
