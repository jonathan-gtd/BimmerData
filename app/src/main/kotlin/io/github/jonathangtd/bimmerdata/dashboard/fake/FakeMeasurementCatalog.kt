/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.fake

import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.SgbdInfo
import java.util.Locale

/** A slice of the MSV70 MESSWERTETAB (INFO labels and EINHEIT units as in the SGBD). */
class FakeMeasurementCatalog : MeasurementCatalog {
    override fun sgbdsWithMeasurements(): List<String> = listOf("msv70")

    override fun sgbdInfos(): List<SgbdInfo> = listOf(SgbdInfo("msv70", "MSV70 fuer N52 (fake data)", MSV70.size))

    override fun measurements(sgbd: String): List<Measurement> = if (sgbd.equals("msv70", ignoreCase = true)) MSV70 else emptyList()

    companion object {
        private fun m(id: Int, result: String, info: String, unit: String) = Measurement(
            id = String.format(Locale.ROOT, "msv70/0x%04X", id), sgbd = "msv70", label = info, unit = unit,
            source = MeasurementSource.MeasurementTable(id, result),
        )

        val MSV70: List<Measurement> = listOf(
            m(0x4200, "STAT_ANSAUGLUFTTEMPERATUR_WERT", "Ansauglufttemperatur 1", "°C"),
            m(0x4201, "STAT_0x4201_WERT", "Umgebungsdruck", "hPa"),
            m(0x4202, "STAT_SAUGROHRDRUCK_WERT", "Saugrohrdruck", "hPa"),
            m(0x4203, "STAT_LUFTMASSE_WERT", "Massenstrom vom HFM", "kg/h"),
            m(0x4204, "STAT_UMGEBUNGSTEMPERATUR_WERT", "Umgebungstemperatur", "°C"),
            m(0x4300, "STAT_KUEHLMITTELTEMPERATUR_WERT", "Kühlwassertemperatur", "°C"),
            m(0x4301, "STAT_0x4301_WERT", "Kuehlerauslasstemperatur", "°C"),
            m(0x4402, "STAT_OELTEMPERATUR_WERT", "Öltemperatur", "°C"),
            m(0x4506, "STAT_POSITION_NOCKENWELLE_EINLASS_WERT", "Nockenwellenposition Einlass", "°CRK"),
            m(0x4507, "STAT_POSITION_NOCKENWELLE_AUSLASS_WERT", "Nockenwellenposition Auslass", "°CRK"),
            m(0x4600, "STAT_DROSSELKLAPPENWINKEL_WERT", "aktueller Drosselklappenwinkel", "°TPS"),
            m(0x460A, "STAT_UBATT_WERT", "Batteriespannung aktuell", "V"),
            m(0x4807, "STAT_MOTORDREHZAHL_WERT", "Motor Drehzahl", "rpm"),
            m(0x480B, "STAT_FAHRERWUNSCH_PEDAL_WERT", "Pedalwert Fahrerwunsch in %", "%"),
            m(0x5804, "STAT_LASTWERT_BERECHNET_WERT", "Berechneter Lastwert", "%"),
            m(0x5807, "STAT_LAMBDA_ADAPTION_MULTIPLIKATIV_GRUPPE1_WERT", "Lambda Adaption Summe mul. & add. Gruppe 1", "%"),
            m(0x5809, "STAT_LAMBDA_ADAPTION_MULTIPLIKATIV_GRUPPE2_WERT", "Lambda Adaption Summe mul. & add. Gruppe 2", "%"),
            m(0x580A, "STAT_KRAFTSTOFFDRUCK_WERT", "Kraftstoffdruck", "kPa"),
            m(0x580D, "STAT_GESCHWINDIGKEIT_2_WERT", "Geschwindigkeit", "km/h"),
            m(0x5810, "STAT_LUFTMASSE_GRAMM_PRO_SEKUNDE_WERT", "Luftdurchsatz OBD", "g/s"),
            m(0x581A, "STAT_0x581A_WERT", "Nockenwelle Einlass", "°CRK"),
            m(0x581C, "STAT_0x581C_WERT", "Nockenwelle Auslass", "°CRK"),
            m(0x5839, "STAT_DROSSELKLAPPE_NOTLAUF_WERT", "Status Drosselklappe Notlauf", "0-n"),
            m(0x583B, "STAT_KRAFTSTOFFTANK_FUELLSTAND_WERT", "Kraftstofftank Füllstand", "l"),
            m(0x5A49, "STAT_ZUENDWINKEL_ZYL1_WERT", "Zündwinkel Zylinder 1", "°CRK"),
            m(0x5A50, "STAT_LAMBDA_BANK1_WERT", "Lambdawert vor Kat Bank 1", "-"),
            m(0x5A51, "STAT_LAMBDA_BANK2_WERT", "Lambdawert vor Kat Bank 2", "-"),
            m(0x5A81, "STAT_INTEGRATOR_BANK1_WERT", "Integrator Bank 1", "%"),
            m(0x5A82, "STAT_INTEGRATOR_BANK2_WERT", "Integrator Bank 2", "%"),
        )
    }
}
