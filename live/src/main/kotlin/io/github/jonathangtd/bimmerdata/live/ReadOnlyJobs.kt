/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import java.util.Locale

/**
 * Which SGBD jobs the live catalog may offer and the acquisition engine may run in a loop: jobs
 * that only read. A live dashboard must never actuate, code, flash, clear or change the session
 * of an ECU, whatever a layout file says. The interface's SafetyGuard still blocks flashing and
 * coding telegrams below this; this filter also keeps out actuator tests, routines, resets,
 * sleep modes and fault memory clears, which the guard allows for the diagnosis screens.
 *
 * A job is read-only when all of these hold:
 * 1. its name looks like a read: it starts with `STATUS` or `IDENT`, or one of its `_`-separated
 *    words is `LESEN`, `READ` or `STATUS`;
 * 2. no word of its name is an action ([deniedWords]: STEUERN, SCHREIBEN, LOESCHEN, FLASH, START,
 *    STOP, RESET, SLEEP, ENERGIESPARMODE, DIAGNOSE, MODE, CODIER*, ...; also reads of secrets and
 *    raw memory: ISN, PASSWORT, PIN, SPEICHER...), and it does not start
 *    with a [deniedPrefixes] entry (fault / info / history memory `FS_`, `IS_`, `HS_`, coding
 *    `C_`, manufacturer-internal `_` jobs, the composite PRUEFCODE / DIAGNOSEPROTOKOLL jobs);
 * 3. when the job documentation is known, its JOBCOMMENT names no writing or acting diagnostic
 *    service ([deniedServices]: StartRoutine, RoutineControl, ShortTermAdjustment, WriteData...,
 *    ECUReset, ClearDiagnostic..., SecurityAccess, RequestDownload, DiagnosticSessionControl...).
 *
 * Rule 3 drops about 270 of the ~18000 STATUS jobs of a full ECU folder (STATUS jobs that run a
 * routine or an IO control short-term adjustment to get their answer).
 */
object ReadOnlyJobs {
    private val deniedWords = setOf(
        "STEUERN", "STEUERE", "ANSTEUERN", "SCHREIBEN", "SCHREIBE", "WRITE", "LOESCHEN", "LOESCHE", "CLEAR", "CLR",
        "DELETE", "ERASE", "RESET", "FLASH", "FLASHEN", "PROG", "PROGRAMMIEREN", "PROGRAMMING", "CODIEREN",
        "CODIERUNG", "CODIERDATEN", "CODING", "START", "STARTEN", "STOP", "STOPPEN", "ENDE", "BEENDEN", "SLEEP",
        "ENERGIESPARMODE", "POWERDOWN", "DIAGNOSE", "DIAGNOSEMODE", "MODE", "MODUS", "SETZEN", "SET", "SPERREN",
        "ENTSPERREN", "FREISCHALTEN", "AKTIVIEREN", "DEAKTIVIEREN", "EINLERNEN", "LERNEN", "ANLERNEN",
        "INITIALISIERUNG", "INITIALISIEREN", "INIT", "AUTHENTISIERUNG", "SEED", "SECURITY", "ROUTINE",
        "SELBSTTEST", "TEST", "TESTEN", "KALIBRIEREN", "ABGLEICHEN", "EINSTELLEN", "VERSTELLEN", "AUFTRAG",
        "SYSTEMCHECK", "NORMALER",
        // reads, but of secrets or raw memory: nothing for a dashboard
        "ISN", "PASSWORT", "PASSWORD", "PASSKEY", "PIN", "NAVDVDPIN", "ZERTIFIKAT", "SPEICHER",
    )

    private val deniedPrefixes = listOf("FS_", "IS_", "HS_", "C_", "_", "PRUEFCODE", "DIAGNOSEPROTOKOLL")

    private val deniedServices = Regex(
        "StartRoutine|StopRoutine|RoutineControl|RequestRoutineResults|ShortTermAdjustment|FreezeCurrentState|" +
            "ResetToDefault|ECUReset|WriteData|WriteMemory|ClearDiagnostic|SecurityAccess|RequestDownload|" +
            "RequestUpload|TransferData|StartDiagnosticSession|DiagnosticSessionControl|CommunicationControl|" +
            "ControlDTCSetting|StopDiagnosticSession",
        RegexOption.IGNORE_CASE,
    )

    /** Rules 1 and 2, on the job name alone (what the engine checks before running a job). */
    fun isReadOnlyName(job: String): Boolean {
        val name = job.trim().uppercase(Locale.ROOT)
        if (name.isEmpty() || deniedPrefixes.any { name.startsWith(it) }) return false
        val words = name.split('_').filter { it.isNotEmpty() }
        if (words.any { it in deniedWords || it.startsWith("CODIER") }) return false
        return name.startsWith("STATUS") || name.startsWith("IDENT") || words.any { it == "LESEN" || it == "READ" || it == "STATUS" }
    }

    /** Rules 1 to 3. */
    fun isReadOnly(job: JobDescription): Boolean =
        isReadOnlyName(job.name) && job.comments.none { deniedServices.containsMatchIn(it) }
}
