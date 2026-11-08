/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.jonathangtd.bimmerdata.live.i18n.Translations
import io.github.jonathangtd.bimmerdata.live.i18n.Translator

/**
 * The shared [Translator], with its cache loaded ([TranslationRepository.get]). Read
 * [Translator.version] (see [translationVersion]) to recompose when files or the language change.
 */
@Composable
fun rememberTranslator(): Translator {
    val context = LocalContext.current
    remember { TranslationRepository.get(context) }
    return Translations.shared
}

/** Changes whenever translations change: use it as a `remember` key around translated values. */
@Composable
fun translationVersion(): Int {
    val v by rememberTranslator().version.collectAsState()
    return v
}

/**
 * Long name of an ECU in the vehicle tree (German, from the vehicle SGBD's tables): matched by its
 * German text in the files of the ECU's own SGBD, then of the vehicle SGBD ([seriesSgbd]).
 */
fun Translator.ecuDescription(ecu: io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu, seriesSgbd: String?): String? =
    ecu.description?.let { d -> table(ecu.sgbd)?.byOriginal(d) ?: table(seriesSgbd)?.byOriginal(d) ?: d }

/**
 * Description of every documented job of [prgName] (an SGBD file name, e.g. "msv70.prg"), by
 * upper-case job name, translated when a translation exists: what the Tool32 job picker shows
 * under each name. Empty for group files or a missing file. Reads the file: call off the main thread.
 */
fun TranslationRepository.jobTexts(prgName: String): Map<String, String> {
    val sgbd = io.github.jonathangtd.bimmerdata.live.EdiabasMeasurementCatalog.normalize(prgName)
    val file = sgbdFile(sgbd) ?: return emptyMap()
    val desc = io.github.jonathangtd.bimmerdata.live.SgbdDescription.read(file) ?: return emptyMap()
    val t = io.github.jonathangtd.bimmerdata.live.i18n.Translations.shared
    return desc.jobs.mapNotNull { j ->
        val de = io.github.jonathangtd.bimmerdata.live.i18n.TranslationTemplate.jobText(j.comments)
        if (de.isBlank()) null else j.name.uppercase(java.util.Locale.ROOT) to t.text(io.github.jonathangtd.bimmerdata.live.i18n.TranslationType.Job, sgbd, j.name, de)
    }.toMap()
}
