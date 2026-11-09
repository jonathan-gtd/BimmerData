/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import io.github.jonathangtd.bimmerdata.live.JobDescription
import io.github.jonathangtd.bimmerdata.live.LiveTestData
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.live.SgbdDescription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** ECU functions offered in the detail (reads, tests, never coding), fault presence chips, standstill rule. */
class EcuFunctionsTest {
    private fun job(name: String, vararg comments: String) = JobDescription(name, comments.toList(), emptyList(), emptyList())

    @Test
    fun `reads and tests are offered, writing and coding never`() {
        assertEquals(EcuFunctionKind.Read, EcuFunctions.kindOf(job("STATUS_MESSWERTBLOCK_LESEN", "KWP2000: \$21 ReadDataByLocalIdentifier")))
        assertEquals(EcuFunctionKind.Read, EcuFunctions.kindOf(job("IDENT")))
        assertEquals(EcuFunctionKind.Test, EcuFunctions.kindOf(job("STEUERN_EKP", "KWP2000: \$30 InputOutputControlByLocalIdentifier")))
        assertEquals(EcuFunctionKind.Test, EcuFunctions.kindOf(job("SELF_TEST")))
        assertEquals(EcuFunctionKind.Test, EcuFunctions.kindOf(job("START_SYSTEMCHECK_DMTL", "KWP2000: \$31 StartRoutineByLocalIdentifier")))
        for (name in listOf(
            "C_C_SCHREIBEN", "C_FG_LESEN", "FS_LOESCHEN", "FS_LESEN", "IS_LESEN", "SPEICHER_LESEN", "FLASH_SCHREIBEN",
            "STEUERN_ISN_SCHREIBEN", "DIAGNOSE_AUFRECHT", "SLEEP_MODE", "NORMALER_DATENVERKEHR", "STEUERN_RESET",
            "STEUERN_EINLERNEN_DK", "PRUEFSTEMPEL_SCHREIBEN", "_SECRET", "INITIALISIERUNG", "STEUERN_SG_RESET",
        )) assertNull(EcuFunctions.kindOf(job(name)), name)
        // a test that needs another session or writes data is not offered
        assertNull(EcuFunctions.kindOf(job("STEUERN_X", "KWP2000: \$10 StartDiagnosticSession")))
        assertNull(EcuFunctions.kindOf(job("STEUERN_Y", "UDS: \$2E WriteDataByIdentifier")))
    }

    @Test
    fun `fault presence from the status text`() {
        assertEquals(FaultPresence.Present, FaultPresence.of("Fehler momentan vorhanden und bereits gespeichert"))
        assertEquals(FaultPresence.Present, FaultPresence.of("Fehler momentan vorhanden, OBD-entprellt"))
        assertEquals(FaultPresence.Stored, FaultPresence.of("Fehler momentan nicht vorhanden, aber bereits gespeichert"))
        assertEquals(FaultPresence.NeverOccurred, FaultPresence.of("Fehler bisher nicht aufgetreten"))
        assertEquals(FaultPresence.Stored, FaultPresence.of("Fault not currently present"))
        assertEquals(FaultPresence.Unknown, FaultPresence.of(null))
        val faults = listOf(
            Fault(1, "a", presence = "Fehler bisher nicht aufgetreten"),
            Fault(2, "b", presence = "Fehler momentan vorhanden und bereits gespeichert"),
        )
        assertEquals(FaultPresence.Present, FaultPresence.worst(faults))
    }

    @Test
    fun `tests only at a standstill`() {
        val now = 100_000L
        assertNull(Standstill.blockingSpeed(emptyList(), now))
        assertNull(Standstill.blockingSpeed(listOf(Sample(0.0, null, now - 500), Sample(1.5, null, now)), now))
        assertEquals(42.0, Standstill.blockingSpeed(listOf(Sample(0.0, null, now), Sample(42.0, null, now - 2_000)), now))
        // an old reading does not count
        assertNull(Standstill.blockingSpeed(listOf(Sample(80.0, null, now - 60_000)), now))
    }

    @Test
    fun `real ECU files offer reads and tests and no coding`() {
        val dir = LiveTestData.ecuDir()
        for (name in listOf("msv70.prg", "frm_87.prg", "fzd_87.prg")) {
            val file = File(dir, name).takeIf { it.isFile } ?: dir.listFiles()!!.first { it.name.equals(name, true) }
            val functions = EcuFunctions.of(SgbdDescription.read(file)!!)
            val reads = functions.filter { it.kind == EcuFunctionKind.Read }
            val tests = functions.filter { it.kind == EcuFunctionKind.Test }
            println("$name: ${reads.size} reads, ${tests.size} tests: ${tests.joinToString { it.job }}")
            assertTrue(reads.isNotEmpty(), name)
            assertTrue(functions.none { f -> f.job.uppercase().let { it.startsWith("C_") || "SCHREIBEN" in it || "FLASH" in it || it.startsWith("FS_") } }, name)
        }
    }
}
