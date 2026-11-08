/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 *
 * The detection order (functional identification with retries, VIN jobs, group files for the
 * variants) follows Deep OBD's E-series detection (BmwDeepObd/XmlToolActivity.cs and
 * EdiabasLib/BmwFileReader/DetectVehicleBmwBase.cs of https://github.com/uholeschak/ediabaslib,
 * Ulrich Holeschak, GPL-3.0); the series comes from T_GRTB instead of Deep OBD's vehicle database.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import java.util.Locale

/**
 * Finds the ECUs of an E-series car (BMW-FAST), INPA / Deep OBD style:
 *
 * 1. **Functional identification**: IDENT_FUNKTIONAL of a vehicle SGBD (E89X, E60, E65, E70,
 *    R56) sends `1A 80` to the functional address 0xEF; every ECU answers with its address and
 *    identification, and the job maps each one through T_GRTB to ECU_SGBD / ECU_GRUPPE. K-line
 *    answers can be lost, so the job is repeated until no new address shows up (max
 *    [Options.identRetries]).
 * 2. **Series**: each answer's (address, variant index, diagnosis index) row of T_GRTB gives the
 *    series the ECU belongs to; the majority picks the vehicle SGBD (E87/E90 -> E89X, E60/E63 ->
 *    E60...). A user choice ([scan] `preferredSeriesSgbd`) is tried first.
 * 3. **VIN**: C_FG_LESEN_FUNKTIONAL (short VIN of several ECUs, majority), then the CAS
 *    STATUS_FAHRGESTELLNUMMER for the 17-character VIN.
 * 4. **Variants**: for every ECU its group file (ECU_GRUPPE, else the T_GRTB group of the address)
 *    is identified like EDIABAS does (IDENTIFIKATION -> variant .prg); ECU_SGBD is the fallback.
 * 5. **Buses** (optional): IDENT_FUNKTIONAL on each bus address (PT-CAN, K-CAN, MOST, SI) gives
 *    the topology for the tree view, kept only if it covers most ECUs.
 *
 * Fallback without any functional answer: the group files of T_GRTB are identified one by one
 * (slow: every absent ECU costs a timeout).
 *
 * Only read jobs are run (see [VehicleJobs]).
 */
