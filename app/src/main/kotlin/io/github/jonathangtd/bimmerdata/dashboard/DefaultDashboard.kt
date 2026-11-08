/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeType

/** MSV70 (E60/E90 N52 DME) MESSWERTETAB ids used by the default dashboard. */
object Msv70 {
    const val OIL_TEMP = "msv70/0x4402"
    const val COOLANT_TEMP = "msv70/0x4300"
    const val STFT_B1 = "msv70/0x5A81"
    const val STFT_B2 = "msv70/0x5A82"
    const val RPM = "msv70/0x4807"
    const val LAMBDA_B1 = "msv70/0x5A50"
    const val LAMBDA_B2 = "msv70/0x5A51"
    const val IGNITION_CYL1 = "msv70/0x5A49"
    const val LOAD = "msv70/0x5804"
    const val INTAKE_AIR = "msv70/0x4200"
    const val BATTERY = "msv70/0x460A"
    const val SPEED = "msv70/0x580D"
}

/** Localized names used when building the first-launch dashboard. */
data class DefaultLabels(
    val pageEngine: String = "Engine",
    val pageMixture: String = "Mixture",
    val pageTemperatures: String = "Temperatures",
    val oil: String = "Oil",
    val coolant: String = "Coolant",
    val fuelTrim: String = "Fuel trim",
    val fuelTrimShort: String = "Fuel trim ST",
    val rpm: String = "Engine speed",
    val lambda: String = "Lambda B1 · B2",
    val lambdaB1: String = "Lambda B1",
    val lambdaB2: String = "Lambda B2",
    val ignition: String = "Advance cyl. 1",
    val load: String = "Load",
    val intakeAir: String = "Intake air",
    val battery: String = "Battery",
    val speed: String = "Speed",
    val bank1: String = "Bank 1",
    val bank2: String = "Bank 2",
    val trimB1: String = "STFT B1",
    val trimB2: String = "STFT B2",
)

