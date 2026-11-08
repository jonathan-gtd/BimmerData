/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.diag

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNetException
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import io.github.jonathangtd.bimmerdata.ediabas.sim.SimulatedKLine
import io.github.jonathangtd.bimmerdata.live.InterfaceArbiter
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One interpreter + one interface. Every EDIABAS call runs on a single dedicated thread (the
 * interpreter is not thread-safe); callers suspend. [cancel] may be called from any thread: it
 * aborts the job between opcodes and wakes up a pending receive.
 */
class DiagSession(ecuDir: File, val iface: EdInterfaceObd, val label: String) : AutoCloseable {

    class JobRun(
        val sgbd: String,
        val job: String,
        val args: String,
        val sets: List<Map<String, ResultData>>,
        val elapsedNanos: Long,
        val telegrams: Long,
        val frames: List<EdInterfaceObd.Frame>,
        val startNanos: Long,
        /** null when the job ran to the end. */
        val error: String?,
        val cancelled: Boolean,
    )

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ediabas-job").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val ediabas = EdiabasNet("EcuPath=${ecuDir.path}")

    @Volatile private var abortRequested = false

    /** A real cable is claimed from the live acquisition ([InterfaceArbiter]) until [close]. */
    private val realCable = iface.port !is SimulatedKLine
    private val cableClaimed = AtomicBoolean(false)

    init {
        ediabas.abortJobFunc = { abortRequested }
        ediabas.edInterfaceClass = iface
    }

    /** Opens the port (pausing live acquisition on the same cable). Returns null on success, else an error text. */
    suspend fun connect(): String? {
        if (realCable && cableClaimed.compareAndSet(false, true)) InterfaceArbiter.acquireForDiag()
        return openPort()
    }

    private suspend fun openPort(): String? = withContext(dispatcher) {
        try {
            if (iface.interfaceConnect()) null else "Interface connect failed"
        } catch (ex: Exception) {
            listOfNotNull(describe(ex), iface.lastConnectError).joinToString(" - ")
        }
    }

    suspend fun readIgnition(): Boolean? = withContext(dispatcher) { iface.readDsr() }

    /** Jobs of an SGBD (virtual job _JOBS, no ECU traffic). */
    suspend fun listJobs(sgbd: String): List<String> = withContext(dispatcher) {
        selectSgbd(sgbd)
        ediabas.noInitForVJobs = true
        try {
            ediabas.executeJob("_JOBS")
        } finally {
            ediabas.noInitForVJobs = false
        }
        ediabas.resultSets!!.drop(1).mapNotNull { it["JOBNAME"]?.opData as? String }
    }

    /** Group file (.grp) -> variant file name, identified by talking to the ECU. */
    suspend fun resolveVariant(groupFile: String): String = withContext(dispatcher) {
        abortRequested = false
        ediabas.resolveSgbdFile(groupFile.substringBeforeLast('.'))
        ediabas.sgbdFileName
    }

    suspend fun runJob(sgbd: String, job: String, args: String): JobRun = withContext(dispatcher) {
        abortRequested = false
        selectSgbd(sgbd)
        val frames = ArrayList<EdInterfaceObd.Frame>()
        iface.frameListener = { frame -> if (frames.size < MaxFrames) frames.add(frame) }
        iface.resetStats()
        var error: String? = null
        val start = System.nanoTime()
        try {
            ediabas.argString = args
            ediabas.executeJob(job)
        } catch (ex: Exception) {
            val blocked = frames.lastOrNull { it.direction == EdInterfaceObd.FrameDirection.Error && it.text?.contains("SafetyGuard") == true }
            error = when {
                abortRequested -> "Cancelled"
                blocked != null -> "${describe(ex)} - ${blocked.text}"
                else -> describe(ex)
            }
        } finally {
            iface.frameListener = null
        }
        val elapsed = System.nanoTime() - start
        JobRun(
            sgbd, job, args, ediabas.resultSets ?: emptyList(), elapsed, iface.stats.telegrams, frames, start,
            error, abortRequested,
        )
    }

    fun cancel() {
        abortRequested = true
        iface.transmitCancel(true)
    }

    private fun selectSgbd(sgbd: String) {
        if (!ediabas.sgbdFileName.equals(sgbd, ignoreCase = true)) {
            ediabas.sgbdFileName = sgbd
        }
    }

    override fun close() {
        cancel()
        executor.execute {
            runCatching { ediabas.close() } // also closes the interface and its port
            if (cableClaimed.compareAndSet(true, false)) InterfaceArbiter.releaseFromDiag()
        }
        executor.shutdown()
    }

    companion object {
        private const val MaxFrames = 4000

        fun describe(ex: Throwable): String {
            var e: Throwable? = ex
            while (e != null) {
                if (e is EdiabasNetException) return EdiabasNet.getErrorDescription(e.errorCode)
                e = e.cause
            }
            val root = generateSequence(ex) { it.cause }.last()
            return root.message ?: root.javaClass.simpleName
        }
    }
}
