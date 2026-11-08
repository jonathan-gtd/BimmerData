/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceBase
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNetException
import io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Result of one job run. [sets] excludes the system set (index 0 of EdiabasNet.resultSets). */
data class JobOutcome(
    val sets: List<Map<String, ResultData>>,
    /** Null when the job ran to the end (whatever its JOB_STATUS). */
    val error: String? = null,
    /** The ECU did not answer (EDIABAS_IFH_0009 / IFH_0010). */
    val noResponse: Boolean = false,
    val cancelled: Boolean = false,
) {
    /** JOB_STATUS of the last result set (the jobs put the overall status there). */
    val jobStatus: String? get() = sets.lastOrNull()?.get("JOB_STATUS")?.opData as? String

    val ok: Boolean get() = error == null
}

/** Result of a group file identification (IDENTIFIKATION job, EdiabasNet.resolveSgbdFile). */
data class GroupResolution(val variant: String?, val error: String? = null, val noResponse: Boolean = false)

/**
 * Proof that the user confirmed a fault memory clear in a dialog. Only the clear jobs require it;
 * the vehicle and fault code never runs any other writing job.
 */
class UserConfirmation(val action: String, val atMs: Long = System.currentTimeMillis())

/**
 * Runs EDIABAS jobs for the vehicle scan and the fault memory. Only the jobs of [VehicleJobs] are
 * accepted: everything else is refused before the interpreter is even called.
 */
interface JobRunner {
    suspend fun run(sgbd: String, job: String, args: String = ""): JobOutcome

    /** Fault memory clear ([VehicleJobs.Clear] only), after the user's [confirmation]. */
    suspend fun clear(sgbd: String, job: String, confirmation: UserConfirmation): JobOutcome

    /** Identifies the variant of a group file by talking to the ECU (IDENTIFIKATION). */
    suspend fun resolveGroup(group: String): GroupResolution
}

/** The only jobs the vehicle scan and the fault views run. */
object VehicleJobs {
    /** Read-only jobs (identification, VIN, fault memory). */
    val Read = setOf(
        "IDENT_FUNKTIONAL", "C_FG_LESEN_FUNKTIONAL", "FS_LESEN_FUNKTIONAL",
        "IDENT", "FS_LESEN", "FS_LESEN_DETAIL", "STATUS_FAHRGESTELLNUMMER",
    )

    /** Fault memory clear: the only writes, each one behind a [UserConfirmation]. */
    val Clear = setOf("FS_LOESCHEN", "FS_LOESCHEN_FUNKTIONAL")
}

/**
 * [JobRunner] on one [EdiabasNet] interpreter and [iface]. Every call runs on a dedicated thread
 * (the interpreter is not thread-safe); callers suspend. [cancel] may be called from any thread.
 */
class EdiabasJobRunner(
    ecuDir: File,
    val iface: EdInterfaceBase,
    config: String = "",
) : JobRunner, AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vehicle-jobs").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val ediabas = EdiabasNet("EcuPath=${ecuDir.path}" + if (config.isNotEmpty()) ";$config" else "")

    @Volatile private var abortRequested = false

    init {
        ediabas.abortJobFunc = { abortRequested }
        ediabas.edInterfaceClass = iface
    }

    /** Opens the interface. Returns null on success, else an error text. */
    suspend fun connect(): String? = withContext(dispatcher) {
        try {
            if (iface.interfaceConnect()) null else "Interface connect failed"
        } catch (ex: Exception) {
            describe(ex)
        }
    }

    override suspend fun run(sgbd: String, job: String, args: String): JobOutcome {
        require(job.uppercase(Locale.ROOT) in VehicleJobs.Read) { "job $job is not allowed here" }
        return execute(sgbd, job, args)
    }

    override suspend fun clear(sgbd: String, job: String, confirmation: UserConfirmation): JobOutcome {
        require(job.uppercase(Locale.ROOT) in VehicleJobs.Clear) { "job $job is not a fault memory clear" }
        return execute(sgbd, job, "")
    }

    private suspend fun execute(sgbd: String, job: String, args: String): JobOutcome = withContext(dispatcher) {
        abortRequested = false
        try {
            val file = if (sgbd.contains('.')) sgbd else "$sgbd.prg"
            if (!ediabas.sgbdFileName.equals(file, ignoreCase = true)) ediabas.sgbdFileName = file
            ediabas.argString = args
            ediabas.executeJob(job)
            JobOutcome(ediabas.resultSets?.drop(1) ?: emptyList())
        } catch (ex: Exception) {
            val partial = ediabas.resultSets?.drop(1) ?: emptyList()
            JobOutcome(partial, error = if (abortRequested) "Cancelled" else describe(ex), noResponse = isNoResponse(ex), cancelled = abortRequested)
        }
    }

    override suspend fun resolveGroup(group: String): GroupResolution = withContext(dispatcher) {
        abortRequested = false
        try {
            ediabas.resolveSgbdFile(group.substringBeforeLast('.').lowercase(Locale.ROOT))
            GroupResolution(EdFilesName.base(ediabas.sgbdFileName))
        } catch (ex: Exception) {
            GroupResolution(null, if (abortRequested) "Cancelled" else describe(ex), isNoResponse(ex))
        }
    }

    fun cancel() {
        abortRequested = true
        runCatching { iface.transmitCancel(true) }
    }

    override fun close() {
        cancel()
        executor.execute { runCatching { ediabas.close() } } // also closes the interface and its port
        executor.shutdown()
    }

    /** Like [close], but returns once the interface (and its port) is closed. */
    suspend fun closeAndJoin() {
        cancel()
        runCatching { withContext(kotlinx.coroutines.NonCancellable + dispatcher) { runCatching { ediabas.close() } } }
        executor.shutdown()
    }

    companion object {
        /** Readable text of an interpreter error (EDIABAS error description when there is one). */
        fun describe(ex: Throwable): String {
            var e: Throwable? = ex
            while (e != null) {
                if (e is EdiabasNetException) {
                    val text = EdiabasNet.getErrorDescription(e.errorCode)
                    return if (text.isNotEmpty()) "${e.errorCode.toString().removePrefix("EDIABAS_")}: $text" else e.errorCode.toString()
                }
                e = e.cause
            }
            val root = generateSequence(ex) { it.cause }.last()
            return root.message ?: root.javaClass.simpleName
        }

        fun isNoResponse(ex: Throwable): Boolean {
            var e: Throwable? = ex
            while (e != null) {
                if (e is EdiabasNetException) return e.errorCode == ErrorCodes.EDIABAS_IFH_0009 || e.errorCode == ErrorCodes.EDIABAS_IFH_0010
                e = e.cause
            }
            return false
        }
    }
}

/** File name helpers for SGBD names. */
internal object EdFilesName {
    /** "msv70.prg" -> "msv70". */
    fun base(fileName: String): String = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').lowercase(Locale.ROOT)
}
