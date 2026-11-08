/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.diag

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNetException
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import io.github.jonathangtd.bimmerdata.live.CableExecutor
import java.util.Locale

/**
 * The mini Tool32 on the shared cable: every job is one [CableExecutor.exclusive] operation, run
 * between two read cycles of the live acquisition (which goes on). [cancel] may be called from
 * any thread: it aborts the running job between opcodes and wakes up a pending receive; the job
 * then returns a [JobRun] with `cancelled` and the frames sent so far.
 */
class DiagSession(private val cable: CableExecutor, private val label: String = "Tool32") {

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
        /** Ignition (DSR of the cable) right after the job, null if unknown. */
        val ignition: Boolean?,
    )

    @Volatile private var cancelRequested = false
    @Volatile private var runningIface: EdInterfaceObd? = null

    /** Ignition state (DSR line of the K+DCAN cable); null if the interface cannot tell. */
    suspend fun readIgnition(): Boolean? = cable.exclusive("$label: ignition") { a -> (a.iface as? EdInterfaceObd)?.readIgnitionLine() }

    /** Group file (.grp) -> variant file name, identified by talking to the ECU. */
    suspend fun resolveVariant(groupFile: String): String =
        cable.exclusive("$label: identify ${name(groupFile)}") { a -> a.resolveGroup(groupFile) }

    suspend fun runJob(sgbd: String, job: String, args: String): JobRun {
        cancelRequested = false
        return cable.exclusive("$label: ${name(sgbd)} $job") { a ->
            val obd = a.iface as? EdInterfaceObd
            a.abortWhen = { cancelRequested }
            runningIface = obd
            val frames = ArrayList<EdInterfaceObd.Frame>()
            obd?.frameListener = { frame -> if (frames.size < MaxFrames) frames.add(frame) }
            obd?.resetStats()
            var error: String? = null
            var sets: List<Map<String, ResultData>> = emptyList()
            val start = System.nanoTime()
            try {
                sets = a.executeJob(sgbd, job, args)
            } catch (ex: Exception) {
                sets = a.ediabas.resultSets ?: emptyList()
                val blocked = frames.lastOrNull { it.direction == EdInterfaceObd.FrameDirection.Error && it.text?.contains("SafetyGuard") == true }
                error = when {
                    a.aborted -> "Cancelled"
                    blocked != null -> "${describe(ex)} - ${blocked.text}"
                    else -> describe(ex)
                }
            } finally {
                obd?.frameListener = null
                runningIface = null
            }
            val elapsed = System.nanoTime() - start
            val cancelled = a.aborted
            val ignition = if (cancelled) null else runCatching { obd?.readIgnitionLine() }.getOrNull()
            JobRun(sgbd, job, args, sets, elapsed, obd?.stats?.telegrams ?: 0, frames, start, error, cancelled, ignition)
        }
    }

    fun cancel() {
        cancelRequested = true
        runningIface?.let { runCatching { it.transmitCancel(true) } }
    }

    private fun name(sgbd: String) = sgbd.substringBeforeLast('.').uppercase(Locale.ROOT)

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
