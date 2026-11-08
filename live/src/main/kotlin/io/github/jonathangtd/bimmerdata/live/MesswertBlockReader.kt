/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceBase
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNetException
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** The interface did not deliver an answer (timeout, cable, checksum...). */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The ECU answered the read, but no longer with the defined record (reset, session timeout...). */
class DefinitionLostException(message: String) : Exception(message)

/** The SGBD job or the ECU refused the block definition (JOB_STATUS other than OKAY). */
class DefineRejectedException(val jobStatus: String, message: String) : Exception(message)

/**
 * Fast path for one block of MESSWERTETAB values of one SGBD.
 *
 * [defineAndRead] runs STATUS_MESSWERTBLOCK_LESEN("3;ids") in the interpreter, exactly like
 * Tool32: clear F0, define F0 by common identifiers, read F0. From the job's own results
 * (_TEL_AUFTRAG_2 = definition, _TEL_AUFTRAG_3 = read request, _TEL_ANTWORT_3 = answer) it learns
 * the record layout and checks that the native decoder gives the same values as the SGBD.
 *
 * [read] then only sends the job's read request (`21 F0`) through the interface and decodes the
 * answer natively (mode [Mode.Native]). If the native decoder could not be verified, it runs the
 * job in read-only mode "2;ids" instead (mode [Mode.Interpreted]: same single telegram, decoded
 * by the SGBD). After [DefinitionLostException] the caller calls [defineAndRead] again.
 *
 * Not thread-safe: use from the thread owning [ediabas] and [iface].
 */
