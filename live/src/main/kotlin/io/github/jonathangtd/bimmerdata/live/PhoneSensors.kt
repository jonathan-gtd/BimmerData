/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

/**
 * Measurements of the phone itself, next to the ECU ones. Today: the GPS position (Settings ›
 * GPS position, off by default). They look like the measurements of an "ECU" named [GPS]
 * (ids `gps/latitude`, `gps/speed`...), so cards, the CSV recording, the Telegraf export
 * (tag `ecu=gps`) and the REST API handle them like any other value. The acquisition engine
 * never sees them: their samples come from the phone's location provider.
 */
object PhoneSensors {
    /** Pseudo SGBD of the GPS measurements. */
    const val GPS = "gps"

    /** The GPS values, with their measurement id `gps/<key>`. */
    enum class GpsField(val key: String, val defaultLabel: String, val unit: String, val min: Double?, val max: Double?) {
        Latitude("latitude", "GPS latitude", "°", -90.0, 90.0),
        Longitude("longitude", "GPS longitude", "°", -180.0, 180.0),
        Altitude("altitude", "GPS altitude", "m", -100.0, 3000.0),
        Speed("speed", "GPS speed", "km/h", 0.0, 260.0),
        Bearing("bearing", "GPS heading", "°", 0.0, 360.0),
        Accuracy("accuracy", "GPS accuracy", "m", 0.0, 50.0),
        ;

        val id: String get() = "$GPS/$key"
    }

    /** The GPS measurements, labelled by [label] (the app passes translated texts). */
    fun gpsMeasurements(label: (GpsField) -> String = { it.defaultLabel }): List<Measurement> = GpsField.entries.map { f ->
        Measurement(
            id = f.id, sgbd = GPS, label = label(f), unit = f.unit,
            source = MeasurementSource.Sensor(GPS, f.key), min = f.min, max = f.max,
            description = "Phone GPS (Settings › GPS position)",
        )
    }

    fun isSensor(m: Measurement): Boolean = m.source is MeasurementSource.Sensor

    /** True for an id of a phone sensor measurement (`gps/...`). */
    fun isSensorId(id: String): Boolean = id.startsWith("$GPS/")

    /** The GPS measurement of [id], or null. */
    fun measurement(id: String): Measurement? = GpsField.entries.firstOrNull { it.id == id }?.let { f -> gpsMeasurements().first { it.id == f.id } }
}

/**
 * One position fix of the phone. [timeMs] is the wall-clock time (epoch ms) of the fix, on the
 * same clock as the ECU samples. Optional values are null when the provider has none.
 */
data class GpsFix(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    /** Ground speed in m/s. */
    val speedMs: Double? = null,
    /** Heading in degrees, 0 = north, clockwise. */
    val bearingDeg: Double? = null,
    /** Horizontal accuracy (68 %), in metres. */
    val accuracyM: Double? = null,
) {
    /** Samples of the GPS measurements (speed in km/h); missing values give no sample. */
    fun toSamples(): Map<String, Sample> {
        val out = LinkedHashMap<String, Sample>()
        fun put(f: PhoneSensors.GpsField, v: Double?) {
            if (v != null && v.isFinite()) out[f.id] = Sample(v, null, timeMs)
        }
        put(PhoneSensors.GpsField.Latitude, latitude)
        put(PhoneSensors.GpsField.Longitude, longitude)
        put(PhoneSensors.GpsField.Altitude, altitudeM)
        put(PhoneSensors.GpsField.Speed, speedMs?.let { it * 3.6 })
        put(PhoneSensors.GpsField.Bearing, bearingDeg)
        put(PhoneSensors.GpsField.Accuracy, accuracyM)
        return out
    }

    companion object {
        /**
         * Wall-clock time of a fix from its elapsed-realtime stamp: the fix happened
         * `nowElapsed - fixElapsed` ago. Keeps GPS samples on the same clock as the ECU samples
         * (the GPS's own UTC time may differ from the phone clock by a second or so).
         */
        fun wallTime(nowWallMs: Long, nowElapsedNanos: Long, fixElapsedNanos: Long): Long =
            nowWallMs - ((nowElapsedNanos - fixElapsedNanos).coerceAtLeast(0) / 1_000_000L)
    }
}

/**
 * [MeasurementCatalog] with the phone's sensors added in front of the ECU measurements of [base]:
 * the picker shows a "gps" entry, and lookups of `gps/...` measurements work everywhere.
 */
class SensorCatalog(
    private val base: MeasurementCatalog,
    private val sensors: () -> List<Measurement> = { PhoneSensors.gpsMeasurements() },
    private val description: String = "Phone GPS",
) : MeasurementCatalog {
    override fun sgbdsWithMeasurements(): List<String> = listOf(PhoneSensors.GPS) + base.sgbdsWithMeasurements().filter { it != PhoneSensors.GPS }

    override fun measurements(sgbd: String): List<Measurement> =
        if (sgbd.equals(PhoneSensors.GPS, ignoreCase = true)) sensors() else base.measurements(sgbd)

    override fun sgbdInfos(): List<SgbdInfo> =
        listOf(SgbdInfo(PhoneSensors.GPS, description, sensors().size)) + base.sgbdInfos().filter { it.name != PhoneSensors.GPS }
}
