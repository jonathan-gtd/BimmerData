/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

import io.github.jonathangtd.bimmerdata.live.EdiabasMeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.LiveTestData
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.SgbdInfo
import io.github.jonathangtd.bimmerdata.live.vehicle.Fault
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultDetail
import io.github.jonathangtd.bimmerdata.live.vehicle.FaultOccurrence
import io.github.jonathangtd.bimmerdata.live.vehicle.NamedValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TranslationsTest {

    // ---------------------------------------------------------------- CSV

    @Test
    fun `parses quotes, semicolons, BOM, CRLF and multi-line fields`() {
        val csv = "﻿type;cle;de;traduction;statut\r\n" +
            "mesure;0x4402;Öltemperatur;Température d'huile;relu\r\n" +
            "defaut;2f08;\"Ansauglufttemperatursensor; Signal\";\"Sonde \"\"IAT\"\"; signal\";auto\r\n" +
            "\r\n" +
            "job;status_oel;Status Öl;\"État\nde l'huile\";\r\n" +
            "bogus;x;y;z;auto\r\n"
        val p = TranslationCsv.parse(csv)
        assertEquals(3, p.entries.size)
        assertEquals(listOf("line 7: unknown type \"bogus\""), p.errors)
        val (m, d, j) = p.entries
        assertEquals(TranslationEntry(TranslationType.Measurement, "0x4402", "Öltemperatur", "Température d'huile", TranslationStatus.Reviewed), m)
        assertEquals("0x2F08", d.key)
        assertEquals("Ansauglufttemperatursensor; Signal", d.de)
        assertEquals("Sonde \"IAT\"; signal", d.text)
        assertEquals(TranslationStatus.Auto, d.status)
        assertEquals("STATUS_OEL", j.key)
        assertEquals("État\nde l'huile", j.text)
        assertEquals(TranslationStatus.Auto, j.status)
    }

    @Test
    fun `header with English names in another order, and no header`() {
        val p = TranslationCsv.parse("key;type;translation;de\n0x4402;measurement;Oil temperature;Öltemperatur\n")
        assertEquals(TranslationEntry(TranslationType.Measurement, "0x4402", "Öltemperatur", "Oil temperature"), p.entries.single())
        val q = TranslationCsv.parse("symptome;0x21;Signal unplausibel;Signal implausible;relu\n")
        assertEquals("0x21", q.entries.single().key)
    }

    @Test
    fun `write then read gives the same entries`() {
        val entries = listOf(
            TranslationEntry(TranslationType.Ecu, "", "Motorelektronik MS 70", "Électronique moteur", TranslationStatus.Reviewed),
            TranslationEntry(TranslationType.Result, "STATUS_OEL/STAT_PHY_NIVOEL_WERT", "Niveau; \"phys\"", "", TranslationStatus.Auto),
            TranslationEntry(TranslationType.Table, "LAMBDASTATUS/0x02", " Regelung EIN", "Régulation active"),
        )
        val text = TranslationCsv.write(entries)
        assertTrue(text.startsWith(TranslationCsv.Header + "\n"))
        val back = TranslationCsv.parse(text).entries
        assertEquals(entries.map { it.copy(de = it.de.trim()) }, back)
        // an empty translation has no status
        assertTrue(text.lines()[2].endsWith(";"))
    }

    // ---------------------------------------------------------------- keys

    @Test
    fun `keys are normalized`() {
        assertEquals("0x4402", TranslationKeys.normalize(TranslationType.Measurement, " 0X4402 "))
        assertEquals("0x2F08", TranslationKeys.normalize(TranslationType.Fault, "12040"))
        assertEquals("0x0A", TranslationKeys.normalize(TranslationType.Symptom, "0x000a"))
        assertEquals("STATUS_OEL/STAT_X_WERT", TranslationKeys.normalize(TranslationType.Result, "status_oel/stat_x_wert"))
        assertEquals("*/STAT_X_WERT", TranslationKeys.normalize(TranslationType.Result, "stat_x_wert"))
        assertEquals("LAMBDASTATUS/0x02", TranslationKeys.normalize(TranslationType.Table, "lambdastatus/2"))
        assertEquals("FARTTEXTEERWEITERT/XXXXXXX0", TranslationKeys.normalize(TranslationType.Table, "FARTTEXTEERWEITERT/xxxxxxx0"))
        assertEquals("", TranslationKeys.normalize(TranslationType.Ecu, "whatever"))
    }

    @Test
    fun `German guard ignores case, spacing and umlaut spelling`() {
        assertTrue(GermanText.same("Wasserpumpe Leistung über BSD", "wasserpumpe  leistung ueber BSD."))
        assertTrue(GermanText.same("Fußgänger", "FUSSGAENGER"))
        assertFalse(GermanText.same("Öltemperatur", "Kühlmitteltemperatur"))
    }

    // ---------------------------------------------------------------- lookups

    private fun file(vararg lines: String) = EcuTranslations("msv70", "fr", TranslationCsv.parse(lines.joinToString("\n")).entries)

    private val msv70 = file(
        "mesure;0x4402;Öltemperatur;Température d'huile;relu",
        "mesure;0x4300;Kühlmitteltemperatur;Température liquide;auto",
        "resultat;*/STAT_UBATT_WERT;Batteriespannung;Tension batterie;auto",
        "resultat;STATUS_OEL/STAT_OEL_TEXT;Ölzustand;État de l'huile;auto",
        "job;STATUS_OEL;Status Öl lesen;Lire l'état de l'huile;auto",
        "defaut;0x2F08;Ansauglufttemperatursensor, Signal;Sonde de température d'air, signal;relu",
        "symptome;0x0A;Signal unplausibel;Signal non plausible;auto",
        "table;FARTSTATUSTEXTE/0x01;Fehler momentan vorhanden;Défaut actuellement présent;auto",
        "table;FUMWELTTEXTE/0x4200;Ansauglufttemperatur 1;Température d'air d'admission 1;auto",
        "ecu;;MSV70 fuer N52;MSV70 pour N52;auto",
    )

    private fun translator(vararg files: EcuTranslations, language: String = "fr") = Translator().apply { reset(language, files.toList()) }

    @Test
    fun `lookup honours the de guard and the job wildcard`() {
        assertEquals(Lookup("Température d'huile", false, TranslationStatus.Reviewed), msv70.lookup(TranslationType.Measurement, "0x4402", "Öltemperatur"))
        assertTrue(msv70.lookup(TranslationType.Measurement, "0x4402", "Öldruck")!!.stale)
        assertFalse(msv70.lookup(TranslationType.Measurement, "0x4402", null)!!.stale)
        assertEquals("Tension batterie", msv70.lookup(TranslationType.Result, "STATUS_UBATT/STAT_UBATT_WERT", "Batteriespannung")?.text)
        assertNull(msv70.lookup(TranslationType.Measurement, "0x9999", "x"))
        assertEquals("Signal non plausible", msv70.byOriginal("signal  UNPLAUSIBEL"))
    }

    @Test
    fun `translator keeps the original when stale, missing or language de`() {
        val t = translator(msv70)
        assertEquals("Température d'huile", t.text(TranslationType.Measurement, "MSV70.PRG", "0x4402", "Öltemperatur"))
        assertEquals("Öldruck", t.text(TranslationType.Measurement, "msv70", "0x4402", "Öldruck"))
        assertEquals("Öltemperatur", t.text(TranslationType.Measurement, "kombi87", "0x4402", "Öltemperatur"))
        val de = translator(msv70.let { EcuTranslations("msv70", "de", it.entries) }, language = "de")
        assertFalse(de.enabled)
        assertEquals("Öltemperatur", de.text(TranslationType.Measurement, "msv70", "0x4402", "Öltemperatur"))
        // a file of another language is ignored
        assertEquals("Öltemperatur", translator(EcuTranslations("msv70", "en", msv70.entries)).text(TranslationType.Measurement, "msv70", "0x4402", "Öltemperatur"))
    }

    @Test
    fun `missing files are reported once per SGBD, ECU lists do not fetch`() {
        val t = translator(msv70)
        val asked = ArrayList<String>()
        t.onMissing = { asked += it }
        t.text(TranslationType.Measurement, "kombi87", "0x1", "x")
        t.text(TranslationType.Measurement, "KOMBI87", "0x2", "y")
        t.sgbdInfo(SgbdInfo("e89x", "Fahrzeug E89X"))
        t.text(TranslationType.Measurement, "msv70", "0x4402", "Öltemperatur")
        assertEquals(listOf("kombi87"), asked)
    }

    @Test
    fun `measurements, ECU infos and faults are translated for display`() {
        val t = translator(msv70)
        val oil = EdiabasMeasurementCatalog.toMeasurement("msv70", io.github.jonathangtd.bimmerdata.live.MesswertRow(
            "ITOEL", 0x4402, "STAT_OELTEMPERATUR_WERT", "Öltemperatur", "°C", "", "", io.github.jonathangtd.bimmerdata.live.DataType.SInt16,
            false, null, 0.1, 1.0, 0.0,
        ))
        assertEquals("Température d'huile", t.measurement(oil).label)
        assertEquals(oil.id, t.measurement(oil).id)
        val job = Measurement(
            "msv70/STATUS_OEL/STAT_OEL_TEXT", "msv70", "Ölzustand", "", MeasurementSource.JobResult("STATUS_OEL", "", "STAT_OEL_TEXT"),
            description = "Status Öl lesen",
        )
        assertEquals("État de l'huile", t.measurement(job).label)
        assertEquals("Lire l'état de l'huile", t.measurement(job).description)
        assertEquals("MSV70 pour N52", t.sgbdInfo(SgbdInfo("msv70", "MSV70 fuer N52")).description)

        val fault = Fault(0x2F08, "Ansauglufttemperatursensor, Signal", symptom = "Signal unplausibel", presence = "Fehler momentan vorhanden")
        val f = t.fault("msv70", fault)
        assertEquals("Sonde de température d'air, signal", f.text)
        assertEquals("Signal non plausible", f.symptom)
        assertEquals("Défaut actuellement présent", f.presence)
        val d = t.faultDetail("msv70", FaultDetail(fault, occurrences = listOf(FaultOccurrence(1000, listOf(NamedValue("Ansauglufttemperatur 1", "-48", "°C"))))))
        assertEquals("Température d'air d'admission 1", d.occurrences.single().values.single().name)
        // same code, other text (other SGBD version): kept in German
        assertEquals("Drosselklappe", t.fault("msv70", Fault(0x2F08, "Drosselklappe")).text)
    }

    @Test
    fun `translating catalog only changes display texts`() {
        val m = Measurement("msv70/0x4402", "msv70", "Öltemperatur", "°C", MeasurementSource.MeasurementTable(0x4402, "STAT_OELTEMPERATUR_WERT"))
        val base = object : MeasurementCatalog {
            override fun sgbdsWithMeasurements() = listOf("msv70")
            override fun measurements(sgbd: String) = listOf(m)
            override fun sgbdInfos() = listOf(SgbdInfo("msv70", "MSV70 fuer N52", 1))
        }
        val t = translator(msv70)
        val c = TranslatingCatalog(base, t)
        assertEquals(m.copy(label = "Température d'huile"), c.measurements("msv70").single())
        assertEquals("MSV70 pour N52", c.sgbdInfos().single().description)
        t.reset("de", emptyList())
        assertEquals(m, c.measurements("msv70").single())
    }

    // ---------------------------------------------------------------- template

    @Test
    fun `merge keeps translations and refreshes de`() {
        val template = listOf(
            TranslationEntry(TranslationType.Measurement, "0x4402", "Öltemperatur neu", ""),
            TranslationEntry(TranslationType.Measurement, "0x4300", "Kühlmitteltemperatur", ""),
        )
        val merged = TranslationTemplate.merge(template, msv70.entries)
        assertEquals("Öltemperatur neu", merged[0].de)
        assertEquals("Température d'huile", merged[0].text)
        assertEquals(TranslationStatus.Reviewed, merged[0].status)
        assertEquals("Température liquide", merged[1].text)
        assertTrue(merged.drop(2).any { it.type == TranslationType.Fault }) // unknown keys kept at the end
        val cov = TranslationTemplate.coverage(template, msv70)
        assertEquals(2, cov.total(TranslationType.Measurement))
        assertEquals(1, cov.translated(TranslationType.Measurement))
        assertEquals(1, cov.stale)
    }

    @Test
    fun `template of the real MSV70 PRG`() {
        val file = File(LiveTestData.ecuDir(), "MSV70.PRG").let { if (it.isFile) it else LiveTestData.ecuDir().listFiles()!!.first { f -> f.name.equals("msv70.prg", true) } }
        val entries = TranslationTemplate.generate("MSV70", file)
        val byType = entries.groupingBy { it.type }.eachCount()
        println("MSV70 template: ${entries.size} entries $byType")
        assertEquals(465, byType[TranslationType.Measurement])
        assertEquals(321, byType[TranslationType.Fault]) // 322 rows, one code listed twice
        assertEquals(1, byType[TranslationType.Ecu])
        assertTrue((byType[TranslationType.Symptom] ?: 0) > 100)
        assertTrue((byType[TranslationType.Job] ?: 0) > 200)
        fun e(type: TranslationType, key: String) = entries.first { it.type == type && it.key == key }
        assertEquals("Ansauglufttemperatur 1", e(TranslationType.Measurement, "0x4200").de)
        assertEquals("Verbrennungsaussetzer, Zylinder 1", e(TranslationType.Fault, "0x29CD").de)
        assertEquals("Umgebungsdruck", e(TranslationType.Table, "FUMWELTTEXTE/0x4201").de)
        assertEquals("2 Regelung EIN", e(TranslationType.Table, "LAMBDASTATUS/0x02").de)
        // identifiers are not translatable texts
        assertTrue(entries.none { it.type == TranslationType.Table && it.key.startsWith("JOBRESULT/") })
        assertTrue(entries.none { it.type == TranslationType.Table && it.de == "PASSIVE" })
        // keys are unique and survive a write / read round trip
        assertEquals(entries.size, entries.map { it.type to it.key }.toSet().size)
        assertEquals(entries, TranslationCsv.parse(TranslationCsv.write(entries)).entries)
        // the catalog labels match the template's de (the guard accepts them)
        val catalog = EdiabasMeasurementCatalog(LiveTestData.ecuDir())
        val tr = Translator().apply {
            reset("xx", listOf(EcuTranslations("msv70", "xx", entries.map { it.copy(text = "T:" + it.de) })))
        }
        val shown = catalog.measurements("msv70").map(tr::measurement)
        assertTrue(shown.all { it.label.startsWith("T:") }, shown.filterNot { it.label.startsWith("T:") }.take(5).toString())
    }
}
