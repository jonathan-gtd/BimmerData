/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import java.io.File

/**
 * Reads an SGBD without the car: its job list through the virtual job `_JOBS`. Uses its own
 * interpreter without an interface, so it never touches the shared cable and works disconnected.
 * Thread-safe (calls are serialized); call off the main thread (file I/O).
 */
class OfflineSgbd(ecuDir: File) : AutoCloseable {
    private val ediabas = EdiabasNet("EcuPath=${ecuDir.path}")

    /** Job names of [sgbd] ("msv70", "msv70.prg", "d_motor.grp"). */
    @Synchronized
    fun jobs(sgbd: String): List<String> {
        val file = if (sgbd.contains('.')) sgbd else "$sgbd.prg"
        if (!ediabas.sgbdFileName.equals(file, ignoreCase = true)) ediabas.sgbdFileName = file
        ediabas.noInitForVJobs = true
        ediabas.argString = ""
        ediabas.executeJob("_JOBS")
        return ediabas.resultSets!!.drop(1).mapNotNull { it["JOBNAME"]?.opData as? String }
    }

    @Synchronized
    override fun close() {
        runCatching { ediabas.close() }
    }
}
