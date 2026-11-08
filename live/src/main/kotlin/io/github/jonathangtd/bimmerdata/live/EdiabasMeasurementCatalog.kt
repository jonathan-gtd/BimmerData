/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.PrgFile
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * [MeasurementCatalog] over an EDIABAS ECU folder (*.prg; group files .grp are not measurable
 * themselves). Thread-safe; every call may do file I/O, so call it off the main thread.
 *
 * Sources of measurements, per SGBD, in this order (a result already given by an earlier source
 * is not repeated):
 * 1. MESSWERTETAB rows ([MeasurementSource.MeasurementTable], fast path: one dynamic record);
 * 2. SG_FUNKTIONEN rows read with STATUS_LESEN (UDS SGBDs), see [GenericMeasurements];
 * 3. results of read-only jobs that need no argument, from the SGBD's job documentation (what the
 *    virtual jobs `_JOBS` / `_RESULTS` return), see [GenericMeasurements] and [ReadOnlyJobs].
 *
 * - [sgbdInfos] / [sgbdsWithMeasurements] scan the folder: per file only the table directory and
 *   the job documentation block are read (84 MB for a full 840 MB folder of 1251 SGBDs), on all
 *   CPU cores. The result is kept in memory and, if [cacheFile] is given, on disk (versioned,
 *   keyed by file name + size + modification time), so later app starts only re-read changed files.
 * - [measurements] builds the list of one SGBD on first use and keeps it; the whole file is only
 *   loaded when it has a MESSWERTETAB or SG_FUNKTIONEN.
 *
 * Measurement ids: "sgbd/0xNNNN" for MESSWERTETAB rows (lower-case SGBD name, upper-case hex id,
 * e.g. "msv70/0x4402"), "sgbd/JOB/RESULT" or "sgbd/JOB(ARGS)/RESULT" for job results.
 * The caches are dropped by themselves when [ecuDir] is replaced (its modification time changes,
 * as after an ECU folder import), or by [invalidate].
 */
