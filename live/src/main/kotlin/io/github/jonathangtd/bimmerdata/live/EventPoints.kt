/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

/**
 * The driver's events (« Événement » button: something was felt or heard) as readings of the
 * pseudo ECU "bimmerdata": id `bimmerdata/event`, value 1 at the time of the tap. The Telegraf
 * export sends them like any value (`ecu=bimmerdata`, `id=event`, `label=` the note or
 * "Event"), so a Grafana annotation query finds them; the CSV recording gets a marker row
 * ([csvMarker]). The label travels in the sample's text (the value field stays 1).
 */
object EventPoints {
    const val KEY = "event"
    const val ID = "${ReadStatsPoints.SGBD}/$KEY"
    const val DEFAULT_LABEL = "Event"

    fun isEvent(id: String): Boolean = id == ID

    /** The reading of one event at [timeMs]; [label] = its note, or the default label. */
    fun sample(timeMs: Long, label: String): Pair<String, Sample> = ID to Sample(1.0, label.ifBlank { DEFAULT_LABEL }, timeMs)

    /** Measurement of an event reading: the label is the one carried by the sample. */
    fun measurement(label: String?): Measurement =
        Measurement(ID, ReadStatsPoints.SGBD, label?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL, "", MeasurementSource.Sensor(ReadStatsPoints.SGBD, KEY), description = "BimmerData driver event")

    /** `marker` cell of the CSV recording: `event` or `event: <note>`. */
    fun csvMarker(note: String?): String = if (note.isNullOrBlank()) KEY else "$KEY: ${note.trim()}"
}
