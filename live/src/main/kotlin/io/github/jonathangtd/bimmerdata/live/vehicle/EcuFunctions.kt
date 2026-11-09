/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import io.github.jonathangtd.bimmerdata.live.ItemDescription
import io.github.jonathangtd.bimmerdata.live.JobDescription
import io.github.jonathangtd.bimmerdata.live.ReadOnlyJobs
import io.github.jonathangtd.bimmerdata.live.Sample
import io.github.jonathangtd.bimmerdata.live.SgbdDescription
import io.github.jonathangtd.bimmerdata.live.i18n.TranslationTemplate
import java.util.Locale

/** What a function of an ECU does: only read, or act on the car (actuator test, self test). */
enum class EcuFunctionKind { Read, Test }

/**
 * One job of an ECU offered in its detail, the way a workshop tester lists an ECU's functions.
 * [description] is the German first sentence of the job comment (empty if undocumented).
 */
data class EcuFunction(
    val job: String,
    val kind: EcuFunctionKind,
    val description: String,
    val args: List<ItemDescription>,
    val results: List<ItemDescription>,
)

/**
 * Which jobs of an SGBD the ECU detail offers:
 * - **reads**: the jobs the live catalog may run too ([ReadOnlyJobs.isReadOnly]);
 * - **tests**: actuator controls and self tests (a name word in [testWords]), run after a
 *   confirmation, with the car at a standstill ([Standstill]);
 * - nothing else: coding, programming, writing, clearing (the fault memory has its own button),
 *   resets, sessions, security access, adaptations and learning, raw memory and secrets are never
 *   offered here (only Tool32 runs any job, and its interface guard still blocks coding and flashing).
 */
object EcuFunctions {
    private val testWords = setOf(
        "STEUERN", "STEUERE", "ANSTEUERN", "ANSTEUERUNG", "TEST", "TESTEN", "SELBSTTEST", "SELFTEST", "SYSTEMCHECK",
    )

    private val deniedWords = setOf(
        "SCHREIBEN", "SCHREIBE", "WRITE", "LOESCHEN", "LOESCHE", "CLEAR", "CLR", "DELETE", "ERASE", "FLASH", "FLASHEN",
        "PROG", "PROGRAMMIEREN", "PROGRAMMING", "CODING", "SPEICHER", "MEMORY", "ISN", "PASSWORT", "PASSWORD", "PASSKEY",
        "PIN", "SEED", "KEY", "SECURITY", "AUTHENTISIERUNG", "ZERTIFIKAT", "RESET", "SLEEP", "ENERGIESPARMODE",
        "POWERDOWN", "DIAGNOSE", "DIAGNOSEMODE", "NORMALER", "AUFTRAG", "FG", "VIN", "SPERREN", "ENTSPERREN",
        "FREISCHALTEN", "KALIBRIEREN", "ABGLEICHEN", "ABGLEICH", "EINLERNEN", "LERNEN", "ANLERNEN", "INITIALISIERUNG",
        "INITIALISIEREN", "INIT", "AUTOINIT", "BOOT", "BOOTMODE", "NORMIEREN", "DENORMIEREN", "ADAPTION", "ADAPTIONEN",
        "BETR", "PROGRAMM", "REGISTRIEREN", "EE", "EEPROM", "AKTIVIEREN", "DEAKTIVIEREN",
    )

    private val deniedPrefixes = listOf("FS_", "IS_", "HS_", "C_", "_", "PRUEFCODE", "PRUEFSTEMPEL", "DIAGNOSEPROTOKOLL")

    private val deniedServices = Regex(
        "WriteData|WriteMemory|RequestDownload|RequestUpload|TransferData|SecurityAccess|ClearDiagnostic|ECUReset|" +
            "StartDiagnosticSession|DiagnosticSessionControl|CommunicationControl|ControlDTCSetting",
        RegexOption.IGNORE_CASE,
    )

    /** A test whose comment talks about these changes something that stays (adaptations, coding). */
    private val deniedTopics = Regex("Adaption|Adaptation|Codier|Coding|Programmier|Lernwert|Anlern", RegexOption.IGNORE_CASE)

    /** [EcuFunctionKind] of [job], or null when it is not offered. */
    fun kindOf(job: JobDescription): EcuFunctionKind? {
        val name = job.name.trim().uppercase(Locale.ROOT)
        if (name.isEmpty()) return null
        if (ReadOnlyJobs.isReadOnly(job)) return EcuFunctionKind.Read
        if (deniedPrefixes.any { name.startsWith(it) }) return null
        val words = name.split('_').filter { it.isNotEmpty() }
        if (words.any { it in deniedWords || it.startsWith("CODIER") }) return null
        if (job.comments.any { deniedServices.containsMatchIn(it) || deniedTopics.containsMatchIn(it) }) return null
        return if (words.any { it in testWords }) EcuFunctionKind.Test else null
    }

    /** The functions of [desc], reads first, each group in the order of the file. */
    fun of(desc: SgbdDescription): List<EcuFunction> =
        desc.jobs.mapNotNull { j ->
            kindOf(j)?.let { EcuFunction(j.name, it, TranslationTemplate.jobText(j.comments), j.args, j.results) }
        }.sortedBy { it.kind.ordinal }
}

/**
 * Tests of actuators only with the car at a standstill: a test is refused when a fresh speed
 * reading (DME or GPS, at most [FRESH_MS] old) is above [MAX_KMH]. No speed known = allowed (the
 * user still confirms on the phone).
 */
object Standstill {
    const val MAX_KMH = 2.0
    const val FRESH_MS = 10_000L

    /** The speed that forbids a test, or null when the car may be tested. */
    fun blockingSpeed(speeds: Collection<Sample>, nowMs: Long): Double? =
        speeds.asSequence()
            .filter { nowMs - it.timestampMs in 0..FRESH_MS }
            .mapNotNull { it.value }
            .filter { it > MAX_KMH }
            .maxOrNull()
}
