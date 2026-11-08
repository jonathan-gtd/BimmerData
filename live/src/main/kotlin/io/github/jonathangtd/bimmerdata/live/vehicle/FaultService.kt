/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import java.util.Locale

/**
 * Fault memory of all ECUs of a [Vehicle]:
 *
 * - [readAll]: one functional FS_LESEN_FUNKTIONAL (every ECU answers its fault count and codes),
 *   then FS_LESEN with the variant SGBD only for the ECUs that have faults (texts from the SGBD)
 *   or that did not answer the functional request (some ECUs do not support it, or the answer was
 *   lost). Without a functional job, every ECU is read one by one.
 * - [detail]: FS_LESEN_DETAIL of one fault (counters, mileage, environment values).
 * - [clearEcu] / [clearAll]: FS_LOESCHEN of one ECU, or FS_LOESCHEN_FUNKTIONAL for all, each
 *   behind a [UserConfirmation], followed by a read so that the user sees what is left.
 *
 * No other job is run (see [VehicleJobs]); the interface's SafetyGuard stays active as well.
 */
class FaultService(
    private val runner: JobRunner,
    private val tables: VehicleTables,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Progress(val done: Int, val total: Int, val ecuName: String?)

    /** Reads every ECU of [vehicle]. Returns one entry per ECU (address order). */
    suspend fun readAll(vehicle: Vehicle, onProgress: (Progress) -> Unit = {}): List<EcuFaults> {
        val total = vehicle.ecus.size + 1
        onProgress(Progress(0, total, null))
        val functional = readFunctional(vehicle)
        val result = ArrayList<EcuFaults>()
        vehicle.ecus.forEachIndexed { i, ecu ->
            onProgress(Progress(i + 1, total, ecu.name))
            val f = functional[ecu.address]
            result += when {
                f != null && f.codes.isEmpty() -> EcuFaults(ecu.address, EcuFaultStatus.Ok, readAtMs = clock())
                f != null && (!ecu.sgbdAvailable || ecu.sgbd == null) -> codesOnly(ecu, f.codes)
                else -> readEcu(ecu).let { r ->
                    // the ECU listed codes functionally but its own job failed: keep the codes
                    if (f != null && r.status != EcuFaultStatus.Faults && r.status != EcuFaultStatus.Ok) codesOnly(ecu, f.codes, r.message) else r
                }
            }
        }
        onProgress(Progress(total, total, null))
        return result
    }

    /** Fault codes per address from FS_LESEN_FUNKTIONAL (empty if the job is not available or failed). */
    private class FunctionalFaults(val codes: List<Pair<Int, Int>>)

    private suspend fun readFunctional(vehicle: Vehicle): Map<Int, FunctionalFaults> {
        val sgbd = vehicle.seriesSgbd ?: return emptyMap()
        if (!tables.hasJob(sgbd, "FS_LESEN_FUNKTIONAL")) return emptyMap()
        val out = runner.run(sgbd, "FS_LESEN_FUNKTIONAL")
        if (out.cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
        val map = HashMap<Int, FunctionalFaults>()
        for (set in out.sets) {
            val address = set.long("ID_SG_ADR")?.toInt() ?: continue
            if (set.str("JOB_STATUS") != "OKAY") continue
            val count = set.long("F_ANZ")?.toInt() ?: continue
            val codes = (1..count).mapNotNull { i ->
                val code = set.long("F_ORT${i}_NR")?.toInt() ?: return@mapNotNull null
                code to (set.long("F_ART${i}_NR")?.toInt() ?: 0)
            }
            map[address] = FunctionalFaults(if (codes.isEmpty() && count > 0) List(count) { 0 to 0 } else codes)
        }
        return map
    }

    private fun codesOnly(ecu: VehicleEcu, codes: List<Pair<Int, Int>>, message: String? = null): EcuFaults = EcuFaults(
        ecu.address,
        EcuFaultStatus.Faults,
        codes.map { (code, status) -> Fault(code, "", hex = String.format("%04X %02X", code, status)) },
        message = message ?: NoSgbdNote,
        readAtMs = clock(),
    )

    /** FS_LESEN of one ECU with its variant SGBD. */
    suspend fun readEcu(ecu: VehicleEcu): EcuFaults {
        val sgbd = ecu.sgbd?.takeIf { ecu.sgbdAvailable }
            ?: return EcuFaults(ecu.address, EcuFaultStatus.Error, message = NoSgbdNote, readAtMs = clock())
        val out = runner.run(sgbd, "FS_LESEN")
        if (out.cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
        return toEcuFaults(ecu.address, out)
    }

    private fun toEcuFaults(address: Int, out: JobOutcome): EcuFaults {
        if (out.noResponse) return EcuFaults(address, EcuFaultStatus.NotResponding, message = out.error, readAtMs = clock())
        if (out.error != null) return EcuFaults(address, EcuFaultStatus.Error, message = out.error, readAtMs = clock())
        val status = out.jobStatus
        if (status != null && status != "OKAY") return EcuFaults(address, EcuFaultStatus.Error, message = status, readAtMs = clock())
        val faults = parseFaults(out.sets)
        return EcuFaults(address, if (faults.isEmpty()) EcuFaultStatus.Ok else EcuFaultStatus.Faults, faults, readAtMs = clock())
    }

    /** FS_LESEN_DETAIL of [fault] (argument: the fault code). */
    suspend fun detail(ecu: VehicleEcu, fault: Fault): Result<FaultDetail> {
        val sgbd = ecu.sgbd?.takeIf { ecu.sgbdAvailable } ?: return Result.failure(IllegalStateException(NoSgbdNote))
        if (!tables.hasJob(sgbd, "FS_LESEN_DETAIL")) return Result.failure(IllegalStateException("$sgbd has no FS_LESEN_DETAIL"))
        val out = runner.run(sgbd, "FS_LESEN_DETAIL", String.format("0x%04X", fault.code))
        if (out.cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
        if (out.error != null) return Result.failure(IllegalStateException(out.error))
        val status = out.jobStatus
        if (status != null && status != "OKAY") return Result.failure(IllegalStateException(status))
        return Result.success(parseDetail(fault, out.sets))
    }

    /** FS_LOESCHEN on one ECU, then reads it again. */
    suspend fun clearEcu(ecu: VehicleEcu, confirmation: UserConfirmation): EcuFaults {
        val sgbd = ecu.sgbd?.takeIf { ecu.sgbdAvailable }
            ?: return EcuFaults(ecu.address, EcuFaultStatus.Error, message = NoSgbdNote, readAtMs = clock())
        val out = runner.clear(sgbd, "FS_LOESCHEN", confirmation)
        if (out.cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
        val after = readEcu(ecu)
        val failure = out.error ?: out.jobStatus?.takeIf { it != "OKAY" }
        return if (failure != null) after.copy(message = "Clear failed: $failure") else after
    }

    /**
     * Clears every ECU: FS_LOESCHEN_FUNKTIONAL of the vehicle SGBD when it has it (one request),
     * else FS_LOESCHEN on each ECU; then reads everything again.
     */
    suspend fun clearAll(vehicle: Vehicle, confirmation: UserConfirmation, onProgress: (Progress) -> Unit = {}): List<EcuFaults> {
        val sgbd = vehicle.seriesSgbd
        if (sgbd != null && tables.hasJob(sgbd, "FS_LOESCHEN_FUNKTIONAL")) {
            val out = runner.clear(sgbd, "FS_LOESCHEN_FUNKTIONAL", confirmation)
            if (out.cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
        } else {
            vehicle.ecus.filter { it.sgbdAvailable && it.sgbd != null }.forEachIndexed { i, ecu ->
                onProgress(Progress(i, vehicle.ecus.size, ecu.name))
                val out = runner.clear(ecu.sgbd!!, "FS_LOESCHEN", confirmation)
                if (out.cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        return readAll(vehicle, onProgress)
    }

    companion object {
        /** Note for faults shown as codes only. */
        const val NoSgbdNote = "Variant SGBD not available: fault codes without texts"

        /** Fault entries of FS_LESEN result sets (one set per fault with F_ORT_NR). */
        fun parseFaults(sets: List<Map<String, ResultData>>): List<Fault> = sets.mapNotNull { set ->
            val code = set.long("F_ORT_NR")?.toInt() ?: return@mapNotNull null
            parseFault(code, set)
        }

        private fun parseFault(code: Int, set: Map<String, ResultData>): Fault {
            val extra = ArrayList<String>()
            set.str("F_READY_TEXT")?.takeIf { it.isNotBlank() }?.let { extra += it }
            val symptom = set.str("F_SYMPTOM_TEXT")?.takeIf { it.isNotBlank() } ?: set.str("F_ART1_TEXT")?.takeIf { it.isNotBlank() }
            return Fault(
                code = code,
                text = set.str("F_ORT_TEXT").orEmpty().let { stripCodePrefix(it, code) },
                symptom = symptom,
                presence = set.str("F_VORHANDEN_TEXT")?.takeIf { it.isNotBlank() },
                warning = set.str("F_WARNUNG_TEXT")?.takeIf { it.isNotBlank() },
                extra = extra,
                hex = set["F_HEX_CODE"]?.formatted(),
            )
        }

        /** "2F08 Ansauglufttemperatursensor" / "5DF0 - Pumpenmotor defekt." -> text without the code. */
        internal fun stripCodePrefix(text: String, code: Int): String {
            val hex = String.format("%04X", code)
            val t = text.trim()
            if (!t.uppercase(Locale.ROOT).startsWith(hex)) return t
            return t.substring(hex.length).trimStart(' ', '-', ':').trim()
        }

        /** FS_LESEN_DETAIL result sets: the first holds the fault, every set with F_UW_KM one occurrence. */
        fun parseDetail(fault: Fault, sets: List<Map<String, ResultData>>): FaultDetail {
            val main = sets.firstOrNull { it.containsKey("F_ORT_NR") }
            val kinds = main?.let { m ->
                (1..(m.long("F_ART_ANZ")?.toInt() ?: 0)).mapNotNull { i -> m.str("F_ART${i}_TEXT")?.takeIf { it.isNotBlank() && it != "--" } }
            } ?: emptyList()
            val occurrences = sets.filter { it.containsKey("F_UW_KM") }.map { s ->
                val count = s.long("F_UW_ANZ")?.toInt() ?: (1..64).count { s.containsKey("F_UW${it}_TEXT") }
                val values = (1..count).mapNotNull { i ->
                    val name = s.str("F_UW${i}_TEXT") ?: return@mapNotNull null
                    val value = s["F_UW${i}_WERT"]?.formatted() ?: return@mapNotNull null
                    NamedValue(name, value, s.str("F_UW${i}_EINH")?.takeIf { it.isNotBlank() && it != "-" })
                }
                FaultOccurrence(s.long("F_UW_KM"), values)
            }
            return FaultDetail(
                fault = main?.let { parseFault(fault.code, it) }?.let { f -> if (f.text.isEmpty()) f.copy(text = fault.text) else f } ?: fault,
                frequency = main?.long("F_HFK")?.toInt()?.takeIf { it >= 0 },
                logisticCounter = main?.long("F_LZ")?.toInt()?.takeIf { it >= 0 },
                pcode = main?.str("F_PCODE_STRING")?.takeIf { it.isNotBlank() && it != "--" && it != "??" },
                pcodeText = main?.str("F_PCODE_TEXT")?.takeIf { it.isNotBlank() },
                kinds = kinds,
                occurrences = occurrences,
            )
        }
    }
}