/** First-launch layout: mockup "A" on the phone, the four-card mockup in Android Auto. */
object DefaultDashboard {
    fun create(l: DefaultLabels = DefaultLabels()): Dashboard {
        fun oilDial(col: Int, row: Int, cs: Int, rs: Int) = Card(
            type = GaugeType.Dial, measurementIds = listOf(Msv70.OIL_TEMP), title = l.oil,
            min = 40.0, max = 140.0, warnBelow = 80.0, warnAbove = 130.0, col = col, row = row, colSpan = cs, rowSpan = rs,
        )
        fun coolantDial(col: Int, row: Int, cs: Int, rs: Int) = Card(
            type = GaugeType.Dial, measurementIds = listOf(Msv70.COOLANT_TEMP), title = l.coolant,
            min = 40.0, max = 120.0, warnBelow = 75.0, warnAbove = 110.0, col = col, row = row, colSpan = cs, rowSpan = rs,
        )
        fun trims(title: String, col: Int, row: Int, cs: Int, rs: Int) = Card(
            type = GaugeType.BipolarBar, measurementIds = listOf(Msv70.STFT_B1, Msv70.STFT_B2), title = title,
            channelLabels = listOf(l.bank1, l.bank2), min = -25.0, max = 25.0, warnBelow = -10.0, warnAbove = 10.0,
            decimals = 1, col = col, row = row, colSpan = cs, rowSpan = rs,
        )
        fun lambda(col: Int, row: Int, cs: Int, rs: Int) = Card(
            type = GaugeType.MultiValue, measurementIds = listOf(Msv70.LAMBDA_B1, Msv70.LAMBDA_B2), title = l.lambda,
            channelLabels = listOf("B1", "B2"), min = 0.8, max = 1.2, warnBelow = 0.9, warnAbove = 1.1, decimals = 2,
            col = col, row = row, colSpan = cs, rowSpan = rs,
        )

        val phoneEngine = Page(
            name = l.pageEngine, columns = 2, rows = 6,
            cards = listOf(
                oilDial(0, 0, 1, 2),
                coolantDial(1, 0, 1, 2),
                trims(l.fuelTrim, 0, 2, 2, 2),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.RPM), title = l.rpm, min = 0.0, max = 7000.0, warnAbove = 6500.0, decimals = 0, col = 0, row = 4),
                lambda(1, 4, 1, 1),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.IGNITION_CYL1), title = l.ignition, min = -10.0, max = 50.0, decimals = 1, col = 0, row = 5),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.LOAD), title = l.load, min = 0.0, max = 100.0, decimals = 0, col = 1, row = 5),
            ),
        )
        val phoneMixture = Page(
            name = l.pageMixture, columns = 2, rows = 6,
            cards = listOf(
                trims(l.fuelTrim, 0, 0, 2, 2),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.STFT_B1), title = l.trimB1, min = -25.0, max = 25.0, warnBelow = -10.0, warnAbove = 10.0, decimals = 1, col = 0, row = 2),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.STFT_B2), title = l.trimB2, min = -25.0, max = 25.0, warnBelow = -10.0, warnAbove = 10.0, decimals = 1, col = 1, row = 2),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.LAMBDA_B1), title = l.lambdaB1, min = 0.8, max = 1.2, decimals = 2, col = 0, row = 3),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.LAMBDA_B2), title = l.lambdaB2, min = 0.8, max = 1.2, decimals = 2, col = 1, row = 3),
                Card(type = GaugeType.BigNumber, measurementIds = listOf(Msv70.LOAD), title = l.load, min = 0.0, max = 100.0, decimals = 0, col = 0, row = 4, rowSpan = 2),
                Card(type = GaugeType.BigNumber, measurementIds = listOf(Msv70.INTAKE_AIR), title = l.intakeAir, min = -20.0, max = 80.0, warnAbove = 60.0, decimals = 0, col = 1, row = 4, rowSpan = 2),
            ),
        )
        val phoneTemps = Page(
            name = l.pageTemperatures, columns = 2, rows = 6,
            cards = listOf(
                Card(type = GaugeType.BigNumber, measurementIds = listOf(Msv70.OIL_TEMP), title = l.oil, min = 40.0, max = 140.0, warnBelow = 80.0, warnAbove = 130.0, decimals = 0, col = 0, row = 0, rowSpan = 2),
                Card(type = GaugeType.BigNumber, measurementIds = listOf(Msv70.COOLANT_TEMP), title = l.coolant, min = 40.0, max = 120.0, warnBelow = 75.0, warnAbove = 110.0, decimals = 0, col = 1, row = 0, rowSpan = 2),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.OIL_TEMP), title = l.oil, min = 40.0, max = 140.0, warnBelow = 80.0, warnAbove = 130.0, decimals = 0, col = 0, row = 2, colSpan = 2, rowSpan = 2),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.INTAKE_AIR), title = l.intakeAir, min = -20.0, max = 80.0, decimals = 0, col = 0, row = 4, colSpan = 2),
                Card(type = GaugeType.Dial, measurementIds = listOf(Msv70.BATTERY), title = l.battery, min = 10.0, max = 16.0, warnBelow = 12.0, warnAbove = 15.0, decimals = 1, col = 0, row = 5),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.SPEED), title = l.speed, min = 0.0, max = 260.0, decimals = 0, col = 1, row = 5),
            ),
        )

        val carEngine = Page(
            name = l.pageEngine, columns = 4, rows = 1,
            cards = listOf(
                oilDial(0, 0, 1, 1),
                coolantDial(1, 0, 1, 1),
                trims(l.fuelTrimShort, 2, 0, 1, 1).copy(channelLabels = listOf("B1", "B2")),
                Card(type = GaugeType.Dial, measurementIds = listOf(Msv70.RPM), title = l.rpm, min = 0.0, max = 7000.0, warnAbove = 6500.0, decimals = 0, col = 3, row = 0),
            ),
        )
        val carMixture = Page(
            name = l.pageMixture, columns = 3, rows = 2,
            cards = listOf(
                trims(l.fuelTrimShort, 0, 0, 2, 2).copy(channelLabels = listOf("B1", "B2")),
                lambda(2, 0, 1, 1),
                Card(type = GaugeType.Trend, measurementIds = listOf(Msv70.LOAD), title = l.load, min = 0.0, max = 100.0, decimals = 0, col = 2, row = 1),
            ),
        )
        val carTemps = Page(
            name = l.pageTemperatures, columns = 3, rows = 1,
            cards = listOf(
                Card(type = GaugeType.BigNumber, measurementIds = listOf(Msv70.OIL_TEMP), title = l.oil, min = 40.0, max = 140.0, warnBelow = 80.0, warnAbove = 130.0, decimals = 0, col = 0),
                Card(type = GaugeType.BigNumber, measurementIds = listOf(Msv70.COOLANT_TEMP), title = l.coolant, min = 40.0, max = 120.0, warnBelow = 75.0, warnAbove = 110.0, decimals = 0, col = 1),
                Card(type = GaugeType.BigNumber, measurementIds = listOf(Msv70.INTAKE_AIR), title = l.intakeAir, min = -20.0, max = 80.0, decimals = 0, col = 2),
            ),
        )
        return Dashboard(
            phonePages = listOf(phoneEngine, phoneMixture, phoneTemps),
            carPages = listOf(carEngine, carMixture, carTemps),
        )
    }
}
