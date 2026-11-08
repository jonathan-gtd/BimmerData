/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import io.github.jonathangtd.bimmerdata.ediabas.PrgFile
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline knowledge taken from the user's ECU folder (no ECU traffic, files read once):
 *
 * - the vehicle SGBDs (E89X, E60, E65, E70, R56) and their tables GROBNAME (address -> short
 *   name) and FUNKTIONALEADRESSE (bus -> functional address),
 * - T_GRTB.PRG ZUORDNUNGSTABELLE: for each "address variant-index diagnosis-index" key the variant
 *   SGBD, its group file, the series it was introduced with (BAUREIHE) and a long name. This is the
 *   table IDENT_FUNKTIONAL itself uses for ECU_SGBD / ECU_GRUPPE / ECU_NAME.
 */
class VehicleTables(val ecuDir: File) {

    /** One row of T_GRTB ZUORDNUNGSTABELLE. */
    data class Assignment(
        val address: Int,
        /** Variant index (2 characters) or null for "----" (any). */
        val variantIndex: String?,
        val diagIndex: Int,
        val sgbd: String,
        val group: String,
        val series: String,
        val description: String,
    )

    private val files: Map<String, File> by lazy {
        ecuDir.listFiles()?.filter { it.isFile }?.associateBy { it.name.lowercase(Locale.ROOT) } ?: emptyMap()
    }

    private val prgCache = ConcurrentHashMap<String, PrgFile>()

    /** The object file [name] ("e89x.prg", "d_motor.grp"), null if missing or unreadable. */
    fun file(name: String): PrgFile? {
        val key = name.lowercase(Locale.ROOT)
        prgCache[key]?.let { return it }
        val f = files[key] ?: return null
        val prg = runCatching { PrgFile.open(f) }.getOrNull()?.takeIf { it.isObjectFile } ?: return null
        prgCache[key] = prg
        return prg
    }

    fun prgExists(sgbd: String): Boolean = files.containsKey(sgbd.lowercase(Locale.ROOT) + ".prg")

    fun groupExists(group: String): Boolean = files.containsKey(group.lowercase(Locale.ROOT) + ".grp")

    /** True if [sgbd] (a .prg) has [job]. */
    fun hasJob(sgbd: String, job: String): Boolean =
        file("${sgbd.lowercase(Locale.ROOT)}.prg")?.jobs?.any { it.name.equals(job, ignoreCase = true) } == true

    /** Vehicle SGBDs present in the folder that support the functional identification, in [KnownVehicleSgbds] order. */
    val vehicleSgbds: List<String> by lazy {
        KnownVehicleSgbds.filter { hasJob(it, "IDENT_FUNKTIONAL") }
    }

    private val grobnameCache = ConcurrentHashMap<String, Map<Int, String>>()

    /** Address -> short name (GROBNAME table of a vehicle SGBD). */
    fun grobnames(vehicleSgbd: String): Map<Int, String> = grobnameCache.getOrPut(vehicleSgbd.lowercase(Locale.ROOT)) {
        val table = file("${vehicleSgbd.lowercase(Locale.ROOT)}.prg")?.table("GROBNAME") ?: return@getOrPut emptyMap()
        val adr = table.column("ADR") ?: return@getOrPut emptyMap()
        val name = table.column("GROBNAME") ?: return@getOrPut emptyMap()
        table.rows.mapNotNull { row -> parseHex(row[adr])?.let { it to row[name] } }
            .filter { (_, n) -> n.isNotBlank() && !n.startsWith("?") }
            .toMap()
    }

    /** Bus name -> functional address (FUNKTIONALEADRESSE), buses only (no ALL / CBS / PERSONAL...). */
    fun functionalBuses(vehicleSgbd: String): Map<String, Int> {
        val table = file("${vehicleSgbd.lowercase(Locale.ROOT)}.prg")?.table("FUNKTIONALEADRESSE") ?: return emptyMap()
        val nr = table.column("NR") ?: return emptyMap()
        val name = table.column("F_ADR") ?: return emptyMap()
        return table.rows.mapNotNull { row -> parseHex(row[nr])?.let { row[name] to it } }
            .filter { (n, _) -> n in BusNames }
            .toMap()
    }