class MesswertBlockReader(
    private val ediabas: EdiabasNet,
    private val iface: EdInterfaceBase,
    /** SGBD file name as given to [EdiabasNet.sgbdFileName], e.g. "msv70.prg". */
    val sgbdFile: String,
    val tab: MesswerteTab,
    val rows: List<MesswertRow>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Mode { Undefined, Native, Interpreted }

    var mode: Mode = Mode.Undefined
        private set

    var defines = 0L
        private set
    var reads = 0L
        private set

    /** Why the native decoder is not used (null in native mode). */
    var interpretedReason: String? = null
        private set

    private var layout: MesswertDecoder.Layout? = null
    private var readRequest: ByteArray? = null
    private var commParameter: UIntArray? = null
    private var commAnswerLen: ShortArray? = null
    private var commRepeats: UInt = 0u

    private val idArgs = rows.joinToString(";") { "0x%04X".format(Locale.ROOT, it.id) }

    init {
        require(rows.isNotEmpty()) { "empty block" }
    }

    fun invalidate() {
        mode = Mode.Undefined
    }

    /** Defines the record in the ECU and reads it once. Returns the samples by row. */
    fun defineAndRead(): Map<MesswertRow, Sample> {
        mode = Mode.Undefined
        defines++
        val sets = runJob("3;$idArgs")
        val results = sets.getOrNull(1) ?: throw DefineRejectedException("", "no result set")
        val status = results["JOB_STATUS"]?.opData as? String ?: ""
        val now = clock()

        val defineTel = results["_TEL_AUFTRAG_2"]?.opData as? ByteArray
        val readTel = results["_TEL_AUFTRAG_3"]?.opData as? ByteArray
        val answer = results["_TEL_ANTWORT_3"]?.opData as? ByteArray
        val lay = defineTel?.let { MesswertDecoder.layout(tab, it) }?.takeIf { l -> l.slots.map { it.row.id } == rows.map { it.id } }

        if (answer != null && readTel != null && lay != null) {
            val decoded = MesswertDecoder.decode(tab, lay, answer, now)
            if (decoded is MesswertDecoder.Decoded.Values) {
                val mismatch = verify(decoded.values, results)
                snapshotCommunication()
                layout = lay
                readRequest = readTel.copyOf()
                if (mismatch == null) {
                    mode = Mode.Native
                    interpretedReason = null
                } else {
                    mode = Mode.Interpreted
                    interpretedReason = mismatch
                }
                // Values are valid even if the job stopped later (e.g. on a state text lookup)
                return decoded.values.associate { (slot, sample) -> slot.row to sample }
            }
            throw DefineRejectedException(status, "$sgbdFile: answer to the definition not usable (${describe(decoded)})")
        }
        if (status != "OKAY") throw DefineRejectedException(status, "$sgbdFile STATUS_MESSWERTBLOCK_LESEN: $status")
        // OKAY but without telegram results (other SGBD generation): trust the job, read it every time
        snapshotCommunication()
        mode = Mode.Interpreted
        interpretedReason = "job gives no _TEL_* results"
        return samplesFromJob(results, now)
    }

    /** One read of the defined record. */
    fun read(): Map<MesswertRow, Sample> = when (mode) {
        Mode.Undefined -> defineAndRead()
        Mode.Native -> readNative()
        Mode.Interpreted -> readInterpreted()
    }

    private fun readNative(): Map<MesswertRow, Sample> {
        val request = readRequest!!
        val lay = layout!!
        restoreCommunication()
        val response = try {
            iface.transmitData(request)
        } catch (ex: Exception) {
            throw TransportException(errorText(ex), ex)
        } ?: throw TransportException(EdiabasNet.getErrorDescription(ediabas.errorCodeLast).ifBlank { "no response" })
        reads++
        return when (val d = MesswertDecoder.decode(tab, lay, response, clock())) {
            is MesswertDecoder.Decoded.Values -> d.values.associate { (slot, sample) -> slot.row to sample }
            is MesswertDecoder.Decoded.NotDefined -> {
                mode = Mode.Undefined
                throw DefinitionLostException("$sgbdFile: ${d.reason}")
            }
            is MesswertDecoder.Decoded.Invalid -> throw TransportException("$sgbdFile: ${d.reason}")
        }
    }

    private fun readInterpreted(): Map<MesswertRow, Sample> {
        val sets = runJob("2;$idArgs")
        reads++
        val results = sets.getOrNull(1) ?: throw TransportException("no result set")
        val now = clock()
        val answer = results["_TEL_ANTWORT"]?.opData as? ByteArray ?: results["_TEL_ANTWORT_3"]?.opData as? ByteArray
        val lay = layout
        if (answer != null && lay != null) {
            when (val d = MesswertDecoder.decode(tab, lay, answer, now)) {
                is MesswertDecoder.Decoded.NotDefined -> {
                    mode = Mode.Undefined
                    throw DefinitionLostException("$sgbdFile: ${d.reason}")
                }
                else -> Unit
            }
        }
        val samples = samplesFromJob(results, now)
        if (samples.isEmpty()) {
            mode = Mode.Undefined
            throw DefinitionLostException("$sgbdFile read-only job: ${results["JOB_STATUS"]?.opData}")
        }
        return samples
    }

    private fun runJob(args: String): List<Map<String, ResultData>> {
        if (!ediabas.sgbdFileName.equals(sgbdFile, ignoreCase = true)) ediabas.sgbdFileName = sgbdFile
        try {
            ediabas.argString = args
            ediabas.executeJob(JobName)
        } catch (ex: Exception) {
            throw TransportException(errorText(ex), ex)
        }
        return ediabas.resultSets ?: throw TransportException("no results")
    }

    /** Null if every value the job decoded equals ours, else a description of the first difference. */
    private fun verify(values: List<Pair<MesswertDecoder.Slot, Sample>>, results: Map<String, ResultData>): String? {
        var compared = 0
        for ((slot, sample) in values) {
            val ref = results[slot.row.resultName.uppercase(Locale.ROOT)] ?: continue
            val expected = (ref.opData as? Number)?.toDouble() ?: continue
            val ours = sample.value ?: return "${slot.row.resultName}: no value"
            if (abs(ours - expected) > 1e-6 * max(1.0, abs(expected))) {
                return "${slot.row.resultName}: native $ours, SGBD $expected"
            }
            compared++
        }
        return if (compared == 0) "no value to compare with the SGBD" else null
    }

    private fun samplesFromJob(results: Map<String, ResultData>, now: Long): Map<MesswertRow, Sample> {
        val out = LinkedHashMap<MesswertRow, Sample>()
        for (row in rows) {
            val r = results[row.resultName.uppercase(Locale.ROOT)] ?: continue
            out[row] = toSample(r, now) ?: continue
        }
        return out
    }

    private fun snapshotCommunication() {
        commParameter = iface.commParameter?.copyOf()
        commAnswerLen = iface.commAnswerLen.copyOf()
        commRepeats = iface.commRepeats
    }

    /** Another SGBD may have set its own parameters in between (several ECUs on one cable). */
    private fun restoreCommunication() {
        val wanted = commParameter ?: return
        val current = iface.commParameter
        if (current == null || !current.contentEquals(wanted)) {
            iface.commParameter = wanted.copyOf()
            commAnswerLen?.let { iface.commAnswerLen = it.copyOf() }
            iface.commRepeats = commRepeats
        }
    }

    private fun describe(d: MesswertDecoder.Decoded) = when (d) {
        is MesswertDecoder.Decoded.NotDefined -> d.reason
        is MesswertDecoder.Decoded.Invalid -> d.reason
        is MesswertDecoder.Decoded.Values -> "ok"
    }

    companion object {
        const val JobName = "STATUS_MESSWERTBLOCK_LESEN"

        /** Ids per definition the SGBD accepts: 2 + 6 * n bytes must fit the one-byte length (255). */
        const val MaxIdsPerDefinition = 42

        /** Bytes of the record in one answer "61 F0 <record>" with a one-byte length. */
        const val MaxRecordBytes = 253

        fun toSample(r: ResultData, now: Long): Sample? = when (val v = r.opData) {
            is Double -> Sample(v, null, now)
            is Long -> Sample(v.toDouble(), null, now)
            is Number -> Sample(v.toDouble(), null, now)
            is String -> Sample(v.trim().replace(',', '.').toDoubleOrNull(), v, now)
            is ByteArray -> Sample(null, v.joinToString(" ") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }, now)
            else -> null
        }

        fun errorText(ex: Throwable): String {
            var e: Throwable? = ex
            while (e != null) {
                if (e is EdiabasNetException) return EdiabasNet.getErrorDescription(e.errorCode)
                e = e.cause
            }
            return ex.message ?: ex.javaClass.simpleName
        }
    }
}
