/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.PrgFile
import java.util.Locale

/**
 * [MeasurementSource.JobResult] measurements of an SGBD that has no (or not only a) MESSWERTETAB:
 *
 * - **Job results** ([fromJobs]): every numeric or text result of every read-only job
 *   ([ReadOnlyJobs]) that needs no argument, or whose arguments are all documented as optional
 *   ("optional", "wenn keine Eingabe", "kein Argument"...). Typical: `STATUS_GETRIEBETEMPERATUR`
 *   → `STAT_GETRIEBETEMPERATUR_WERT` with its unit in `STAT_GETRIEBETEMPERATUR_EINH`. Results
 *   that are not values are skipped: `JOB_STATUS`, `_*` (raw telegrams), `*_EINH` (unit texts),
 *   binary results.
 * - **SG_FUNKTIONEN** ([fromSgFunktionen], UDS SGBDs of F/G-series ECUs): rows whose SERVICE
 *   includes 0x22 (ReadDataByIdentifier), read with `STATUS_LESEN` and arguments `ARG;<name>`;
 *   a row with a RES_TABELLE gives one measurement per result of that table.
 *
 * Ids: `sgbd/JOB/RESULT`, or `sgbd/JOB(ARGS)/RESULT` with arguments, e.g.
 * "gs19d/STATUS_GETRIEBETEMPERATUR/STAT_GETRIEBETEMPERATUR_WERT".
 */
object GenericMeasurements {

    /** Arguments that may be left empty, from their comments. */
    private val optionalArg = Regex(
        "optional|keine\\s+eingabe|kein(en)?\\s+argument|ohne\\s+argument|nicht\\s+verwendet|nicht\\s+angegeben|wenn\\s+leer|if\\s+empty",
        RegexOption.IGNORE_CASE,
    )

    private val numericTypes = Regex("^(unsigned |signed )?(char|int|integer|long|word|real|double|float)$", RegexOption.IGNORE_CASE)

    fun jobId(sgbd: String, job: String, args: String, result: String): String =
        "${EdiabasMeasurementCatalog.normalize(sgbd)}/$job${if (args.isNotEmpty()) "($args)" else ""}/$result"

    /** A job the catalog may offer: read-only, and runnable without arguments. */
    fun isUsableJob(job: JobDescription): Boolean =
        ReadOnlyJobs.isReadOnly(job) && job.args.all { a -> a.comments.any { optionalArg.containsMatchIn(it) } }

    /** Results of [job] that are values (see the class comment). */
    fun valueResults(job: JobDescription): List<ItemDescription> = job.results.filter { r ->
        val name = r.name.uppercase(Locale.ROOT)
        name.isNotEmpty() && !name.startsWith("_") && name != "JOB_STATUS" && !name.endsWith("_EINH") &&
            !r.isBinary && (r.isText || numericTypes.matches(r.type.trim()))
    }

    /** Number of job-result measurements of [desc] (cheap: no label / unit parsing), for folder scans. */
    fun countJobResults(desc: SgbdDescription): Int =
        desc.jobs.sumOf { j -> if (isUsableJob(j)) valueResults(j).size else 0 }

    /**
     * Job-result measurements of [sgbd]; results named in [exclude] (upper case, e.g. those of the
     * MESSWERTETAB) are left out. STATUS jobs first, then IDENT and other reads, in file order.
     */
    fun fromJobs(sgbd: String, desc: SgbdDescription, exclude: Set<String> = emptySet()): List<Measurement> {
        val key = EdiabasMeasurementCatalog.normalize(sgbd)
        val jobs = desc.jobs.filter { isUsableJob(it) }
            .sortedBy { if (it.name.uppercase(Locale.ROOT).startsWith("STATUS")) 0 else 1 }
        val out = ArrayList<Measurement>()
        val seen = HashSet<String>()
        for (job in jobs) {
            val byName = job.results.associateBy { it.name.uppercase(Locale.ROOT) }
            val jobTitle = MeasurementText.jobTitle(job.comments)
            for (r in valueResults(job)) {
                val upper = r.name.uppercase(Locale.ROOT)
                if (upper in exclude) continue
                val id = jobId(key, job.name, "", r.name)
                if (!seen.add(id)) continue
                out += if (r.isText) {
                    Measurement(
                        id = id, sgbd = key, label = MeasurementText.label(r.name, r.comments), unit = "",
                        source = MeasurementSource.JobResult(job.name, "", r.name), kind = ValueKind.Text, description = jobTitle,
                    )
                } else {
                    val sibling = byName[upper.removeSuffix("_WERT") + "_EINH"] ?: byName[upper + "_EINH"]
                    val unit = MeasurementText.unitFromComments(r.comments, sibling?.comments.orEmpty())
                    val range = MeasurementText.rangeFromComments(r.comments) ?: MeasurementText.rangeForUnit(unit, r.name)
                    Measurement(
                        id = id, sgbd = key, label = MeasurementText.label(r.name, r.comments), unit = unit,
                        source = MeasurementSource.JobResult(job.name, "", r.name),
                        min = range?.first, max = range?.second, description = jobTitle,
                    )
                }
            }
        }
        return out
    }