class EdiabasMeasurementCatalog(
    val ecuDir: File,
    private val cacheFile: File? = null,
) : MeasurementCatalog {

    @Volatile private var infos: List<SgbdInfo>? = null
    private val tabs = ConcurrentHashMap<String, MesswerteTab>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val lists = ConcurrentHashMap<String, List<Measurement>>()
    private val byId = ConcurrentHashMap<String, Map<String, Measurement>>()
    private val files = ConcurrentHashMap<String, File>()
    private val scanLock = Any()

    /** Duration of the last folder scan and how many files had to be read (not from the cache). */
    @Volatile var lastScanMs: Long = 0
        private set
    @Volatile var lastScanParsed: Int = 0
        private set

    /** Folder identity when the caches were filled: a re-import replaces the folder (new mtime). */
    @Volatile private var dirStamp: Long = ecuDir.lastModified()

    private fun checkFolder() {
        val stamp = ecuDir.lastModified()
        if (stamp != dirStamp) {
            dirStamp = stamp
            invalidate()
        }
    }

    override fun sgbdsWithMeasurements(): List<String> = sgbdInfos().map { it.name }

    override fun sgbdInfos(): List<SgbdInfo> {
        checkFolder()
        infos?.let { return it }
        synchronized(scanLock) {
            infos?.let { return it }
            val list = scan()
            infos = list
            return list
        }
    }

    override fun measurements(sgbd: String): List<Measurement> {
        checkFolder()
        val key = normalize(sgbd)
        lists[key]?.let { return it }
        val list = build(key)
        lists[key] = list
        byId[key] = list.associateBy { it.id }
        return list
    }

    /** One measurement by id ("msv70/0x4402", "gs19d/STATUS_MOTORDREHZAHL/STAT_MOTORDREHZAHL_WERT"), null if unknown. */
    fun measurement(id: String): Measurement? {
        val slash = id.indexOf('/')
        if (slash <= 0) return null
        val key = normalize(id.substring(0, slash))
        measurements(key)
        byId[key]?.get(id)?.let { return it }
        // table ids are also accepted with other spellings of the number ("msv70/17410")
        val (sgbd, num) = parseId(id) ?: return null
        val tab = table(sgbd) ?: return null
        return tab.row(num)?.let { toMeasurement(tab.sgbd, it) }
    }

    /** Parsed MESSWERTETAB of [sgbd] (name with or without extension, any case), null if none. */
    fun table(sgbd: String): MesswerteTab? {
        checkFolder()
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
        files[key]?.let { if (it.isFile) return it }
        val exact = File(ecuDir, "$key.prg")
        val found = if (exact.isFile) exact else ecuDir.listFiles()?.firstOrNull { it.isFile && it.name.equals("$key.prg", ignoreCase = true) }
        if (found != null) files[key] = found
        return found
    }

    /** Forgets everything (after a new ECU folder import). */
    fun invalidate() {
        synchronized(scanLock) {
            infos = null
            tabs.clear()
            missing.clear()
            lists.clear()
            byId.clear()
            files.clear()
        }
    }

    // ---------------------------------------------------------------- one SGBD

    private fun build(key: String): List<Measurement> {
        val file = sgbdFile(key) ?: return emptyList()
        val tables = PrgTableDirectory.tables(file)
        val out = ArrayList<Measurement>()
        val seenResults = HashSet<String>()
        if (MesswerteTab.TableName in tables || GenericMeasurements.SgFunktionenTable in tables) {
            val prg = runCatching { PrgFile.open(file) }.getOrNull()
            if (prg != null) {
                if (MesswerteTab.TableName in tables) {
                    val tab = tabs[key] ?: runCatching { MesswerteTab.load(key, prg) }.getOrNull()?.also { tabs[key] = it }
                    tab?.rows?.forEach { row ->
                        out += toMeasurement(key, row)
                        seenResults += row.resultName.uppercase(Locale.ROOT)
                    }
                }
                if (GenericMeasurements.SgFunktionenTable in tables) {
                    val sgf = runCatching { GenericMeasurements.fromSgFunktionen(key, prg, seenResults) }.getOrDefault(emptyList())
                    out += sgf
                    sgf.forEach { seenResults += (it.source as MeasurementSource.JobResult).resultName.uppercase(Locale.ROOT) }
                }
            }
        }
        val desc = SgbdDescription.read(file)
        if (desc != null) out += GenericMeasurements.fromJobs(key, desc, seenResults)
        return out.distinctBy { it.id }
    }

    // ---------------------------------------------------------------- scan

    private class CacheEntry(val size: Long, val mtime: Long, val count: Int, val description: String)

    private fun scan(): List<SgbdInfo> {
        val t0 = System.nanoTime()
        val names = ecuDir.list()?.filter { it.endsWith(".prg", ignoreCase = true) }?.sortedBy { it.lowercase(Locale.ROOT) } ?: return emptyList()
        val files = names.map { File(ecuDir, it) }
        val cache = readCache()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "catalog-scan").apply { isDaemon = true } }
        val entries = arrayOfNulls<CacheEntry>(files.size)
        var parsed = 0
        try {
            // one stat per file (size, mtime), in parallel: slow on network / FUSE file systems
            val stats = files.map { f -> pool.submit<FileStamp?> { stamp(f) } }.map { it.get() }
            val toParse = ArrayList<Int>()
            files.indices.forEach { i ->
                val st = stats[i] ?: return@forEach
                val cached = cache?.get(names[i].lowercase(Locale.ROOT))
                if (cached != null && cached.size == st.size && cached.mtime == st.mtime) entries[i] = cached else toParse += i
            }
            val futures: List<Pair<Int, Future<CacheEntry>>> = toParse.map { i -> i to pool.submit<CacheEntry> { scanFile(files[i], stats[i]!!) } }
            for ((i, f) in futures) entries[i] = f.get()
            parsed = toParse.size
        } finally {
            pool.shutdown()
        }
        val known = files.indices.filter { entries[it] != null }
        if (cache == null || parsed > 0 || cache.size != known.size) {
            writeCache(known.associate { names[it].lowercase(Locale.ROOT) to entries[it]!! })
        }
        lastScanParsed = parsed
        lastScanMs = (System.nanoTime() - t0) / 1_000_000
        return known.mapNotNull { i ->
            val e = entries[i]!!
            if (e.count <= 0) null else SgbdInfo(normalize(names[i]), e.description, e.count)
        }
    }

    private class FileStamp(val size: Long, val mtime: Long)

    private fun stamp(f: File): FileStamp? = try {
        val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        if (a.isRegularFile) FileStamp(a.size(), a.lastModifiedTime().toMillis()) else null
    } catch (_: Exception) {
        null
    }

    /** Approximate measurement count (table rows + documented job results) and ECU text of one file. */
    private fun scanFile(f: File, st: FileStamp): CacheEntry = try {
        val tables = PrgTableDirectory.tables(f)
        val desc = SgbdDescription.read(f)
        // Only vehicle ECUs are offered in the card picker: a file without an IDENT job is a series
        // file (functional jobs), a software-key, table or tool file (00SWTKWS, T_GRTB, UTILITY...).
        val ecu = desc == null || desc.jobs.any { it.name.equals("IDENT", ignoreCase = true) }
        val count = if (!ecu) 0 else (tables[MesswerteTab.TableName] ?: 0) + (tables[GenericMeasurements.SgFunktionenTable] ?: 0) +
            (desc?.let { GenericMeasurements.countJobResults(it) } ?: 0)
        CacheEntry(st.size, st.mtime, count, desc?.summary.orEmpty())
    } catch (_: Exception) {
        CacheEntry(st.size, st.mtime, 0, "")
    }

    /** Entries of the disk cache; empty if there is none, null if it is in another format (rewritten). */
    private fun readCache(): Map<String, CacheEntry>? {
        val file = cacheFile ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching {
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.firstOrNull() != CacheHeader) return null
            lines.drop(1).mapNotNull { line ->
                val p = line.split('\t')
                if (p.size != 5) null else p[0] to CacheEntry(p[1].toLong(), p[2].toLong(), p[3].toInt(), p[4])
            }.toMap()
        }.getOrNull()
    }

    private fun writeCache(entries: Map<String, CacheEntry>) {
        val file = cacheFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(
                CacheHeader + "\n" + entries.entries.joinToString("\n") { (n, e) ->
                    "$n\t${e.size}\t${e.mtime}\t${e.count}\t${e.description.replace(Regex("[\\t\\r\\n]+"), " ")}"
                },
                Charsets.UTF_8,
            )
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        /** First line of the disk cache; bump the version when the scan result changes meaning. */
        const val CacheHeader = "# BimmerData measurement catalog v3"

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
                kind = if (row.conversionTable != null) ValueKind.Text else ValueKind.Number,
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
            if (row.unit == "") return if (name.startsWith("STAT_LAMBDA_BANK")) 0.7 to 1.3 else null to null
            val r = MeasurementText.rangeForUnit(row.unit, row.resultName) ?: return null to null
            return r.first to r.second
        }
    }
}
