/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Read-only job rules, description parsing and text heuristics, without ECU files. */
class ReadOnlyJobsTest {

    @Test
    fun `read jobs are allowed`() {
        for (job in listOf(
            "STATUS_GETRIEBETEMPERATUR", "STATUS_ANALOG_INPUTS", "STATUS_MESSWERTBLOCK_LESEN", "STATUS_LESEN", "IDENT",
            "IDENT_IBS", "SERIENNUMMER_LESEN", "STATUS_AIF_TANKDATEN_LESEN", "READ_VARIANTE", "STATUS_KLIMA_EIN", "status_lwr_position",
        )) assertTrue(ReadOnlyJobs.isReadOnlyName(job), job)
    }

    @Test
    fun `writing, acting and session jobs are refused`() {
        for (job in listOf(
            "STEUERN_LAMPEN_DIGITAL", "STEUERN_BETR_H_FRMFA_LOESCHEN", "FS_LOESCHEN", "FS_LESEN", "IS_LESEN", "HS_LESEN",
            "PRUEFSTEMPEL_SCHREIBEN", "AIF_SCHREIBEN", "FLASH_PROGRAMMIER_STATUS_LESEN", "FLASH_ZEITEN_LESEN", "START_SYSTEMCHECK_LLR",
            "STOP_SYSTEMCHECK_LLR", "STATUS_SYSTEMCHECK_LLR_START", "CODIERUNG_LESEN", "C_FG_LESEN", "C_FG_AUFTRAG",
            "STEUERGERAETE_RESET", "SLEEP_MODE", "ENERGIESPARMODE", "STATUS_ENERGIESPARMODE", "DIAGNOSE_MODE", "DIAGNOSE_ENDE",
            "DIAGNOSE_AUFRECHT", "AUTHENTISIERUNG_ZUFALLSZAHL_LESEN", "_CODIERDATEN_BLOCK_LESEN_LEAR", "PRUEFCODE_LESEN",
            "DIAGNOSEPROTOKOLL_LESEN", "INITIALISIERUNG", "INFO", "NORMALER_DATENVERKEHR", "ADAPTION_LOESCHEN",
            "STATUS_ADAPTION_SCHREIBEN", "WRITE_FVIN", "SPEICHER_SCHREIBEN", "CLEAR_DTC", "STATUS_LERNEN",
        )) assertFalse(ReadOnlyJobs.isReadOnlyName(job), job)
    }

    @Test
    fun `a STATUS job that runs a routine or an IO adjustment is refused`() {
        fun job(vararg comments: String) = JobDescription("STATUS_X", comments.toList(), emptyList(), emptyList())
        assertTrue(ReadOnlyJobs.isReadOnly(job("Auslesen", "KWP2000: \$30 InputOutputControlByLocalIdentifier", "\$01 ReportCurrentState")))
        assertTrue(ReadOnlyJobs.isReadOnly(job("KWP2000: \$21 ReadDataByLocalIdentifier")))
        assertFalse(ReadOnlyJobs.isReadOnly(job("KWP2000: \$31 StartRoutineByLocalIdentifier")))
        assertFalse(ReadOnlyJobs.isReadOnly(job("KWP2000: \$30 InputOutputControlByLocalIdentifier", "\$07 ShortTermAdjustment")))
        assertFalse(ReadOnlyJobs.isReadOnly(job("UDS: \$31 RoutineControl")))
    }

    private val sample = """
        ECU:GS19.11
        ORIGIN:BMW
        ECUCOMMENT:SGBD fuer GS19.11
        JOBNAME:INFO
        JOBCOMMENT:Information SGBD
        RESULT:ECU
        RESULTTYPE:string
        JOBNAME:STATUS_GETRIEBETEMPERATUR
        JOBCOMMENT:Auslesen der Getriebetemperatur
        JOBCOMMENT:KWP2000: ${'$'}21 ReadDataByLocalIdentifier
        RESULT:JOB_STATUS
        RESULTTYPE:string
        RESULTCOMMENT:OKAY, wenn fehlerfrei
        RESULT:STAT_GETRIEBETEMPERATUR_WERT
        RESULTTYPE:real
        RESULTCOMMENT:Getriebeoeltemperatur
        RESULTCOMMENT:Bereich: -40 bis 215
        RESULT:STAT_GETRIEBETEMPERATUR_EINH
        RESULTTYPE:string
        RESULTCOMMENT:Grad C
        RESULT:_TEL_ANTWORT
        RESULTTYPE:binary
        JOBNAME:STATUS_PORT
        ARG:PORT
        ARGTYPE:int
        ARGCOMMENT:Portnummer
        RESULT:STAT_PORT_WERT
        RESULTTYPE:int
        JOBNAME:STATUS_SEKTOR
        ARG:SEKTOR
        ARGTYPE:int
        ARGCOMMENT:Bereich: 0-9, keine Eingabe:alle Sektoren werden gelesen
        RESULT:STAT_SPANNUNG_WERT
        RESULTTYPE:real
        RESULTCOMMENT:Spannung Klemme 30 in [V]
        JOBNAME:STEUERN_PUMPE
        RESULT:STAT_PUMPE_WERT
        RESULTTYPE:int
        JOBNAME:NOT_IN_JOB_TABLE
    """.trimIndent()

