/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementCatalog
import io.github.jonathangtd.bimmerdata.live.SgbdInfo

/**
 * [MeasurementCatalog] whose labels, descriptions and ECU texts are translated on the way out.
 * The wrapped catalog (and its disk cache) keeps the German texts; ids, units and sources are
 * unchanged, so layouts and the acquisition engine never see a translated value.
 */
class TranslatingCatalog(private val base: MeasurementCatalog, private val translator: Translator = Translations.shared) : MeasurementCatalog {
    override fun sgbdsWithMeasurements(): List<String> = base.sgbdsWithMeasurements()

    override fun measurements(sgbd: String): List<Measurement> {
        val list = base.measurements(sgbd)
        if (!translator.enabled) return list
        return list.map(translator::measurement)
    }

    override fun sgbdInfos(): List<SgbdInfo> {
        val list = base.sgbdInfos()
        if (!translator.enabled) return list
        return list.map(translator::sgbdInfo)
    }
}
