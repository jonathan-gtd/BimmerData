/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * [MeasurementCatalog] over an EDIABAS ECU folder (*.prg). Thread-safe; every call may do file
 * I/O, so call it off the main thread.
 *
 * - [sgbdsWithMeasurements] scans only the table directory of each .prg (a few hundred bytes per
 *   file, not the whole file). The result is kept in memory and, if [cacheFile] is given, on disk
 *   keyed by file name + size + modification time, so later app starts only re-check changed files.
 * - [measurements] loads the MESSWERTETAB of one SGBD on first use and keeps it.
 *
 * Measurement ids are "sgbd/0xNNNN" (lower-case SGBD name, upper-case hex id), e.g. "msv70/0x4402".
 */
class EdiabasMeasurementCatalog(
    val ecuDir: File,
    private val cacheFile: File? = null,
) : MeasurementCatalog {

    @Volatile private var sgbdList: List<String>? = null
    private val tabs = ConcurrentHashMap<String, MesswerteTab>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val scanLock = Any()

    override fun sgbdsWithMeasurements(): List<String> {
        sgbdList?.let { return it }
        synchronized(scanLock) {
            sgbdList?.let { return it }
            val list = scan()
            sgbdList = list
            return list
        }
    }

    override fun measurements(sgbd: String): List<Measurement> {
        val tab = table(sgbd) ?: return emptyList()
        return tab.rows.map { toMeasurement(tab.sgbd, it) }
    }

    /** One measurement by id ("msv70/0x4402"), null if unknown. */
    fun measurement(id: String): Measurement? {
        val (sgbd, num) = parseId(id) ?: return null
        val tab = table(sgbd) ?: return null
        return tab.row(num)?.let { toMeasurement(tab.sgbd, it) }
    }

    /** Parsed MESSWERTETAB of [sgbd] (name with or without extension, any case), null if none. */
    fun table(sgbd: String): MesswerteTab? {
        val key = normalize(sgbd)
        tabs[key]?.let { return it }
        if (key in missing) return null
        val file = sgbdFile(key)
        val tab = file?.let { runCatching { MesswerteTab.load(key, it) }.getOrNull() }
        if (tab == null) {
            missing.add(key)
            return null
        }
        tabs[key] = tab
        return tab
    }

    /** The .prg file of [sgbd] in [ecuDir], whatever its case on disk. */
    fun sgbdFile(sgbd: String): File? {
        val key = normalize(sgbd)
        val exact = File(ecuDir, "$key.prg")
        if (exact.isFile) return exact
        return ecuDir.listFiles()?.firstOrNull { it.isFile && it.name.equals("$key.prg", ignoreCase = true) }
    }

    /** Forgets everything (after a new ECU folder import). */
    fun invalidate() {
        synchronized(scanLock) {
            sgbdList = null
            tabs.clear()
            missing.clear()
        }
    }

    // ---------------------------------------------------------------- scan

    private class CacheEntry(val size: Long, val mtime: Long, val has: Boolean)

    private fun scan(): List<String> {
        val files = ecuDir.listFiles()?.filter { it.isFile && it.name.endsWith(".prg", ignoreCase = true) } ?: return emptyList()
        val cache = readCache()
        val result = ArrayList<String>()
        val newCache = LinkedHashMap<String, CacheEntry>()
        var changed = cache.size != files.size
        for (f in files.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            val name = f.name.lowercase(Locale.ROOT)
            val size = f.length()
            val mtime = f.lastModified()
            val cached = cache[name]
            val has = if (cached != null && cached.size == size && cached.mtime == mtime) {
                cached.has
            } else {
                changed = true
                MesswerteTab.TableName in PrgTableDirectory.tableNames(f)
            }
            newCache[name] = CacheEntry(size, mtime, has)
            if (has) result.add(name.removeSuffix(".prg"))
        }
        if (changed) writeCache(newCache)
        return result
    }

    private fun readCache(): Map<String, CacheEntry> {
        val file = cacheFile ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size != 4) null else p[0] to CacheEntry(p[1].toLong(), p[2].toLong(), p[3] == "1")
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeCache(entries: Map<String, CacheEntry>) {
        val file = cacheFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(entries.entries.joinToString("\n") { (n, e) -> "$n\t${e.size}\t${e.mtime}\t${if (e.has) 1 else 0}" })
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        fun normalize(sgbd: String): String = sgbd.trim().lowercase(Locale.ROOT).removeSuffix(".prg")

        fun measurementId(sgbd: String, id: Int): String = "${normalize(sgbd)}/0x%04X".format(Locale.ROOT, id)

        /** "msv70/0x4402" -> ("msv70", 0x4402). */
        fun parseId(id: String): Pair<String, Int>? {
            val slash = id.indexOf('/')
            if (slash <= 0) return null
            val num = MesswerteTab.parseInt(id.substring(slash + 1)) ?: return null
            return normalize(id.substring(0, slash)) to num
        }

        fun toMeasurement(sgbd: String, row: MesswertRow): Measurement {
            val (min, max) = plausibleRange(row)
            return Measurement(
                id = measurementId(sgbd, row.id),
                sgbd = normalize(sgbd),
                label = row.info.ifBlank { row.resultName },
                unit = row.unit,
                source = MeasurementSource.MeasurementTable(row.id, row.resultName),
                min = min,
                max = max,
            )
        }

        /**
         * Gauge range for well-known units only; everything else stays null (the UI picks one).
         * The data type range times the factor would be far too wide (e.g. -3276..3276 degC).
         *
         * | unit | range |
         * |---|---|
         * | degC | -40..150 |
         * | rpm | 0..7000 (gradients excluded) |
         * | % | -25..25 for lambda integrators / adaptations, else 0..100 |
         * | V | 0..16 |
         * | km/h | 0..260 |
         * | hPa | 0..1100 |
         * | kg/h | 0..800 |
         * | lambda (no unit, LAMBDA_*) | 0.7..1.3 |
         */
        fun plausibleRange(row: MesswertRow): Pair<Double?, Double?> {
            if (row.conversionTable != null) return null to null
            val name = row.resultName.uppercase(Locale.ROOT)
            return when (row.unit) {
                "°C" -> -40.0 to 150.0
                "rpm" -> if ("GRADIENT" in name) null to null else 0.0 to 7000.0
                "%" -> if ("INTEGRATOR" in name || "ADAPTION" in name || "GEMISCH" in name) -25.0 to 25.0 else 0.0 to 100.0
                "V" -> 0.0 to 16.0
                "km/h" -> 0.0 to 260.0
                "hPa" -> 0.0 to 1100.0
                "kg/h" -> 0.0 to 800.0
                "" -> if (name.startsWith("STAT_LAMBDA_BANK")) 0.7 to 1.3 else null to null
                else -> null to null
            }
        }
    }
}