    @Test
    fun `description block - jobs, items and job measurements`() {
        val bytes = sample.toByteArray(Charsets.ISO_8859_1)
        val desc = SgbdDescription.parse(bytes, setOf("INFO", "STATUS_GETRIEBETEMPERATUR", "STATUS_PORT", "STATUS_SEKTOR", "STEUERN_PUMPE"))
        assertEquals("GS19.11", desc.ecu)
        assertEquals("GS19.11 · SGBD fuer GS19.11", desc.summary)
        assertEquals(listOf("INFO", "STATUS_GETRIEBETEMPERATUR", "STATUS_PORT", "STATUS_SEKTOR", "STEUERN_PUMPE"), desc.jobs.map { it.name })
        val temp = desc.jobs[1]
        assertEquals(listOf("JOB_STATUS", "STAT_GETRIEBETEMPERATUR_WERT", "STAT_GETRIEBETEMPERATUR_EINH", "_TEL_ANTWORT"), temp.results.map { it.name })
        assertEquals(listOf("Getriebeoeltemperatur", "Bereich: -40 bis 215"), temp.results[1].comments)

        val ms = GenericMeasurements.fromJobs("GS19", desc)
        assertEquals(listOf("gs19/STATUS_GETRIEBETEMPERATUR/STAT_GETRIEBETEMPERATUR_WERT", "gs19/STATUS_SEKTOR/STAT_SPANNUNG_WERT"), ms.map { it.id })
        assertEquals("°C", ms[0].unit)
        assertEquals(-40.0, ms[0].min)
        assertEquals(215.0, ms[0].max)
        assertEquals("Auslesen der Getriebetemperatur", ms[0].description)
        assertEquals("V", ms[1].unit)
        assertEquals("Spannung Klemme 30", ms[1].label)
        assertEquals(0.0, ms[1].min)
        assertEquals(16.0, ms[1].max)
        assertEquals(2, GenericMeasurements.countJobResults(desc))
    }

    @Test
    fun `labels and units`() {
        assertEquals("Spannung Klemme 30A", MeasurementText.humanize("STAT_SPANNUNG_KLEMME_30A_WERT"))
        assertEquals("Aussentemperatur (gefiltert)", MeasurementText.label("X", listOf("Aussentemperatur in \"Grad Celsius\" (gefiltert)")))
        assertEquals("Fahrgeschwindigkeit", MeasurementText.label("X", listOf("Fahrgeschwindigkeit in km/h, Aufloesung: 1 km")))
        assertEquals("Motortyp", MeasurementText.label("STAT_MOTORTYP_NR", listOf("0 = Benzinmotor, 1 = Dieselmotor")))
        assertEquals("°C", MeasurementText.knownUnit("Grad C"))
        assertEquals("rpm", MeasurementText.knownUnit("U/min"))
        assertEquals("l", MeasurementText.unitFromComments(listOf("Tankinhalt"), listOf("Einheit Tankinhalt Hebelgeber1: \"Liter\"")))
        assertEquals("km", MeasurementText.unitFromComments(listOf("Gesamtwegstreckenzaehler"), listOf("Einheit des GWSZ [km]")))
        assertEquals("V", MeasurementText.unitFromComments(listOf("Spannung", "Bereich: 0 V bis 5 V"), emptyList()))
        assertEquals("km/h", MeasurementText.unitFromComments(listOf("Fahrgeschwindigkeit in km/h, Aufloesung: 1 km"), emptyList()))
        assertEquals("", MeasurementText.unitFromComments(listOf("Zaehler"), emptyList()))
        assertEquals(1.0 to 12.0, MeasurementText.rangeFromComments(listOf("Monatsangabe [1-12]")))
        assertEquals(0.0 to 255.0, MeasurementText.rangeFromComments(listOf("Bereich: 0x00 bis 0xFF")))
        assertEquals("", MeasurementText.tableUnit("0-n"))
        assertEquals("°C", MeasurementText.tableUnit("degC"))
    }
}