class VehicleScanner(
    private val runner: JobRunner,
    private val tables: VehicleTables,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Step { Identifying, Vin, Variants, Buses, Sequential }

    data class Progress(val step: Step, val done: Int = 0, val total: Int = 0, val detail: String? = null)

    data class Options(
        val identRetries: Int = 3,
        val probeBuses: Boolean = true,
        val sequentialFallback: Boolean = true,
    )

    data class Result(
        val vehicle: Vehicle?,
        /** Why nothing was found (null if [vehicle] is set). */
        val error: String? = null,
        val cancelled: Boolean = false,
        /** What was done, for the user and for bug reports. */
        val log: List<String> = emptyList(),
    )

    /** One answer of the functional identification. */
    private class Answer(
        val address: Int,
        val grobname: String?,
        val ecuName: String?,
        val ecuSgbd: String?,
        val ecuGroup: String?,
        val diagIndex: Int?,
        val variantIndex: String?,
        val ident: List<NamedValue>,
    )

    private val log = ArrayList<String>()

    /** Thrown inside the scan when the runner reports a cancelled job. */
    private class Cancelled : Exception()

    private fun JobOutcome.orCancel(): JobOutcome = if (cancelled) throw Cancelled() else this

    private fun GroupResolution.orCancel(): GroupResolution = if (error == "Cancelled") throw Cancelled() else this

    private fun note(text: String) {
        log += text
    }

    suspend fun scan(
        previous: Vehicle? = null,
        preferredSeriesSgbd: String? = null,
        options: Options = Options(),
        onProgress: (Progress) -> Unit = {},
    ): Result {
        log.clear()
        return try {
            scanInternal(previous, preferredSeriesSgbd, options, onProgress)
        } catch (_: Cancelled) {
            Result(null, "Cancelled", cancelled = true, log = log.toList())
        }
    }

    private suspend fun scanInternal(
        previous: Vehicle?,
        preferredSeriesSgbd: String?,
        options: Options,
        onProgress: (Progress) -> Unit,
    ): Result {
        val candidates = (listOfNotNull(preferredSeriesSgbd?.lowercase(Locale.ROOT)) + tables.vehicleSgbds)
            .distinct().filter { tables.hasJob(it, "IDENT_FUNKTIONAL") }
        note("vehicle SGBDs with IDENT_FUNKTIONAL: ${candidates.joinToString().ifEmpty { "none" }}")

        // 1. functional identification
        onProgress(Progress(Step.Identifying))
        val answers = LinkedHashMap<Int, Answer>()
        var functionalSgbd: String? = null
        var lastError: String? = null
        var silent = false
        for (sgbd in candidates) {
            for (attempt in 1..options.identRetries.coerceAtLeast(1)) {
                onProgress(Progress(Step.Identifying, attempt, options.identRetries, sgbd.uppercase(Locale.ROOT)))
                val out = runner.run(sgbd, "IDENT_FUNKTIONAL").orCancel()
                val before = answers.size
                for (set in out.sets) parseAnswer(set)?.let { a -> answers.putIfAbsent(a.address, a) }
                note("IDENT_FUNKTIONAL($sgbd) #$attempt: ${answers.size - before} new, ${out.error ?: "ok"}")
                if (out.error != null && answers.isEmpty()) {
                    lastError = out.error
                    silent = out.noResponse
                    break
                }
                if (attempt > 1 && answers.size == before) break
            }
            if (answers.isNotEmpty()) {
                functionalSgbd = sgbd
                break
            }
            if (silent) break // the request is the same with every vehicle SGBD: no other one will get an answer
        }

        val vehicle = if (answers.isNotEmpty() && functionalSgbd != null) {
            fromFunctional(previous, preferredSeriesSgbd, functionalSgbd, answers.values.toList(), options, onProgress)
        } else if (options.sequentialFallback) {
            note("no functional answer (${lastError ?: "no vehicle SGBD"}): sequential identification")
            sequential(previous, preferredSeriesSgbd, onProgress)
        } else {
            null
        }
        if (vehicle == null || vehicle.ecus.isEmpty()) {
            return Result(null, lastError ?: "No ECU answered", log = log.toList())
        }
        return Result(vehicle, log = log.toList())
    }

    private fun parseAnswer(set: Map<String, io.github.jonathangtd.bimmerdata.ediabas.ResultData>): Answer? {
        val address = set.long("ID_SG_ADR")?.toInt() ?: return null
        if (address !in 0..0xFF) return null
        val name = set.str("ECU_GROBNAME")
        if (name != null && name.startsWith("VIRTSG", ignoreCase = true)) return null // DetectVehicleBmwBase.IsValidEcuName
        val variant = set.long("ID_VAR_INDEX")?.let { v ->
            val hi = ((v shr 8) and 0xFF).toInt()
            val lo = (v and 0xFF).toInt()
            if (hi in 0x20..0x7E && lo in 0x20..0x7E) "${hi.toChar()}${lo.toChar()}" else null
        }
        return Answer(
            address = address,
            grobname = name?.takeIf { it.isNotEmpty() },
            ecuName = set.str("ECU_NAME")?.takeIf { it.isNotEmpty() },
            ecuSgbd = set.str("ECU_SGBD")?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT),
            ecuGroup = set.str("ECU_GRUPPE")?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT),
            diagIndex = set.long("ID_DIAG_INDEX")?.toInt(),
            variantIndex = variant,
            ident = set.identValues(),
        )
    }

    private suspend fun fromFunctional(
        previous: Vehicle?,
        preferredSeriesSgbd: String?,
        functionalSgbd: String,
        answers: List<Answer>,
        options: Options,
        onProgress: (Progress) -> Unit,
    ): Vehicle {
        // 2. series
        val votes = HashMap<String, Double>()
        for (a in answers) {
            val series = tables.assignment(a.address, a.diagIndex, a.variantIndex)?.series?.let { listOf(it) }
                ?: a.ecuSgbd?.let { s -> tables.assignments.filter { it.sgbd == s }.map { it.series }.distinct() }
                ?: emptyList()
            val families = series.mapNotNull { VehicleTables.vehicleSgbdForSeries(it) }.distinct()
            families.forEach { f -> votes[f] = (votes[f] ?: 0.0) + 1.0 / families.size }
        }
        val voted = votes.entries.sortedByDescending { it.value }.firstOrNull()?.key?.takeIf { it in tables.vehicleSgbds }
        val seriesSgbd = preferredSeriesSgbd?.lowercase(Locale.ROOT)?.takeIf { it in tables.vehicleSgbds } ?: voted ?: functionalSgbd
        note("series votes: ${votes.entries.joinToString { "${it.key}=%.1f".format(Locale.ROOT, it.value) }} -> $seriesSgbd")
        val names = tables.grobnames(seriesSgbd)

        // 3. VIN (short from the functional job, full from the CAS below)
        onProgress(Progress(Step.Vin))
        var vinShort: String? = null
        if (tables.hasJob(seriesSgbd, "C_FG_LESEN_FUNKTIONAL")) {
            val out = runner.run(seriesSgbd, "C_FG_LESEN_FUNKTIONAL").orCancel()
            vinShort = out.sets.mapNotNull { s -> s.str("FG_NR")?.uppercase(Locale.ROOT)?.takeIf { ShortVin.matches(it) } }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            note("C_FG_LESEN_FUNKTIONAL: ${vinShort ?: out.error ?: "no VIN"}")
        }

        // 4. variants through the group files
        val ecus = ArrayList<VehicleEcu>()
        answers.sortedBy { it.address }.forEachIndexed { i, a ->
            val group = a.ecuGroup?.takeIf { tables.groupExists(it) }
                ?: tables.groupsForAddress(a.address).firstOrNull { tables.groupExists(it) }
            onProgress(Progress(Step.Variants, i, answers.size, names[a.address] ?: a.grobname))
            var sgbd: String? = null
            if (group != null) {
                val res = runner.resolveGroup(group).orCancel()
                sgbd = res.variant
                if (res.variant == null) note("${hex(a.address)} $group: ${res.error}")
            }
            if (sgbd == null || !tables.prgExists(sgbd)) sgbd = a.ecuSgbd ?: sgbd
            ecus += VehicleEcu(
                address = a.address,
                name = names[a.address] ?: a.grobname ?: group?.removePrefix("d_")?.uppercase(Locale.ROOT) ?: hex(a.address),
                description = a.ecuName ?: sgbd?.let { tables.descriptionOf(it) },
                group = group ?: a.ecuGroup,
                sgbd = sgbd,
                sgbdAvailable = sgbd != null && tables.prgExists(sgbd),
                ident = a.ident,
            )
        }
        onProgress(Progress(Step.Variants, answers.size, answers.size))

        val vin = readFullVin(ecus, vinShort)
        if (vinShort == null && vin != null) vinShort = vin.takeLast(7)

        // 5. bus topology
        var withBus: List<VehicleEcu> = ecus
        var topology = false
        if (options.probeBuses) {
            val buses = tables.functionalBuses(seriesSgbd).keys.filter { it in BusProbeOrder }.sortedBy { BusProbeOrder.indexOf(it) }
            val busOf = HashMap<Int, String>()
            buses.forEachIndexed { i, bus ->
                onProgress(Progress(Step.Buses, i, buses.size, bus))
                val out = runner.run(seriesSgbd, "IDENT_FUNKTIONAL", bus).orCancel()
                out.sets.mapNotNull { it.long("ID_SG_ADR")?.toInt() }.forEach { busOf.putIfAbsent(it, bus) }
            }
            val covered = ecus.count { busOf.containsKey(it.address) }
            topology = ecus.isNotEmpty() && covered >= ecus.size * 3 / 4
            note("bus probe: $covered/${ecus.size} ECUs on ${buses.joinToString()} -> ${if (topology) "used" else "ignored"}")
            if (topology) withBus = ecus.map { it.copy(bus = busOf[it.address]) }
        }

        return buildVehicle(previous, vin, vinShort, seriesSgbd, withBus, ScanMethod.Functional, topology)
    }

    /** CAS STATUS_FAHRGESTELLNUMMER: the 17-character VIN, if it matches the short one. */
    private suspend fun readFullVin(ecus: List<VehicleEcu>, vinShort: String?): String? {
        val cas = ecus.firstOrNull { it.group == "d_cas" && it.sgbdAvailable && it.sgbd != null && tables.hasJob(it.sgbd, "STATUS_FAHRGESTELLNUMMER") }
            ?: return null
        val out = runner.run(cas.sgbd!!, "STATUS_FAHRGESTELLNUMMER").orCancel()
        val vin = out.sets.firstNotNullOfOrNull { s -> s.str("FGNUMMER")?.uppercase(Locale.ROOT)?.takeIf { FullVin.matches(it) } }
        note("STATUS_FAHRGESTELLNUMMER(${cas.sgbd}): ${vin ?: out.error ?: out.jobStatus}")
        return vin?.takeIf { vinShort == null || it.endsWith(vinShort) }
    }

    private suspend fun sequential(previous: Vehicle?, preferredSeriesSgbd: String?, onProgress: (Progress) -> Unit): Vehicle? {
        val candidates = tables.sequentialCandidates()
        val ecus = ArrayList<VehicleEcu>()
        candidates.forEachIndexed { i, (address, group) ->
            onProgress(Progress(Step.Sequential, i, candidates.size, group.uppercase(Locale.ROOT)))
            val res = runner.resolveGroup(group).orCancel()
            val sgbd = res.variant ?: return@forEachIndexed
            var ident = emptyList<NamedValue>()
            var realAddress = address
            if (tables.hasJob(sgbd, "IDENT")) {
                val out = runner.run(sgbd, "IDENT").orCancel()
                val set = out.sets.firstOrNull { it.containsKey("ID_BMW_NR") }
                ident = set?.identValues() ?: emptyList()
                set?.long("ID_SG_ADR")?.toInt()?.takeIf { it in 0..0xFF }?.let { realAddress = it }
            }
            if (ecus.any { it.address == realAddress }) return@forEachIndexed
            ecus += VehicleEcu(
                address = realAddress,
                name = group.removePrefix("d_").uppercase(Locale.ROOT),
                description = tables.descriptionOf(sgbd),
                group = group,
                sgbd = sgbd,
                sgbdAvailable = tables.prgExists(sgbd),
                ident = ident,
            )
        }
        onProgress(Progress(Step.Sequential, candidates.size, candidates.size))
        note("sequential: ${ecus.size} of ${candidates.size} group files answered")
        if (ecus.isEmpty()) return null
        val votes = ecus.flatMap { e -> tables.assignments.filter { it.sgbd == e.sgbd }.mapNotNull { VehicleTables.vehicleSgbdForSeries(it.series) }.distinct() }
            .groupingBy { it }.eachCount()
        val seriesSgbd = preferredSeriesSgbd ?: votes.maxByOrNull { it.value }?.key
        val named = seriesSgbd?.let { tables.grobnames(it) } ?: emptyMap()
        val renamed = ecus.map { e -> named[e.address]?.let { e.copy(name = it) } ?: e }
        val vin = readFullVin(renamed, null)
        return buildVehicle(previous, vin, vin?.takeLast(7), seriesSgbd, renamed, ScanMethod.Sequential, false)
    }

    private fun buildVehicle(
        previous: Vehicle?,
        vin: String?,
        vinShort: String?,
        seriesSgbd: String?,
        ecus: List<VehicleEcu>,
        method: ScanMethod,
        topology: Boolean,
    ): Vehicle {
        val samePrevious = previous?.takeIf { p -> vinShort == null || p.vinShort == null || p.vinShort == vinShort }
        val family = seriesSgbd?.let { VehicleTables.FamilyLabels[it] ?: it.uppercase(Locale.ROOT) }
        val id = vinShort ?: samePrevious?.id ?: "car-${java.lang.Long.toHexString(clock())}"
        return Vehicle(
            id = id,
            label = samePrevious?.label ?: listOfNotNull(family, vinShort).joinToString(" ").ifEmpty { "BMW" },
            vin = vin ?: samePrevious?.vin,
            vinShort = vinShort ?: samePrevious?.vinShort,
            series = family,
            seriesSgbd = seriesSgbd,
            seriesByUser = samePrevious?.seriesByUser == true && samePrevious.seriesSgbd == seriesSgbd,
            ecus = ecus.sortedBy { it.address },
            scannedAtMs = clock(),
            scanMethod = method,
            busTopology = topology,
        )
    }

    companion object {
        /** Buses probed for the topology, in priority order (an ECU answering on several keeps the first). */
        val BusProbeOrder = listOf("PT-CAN", "SI", "MOST", "K-CAN")

        private val ShortVin = Regex("^[A-Z0-9]{7}$")
        private val FullVin = Regex("^[A-HJ-NPR-Z0-9]{17}$")

        fun hex(address: Int) = String.format("0x%02X", address)
    }
}