    /** T_GRTB ZUORDNUNGSTABELLE rows (LIN sub-addressed rows excluded). */
    val assignments: List<Assignment> by lazy {
        val table = file("t_grtb.prg")?.table("ZUORDNUNGSTABELLE") ?: return@lazy emptyList()
        table.records().mapNotNull { rec ->
            val parts = rec["ADR_VAR_DIAG"]?.trim()?.split(Regex("\\s+")) ?: return@mapNotNull null
            if (parts.size != 3) return@mapNotNull null
            val address = parseHex(parts[0]) ?: return@mapNotNull null
            val diag = parts[2].toIntOrNull(16) ?: return@mapNotNull null
            val v = parts[1]
            val variant = if (v.length == 4 && v[0] == '.' && v[3] == '.') v.substring(1, 3) else null
            Assignment(
                address, variant, diag,
                sgbd = rec["SGBD"]?.lowercase(Locale.ROOT) ?: return@mapNotNull null,
                group = rec["GRUPPE"]?.lowercase(Locale.ROOT) ?: return@mapNotNull null,
                series = rec["BAUREIHE"] ?: "",
                description = rec["STEUERGERAET"] ?: "",
            )
        }
    }

    /** The row IDENT_FUNKTIONAL would match for this identification, null if none. */
    fun assignment(address: Int, diagIndex: Int?, variantIndex: String?): Assignment? {
        val rows = assignments.filter { it.address == address && it.diagIndex == diagIndex }
        return rows.firstOrNull { it.variantIndex != null && it.variantIndex == variantIndex }
            ?: rows.firstOrNull { it.variantIndex == null }
    }

    /** Group files known for [address] (most frequent first), from T_GRTB. */
    fun groupsForAddress(address: Int): List<String> =
        assignments.filter { it.address == address }.groupingBy { it.group }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }

    /** Long name of a variant from T_GRTB, if listed. */
    fun descriptionOf(sgbd: String): String? =
        assignments.firstOrNull { it.sgbd.equals(sgbd, ignoreCase = true) }?.description?.takeIf { it.isNotBlank() }

    /**
     * (address, group) pairs to try one after the other when no functional identification is
     * possible: every group file once, with the address it is listed for most often (a group
     * file talks to one address, e.g. D_SIM to 0x01 even if T_GRTB also lists it elsewhere).
     */
    fun sequentialCandidates(): List<Pair<Int, String>> =
        assignments.groupBy { it.group }
            .filterKeys { groupExists(it) }
            .map { (group, rows) -> rows.groupingBy { it.address }.eachCount().maxBy { it.value }.key to group }
            .sortedBy { it.first }

    companion object {
        /** Vehicle (series) SGBDs of the E-series / R5x generation with functional jobs. */
        val KnownVehicleSgbds = listOf("e89x", "e60", "e65", "e70", "r56")

        /** FUNKTIONALEADRESSE entries that are buses (ECU topology). */
        val BusNames = setOf("PT-CAN", "K-CAN", "SI", "MOST", "LIN", "VD-FLEXRAY")

        /** Family label per vehicle SGBD. */
        val FamilyLabels = mapOf("e89x" to "E8x/E9x", "e60" to "E6x", "e65" to "E65", "e70" to "E7x", "r56" to "R5x")

        /** Vehicle SGBD for a series name ("E90", "E87", "E63", "R56"...), null if none applies. */
        fun vehicleSgbdForSeries(series: String): String? {
            val s = series.uppercase(Locale.ROOT).trim()
            val m = Regex("^([ER])(\\d{2})").find(s) ?: return null
            val n = m.groupValues[2].toInt()
            return when (m.groupValues[1]) {
                "E" -> when (n) {
                    in 81..84, in 87..93 -> "e89x"
                    in 60..64 -> "e60"
                    in 65..68 -> "e65"
                    in 70..72 -> "e70"
                    else -> null
                }
                "R" -> if (n in 55..61) "r56" else null
                else -> null
            }
        }

        internal fun parseHex(s: String): Int? {
            val t = s.trim().removePrefix("0x").removePrefix("0X")
            if (t.isEmpty() || t.length > 4) return null
            return t.toIntOrNull(16)
        }
    }
}
