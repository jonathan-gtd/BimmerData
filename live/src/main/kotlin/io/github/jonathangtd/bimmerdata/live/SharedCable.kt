/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceBase
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.ResultData

/*
 * One cable, one interpreter, many users. The acquisition engine owns the interface and the
 * EdiabasNet instance; everybody else (Tool32, vehicle scan, fault memory, REST API) borrows them
 * for one operation through [CableExecutor.exclusive]. The operation runs on the engine's thread
 * between two read cycles, then the live reading goes on by itself.
 */

/** The cable cannot be used: not connected, connection refused, or disconnected during the operation. */
class CableUnavailableException(message: String) : Exception(message)

/** Runs one-off operations on the shared cable. */
interface CableExecutor {
    /**
     * Runs [block] with exclusive use of the interpreter and the interface, between two read
     * cycles, and returns its result. Cancelling the calling coroutine aborts a running job (the
     * interpreter stops between opcodes, a pending receive is woken up).
     *
     * @param reason short text for the logs and the CSV pause line, e.g. "Tool32: MSV70 IDENT".
     * @throws CableUnavailableException when there is no usable connection.
     */
    suspend fun <T> exclusive(reason: String, block: (CableAccess) -> T): T
}

/**
 * What a one-off operation may use. Valid only inside [CableExecutor.exclusive], on the engine's
 * thread. The interpreter keeps its state between operations (selected SGBD, initialisation):
 * always select the SGBD through [selectSgbd] / [executeJob] so the engine knows which fast-path
 * blocks may have been touched (they are defined again afterwards).
 */
class CableAccess internal constructor(
    val ediabas: EdiabasNet,
    val iface: EdInterfaceBase,
    private val abortFlag: () -> Boolean,
) {
    internal val touched = HashSet<String>()

    /**
     * Extra abort condition of the operation (e.g. a Cancel button that wants the partial result
     * back instead of cancelling the coroutine). Checked between opcodes; to wake up a pending
     * receive also call [EdInterfaceBase.transmitCancel] (true) on [iface] from the other thread.
     */
    @Volatile var abortWhen: (() -> Boolean)? = null

    /** True once the operation was cancelled (the next interpreter call throws). */
    val aborted: Boolean get() = abortFlag() || abortWhen?.invoke() == true

    /** Selects an SGBD or group file, e.g. "msv70", "msv70.prg", "d_motor.grp". */
    fun selectSgbd(sgbd: String) {
        val file = if (sgbd.contains('.')) sgbd else "$sgbd.prg"
        if (!ediabas.sgbdFileName.equals(file, ignoreCase = true)) ediabas.sgbdFileName = file
        touched += EdiabasMeasurementCatalog.normalize(file)
    }

    /**
     * Runs [job] of [sgbd] with [args]; returns every result set (index 0 = system set). On an
     * exception the partial results stay in [EdiabasNet.resultSets].
     */
    fun executeJob(sgbd: String, job: String, args: String = ""): List<Map<String, ResultData>> {
        selectSgbd(sgbd)
        ediabas.argString = args
        ediabas.executeJob(job)
        return ediabas.resultSets ?: emptyList()
    }

    /** Identifies the variant of a group file with the ECU; returns the variant file name ("msv70.prg"). */
    fun resolveGroup(group: String): String {
        touched += EdiabasMeasurementCatalog.normalize(group)
        ediabas.resolveSgbdFile(group.substringBeforeLast('.'))
        touched += EdiabasMeasurementCatalog.normalize(ediabas.sgbdFileName)
        return ediabas.sgbdFileName
    }
}
