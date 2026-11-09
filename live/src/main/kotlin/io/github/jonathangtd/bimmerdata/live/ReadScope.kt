/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

/**
 * What a screen asks the engine to read: every value of the active profile (all its phone and
 * Android Auto pages) when that costs no extra read cycle, else only what is on screen.
 *
 * Reading the whole profile keeps every value current: switching page shows values at once,
 * their curves have no hole, and the CSV recording and the Telegraf export get every value of the
 * profile whatever the page shown. It is free when the values of the other pages are MESSWERTETAB
 * values ([MeasurementSource.MeasurementTable]) of ECUs already read for the screen, and each ECU
 * keeps one dynamic block ([MesswertBlockReader.MaxIdsPerDefinition] values): the block is read
 * in one telegram either way, only a few bytes longer. It is not free when another page needs
 * another ECU (one more telegram per cycle), a job (about 100 ms each) or a second block for an
 * ECU (every read redefines): then only the screen is read, as before.
 *
 * Phone sensors (GPS) never cost a read cycle and are always included.
 */
object ReadScope {
    /** The measurements to read: [profile] when it is free (see above), else [visible]. */
    fun select(
        profile: List<Measurement>,
        visible: List<Measurement>,
        maxPerBlock: Int = MesswertBlockReader.MaxIdsPerDefinition,
    ): List<Measurement> {
        val visibleIds = visible.mapTo(HashSet()) { it.id }
        val extra = profile.filter { it.id !in visibleIds }.distinctBy { it.id }
        if (extra.isEmpty()) return visible
        val visibleEcus = visible.filter { it.source is MeasurementSource.MeasurementTable }.mapTo(HashSet()) { it.sgbd }
        val free = extra.all { m ->
            when (m.source) {
                is MeasurementSource.Sensor -> true
                is MeasurementSource.MeasurementTable -> m.sgbd in visibleEcus
                is MeasurementSource.JobResult -> false
            }
        }
        if (!free) return visible
        val all = visible + extra
        val perEcu = all.filter { it.source is MeasurementSource.MeasurementTable }.distinctBy { it.id }.groupingBy { it.sgbd }.eachCount()
        if (perEcu.values.any { it > maxPerBlock }) return visible
        return all
    }
}