    const val UdsStatusJob = "STATUS_LESEN"
    const val SgFunktionenTable = "SG_FUNKTIONEN"

    private fun placeholder(s: String?): Boolean = s == null || s.isBlank() || s.trim() == "-"

    /** SG_FUNKTIONEN rows readable with STATUS_LESEN (see the class comment); results in [exclude] are left out. */
    fun fromSgFunktionen(sgbd: String, prg: PrgFile, exclude: Set<String> = emptySet()): List<Measurement> {
        if (prg.jobs.none { it.name.equals(UdsStatusJob, ignoreCase = true) }) return emptyList()
        val table = prg.table(SgFunktionenTable) ?: return emptyList()
        val cArg = table.column("ARG") ?: return emptyList()
        val cService = table.column("SERVICE") ?: return emptyList()
        val cRes = table.column("RESULTNAME")
        val cInfo = table.column("INFO")
        val cUnit = table.column("EINHEIT")
        val cLabel = table.column("LABEL")
        val cType = table.column("DATENTYP")
        val cResTab = table.column("RES_TABELLE")
        val key = EdiabasMeasurementCatalog.normalize(sgbd)
        val out = ArrayList<Measurement>()
        val seen = HashSet<String>()
        val resTables = HashMap<String, PrgFile.TableData?>()

        fun add(arg: String, result: String, info: String, label: String, unit: String, type: String) {
            if (placeholder(result) || result.uppercase(Locale.ROOT) in exclude) return
            val t = type.trim().lowercase(Locale.ROOT)
            if (t.startsWith("data") || t == "binary") return
            val args = "ARG;$arg"
            val id = jobId(key, UdsStatusJob, args, result)
            if (!seen.add(id)) return
            val text = t.startsWith("string")
            val u = if (text) "" else MeasurementText.tableUnit(unit)
            val range = if (text) null else MeasurementText.rangeForUnit(u, result)
            out += Measurement(
                id = id, sgbd = key,
                label = MeasurementText.label(result, listOfNotNull(label.takeUnless { placeholder(it) }, info.takeUnless { placeholder(it) })),
                unit = u, source = MeasurementSource.JobResult(UdsStatusJob, args, result),
                min = range?.first, max = range?.second,
                kind = if (text) ValueKind.Text else ValueKind.Number,
                description = info.takeUnless { placeholder(it) }.orEmpty(),
            )
        }

        for (row in table.rows) {
            val services = row[cService].uppercase(Locale.ROOT).split(';', ',', ' ', '/').map { it.trim().removePrefix("0X").removePrefix("$") }
            if ("22" !in services) continue
            val arg = row[cArg].trim()
            if (placeholder(arg)) continue
            val info = cInfo?.let { row[it] }.orEmpty()
            val resTab = cResTab?.let { row[it] }?.takeUnless { placeholder(it) }
            if (resTab != null) {
                val rt = resTables.getOrPut(resTab.uppercase(Locale.ROOT)) { prg.table(resTab) } ?: continue
                val rName = rt.column("RESULTNAME") ?: continue
                val rUnit = rt.column("EINHEIT")
                val rType = rt.column("DATENTYP")
                val rInfo = rt.column("INFO")
                for (r in rt.rows) {
                    add(arg, r[rName].trim(), rInfo?.let { r[it] }.orEmpty().ifBlank { info }, "", rUnit?.let { r[it] }.orEmpty(), rType?.let { r[it] }.orEmpty())
                }
            } else {
                add(arg, cRes?.let { row[it] }.orEmpty().trim(), info, cLabel?.let { row[it] }.orEmpty(), cUnit?.let { row[it] }.orEmpty(), cType?.let { row[it] }.orEmpty())
            }
        }
        return out
    }
}
