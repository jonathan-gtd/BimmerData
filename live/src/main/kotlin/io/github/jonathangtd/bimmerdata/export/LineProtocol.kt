/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.Sample
import java.math.BigDecimal

/**
 * Text "line protocol" (one point per line) as accepted by Telegraf's `influxdb_v2_listener` and
 * `http_listener_v2` inputs. It is only the wire format: Telegraf writes the points to
 * TimescaleDB with its `postgresql` output, there is no InfluxDB anywhere.
 *
 * `measurement,tag=value,... field=value,... timestamp_ns`
 */
object LineProtocol {

    /** Measurement names: escape comma and space. */
    fun escapeMeasurement(s: String): String = escape(clean(s), ",  ")

    /** Tag keys, tag values and field keys: escape comma, equals sign and space. */
    fun escapeKey(s: String): String = escape(clean(s), ",= ")

    /** String field value, with its double quotes. */
    fun quote(s: String): String = "\"" + clean(s).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Float field value: plain decimal, no exponent (null for NaN / infinity). */
    fun float(v: Double): String? {
        if (!v.isFinite()) return null
        val s = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    /**
     * One line; tags with blank values are left out (not allowed by the format). Null when no
     * field is left.
     */
    fun line(measurement: String, tags: List<Pair<String, String>>, fields: List<Pair<String, String>>, timeNs: Long): String? {
        if (fields.isEmpty()) return null
        return buildString {
            append(escapeMeasurement(measurement))
            for ((k, v) in tags) {
                val value = v.trim()
                if (value.isEmpty()) continue
                // a tag value may not end with a backslash
                append(',').append(escapeKey(k)).append('=').append(escapeKey(value).trimEnd('\\').ifEmpty { "_" })
            }
            append(' ')
            fields.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(escapeKey(k)).append('=').append(v)
            }
            append(' ').append(timeNs)
        }
    }

    private fun clean(s: String) = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    private fun escape(s: String, special: String): String {
        if (s.none { it in special }) return s
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            if (c in special) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }
}

/**
 * Turns samples into line-protocol points:
 * `bimmerdata,car=<car>,ecu=msv70,id=0x4402,label=Oil temperature,unit=°C value=87.5 <ns>`.
 * Text results go into a `text` string field instead of `value`. Times are the sample times
 * (when the value was read in the car), in nanoseconds.
 */
class LineEncoder(private val car: String, private val measurementName: String = DEFAULT_MEASUREMENT) {

    fun encode(m: Measurement, s: Sample): String? {
        val fields = ArrayList<Pair<String, String>>(1)
        val v = s.value?.let(LineProtocol::float)
        if (v != null) fields += "value" to v else s.text?.takeIf { it.isNotBlank() }?.let { fields += "text" to LineProtocol.quote(it.take(MAX_TEXT)) }
        val key = m.id.substringAfter('/', m.id)
        return LineProtocol.line(
            measurementName,
            listOf("car" to car.ifBlank { "car" }, "ecu" to m.sgbd, "id" to key, "label" to m.label, "unit" to m.unit),
            fields,
            s.timestampMs * 1_000_000L,
        )
    }

    companion object {
        const val DEFAULT_MEASUREMENT = "bimmerdata"
        private const val MAX_TEXT = 200
    }
}

/**
 * Picks which samples to export: only new ones (sample time later than the last exported one of
 * that measurement), at most [pointsPerSecond] per measurement, and nothing older than
 * [notBeforeMs] (the last values kept on screen while disconnected are not exported again).
 */
class ExportSampler(var pointsPerSecond: Int, var notBeforeMs: Long = Long.MIN_VALUE) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun select(samples: Map<String, Sample>): List<Pair<String, Sample>> {
        val minGap = 1000L / pointsPerSecond.coerceIn(1, 50)
        val out = ArrayList<Pair<String, Sample>>()
        for ((id, s) in samples) {
            if (s.timestampMs < notBeforeMs) continue
            val prev = last[id]
            if (prev != null && (s.timestampMs <= prev || s.timestampMs - prev < minGap)) continue
            last[id] = s.timestampMs
            out += id to s
        }
        return out
    }

    @Synchronized
    fun reset(notBeforeMs: Long) {
        last.clear()
        this.notBeforeMs = notBeforeMs
    }
}
