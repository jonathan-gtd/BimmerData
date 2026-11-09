/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

/**
 * What the engine reads for a screen: every value of the active profile (all its phone and Android
 * Auto pages), the page shown first.
 *
 * Reading the whole profile keeps every value current: switching page shows values at once,
 * their curves have no hole, and the CSV recording and the Telegraf export get every value of the
 * profile whatever the page shown. It costs read rate: each value of an ECU's dynamic block costs
 * that ECU about 1 ms per cycle, another ECU one more telegram per cycle and a job about 100 ms.
 * A profile too slow to read is split into several profiles by its owner.
 *
 * One exception: when an ECU of the profile has more MESSWERTETAB values than one dynamic block
 * holds ([MesswertBlockReader.MaxIdsPerDefinition]), only the page shown is read, as reading two
 * blocks would redefine the block at every cycle.
 */
object ReadScope {
    /** The measurements to read: [profile] (the [visible] ones first), or [visible] (see above). */
    fun select(
        profile: List<Measurement>,
        visible: List<Measurement>,
        maxPerBlock: Int = MesswertBlockReader.MaxIdsPerDefinition,
    ): List<Measurement> {
        val visibleIds = visible.mapTo(HashSet()) { it.id }
        val extra = profile.filter { it.id !in visibleIds }.distinctBy { it.id }
        if (extra.isEmpty()) return visible
        val all = visible + extra
        val perEcu = all.filter { it.source is MeasurementSource.MeasurementTable }.distinctBy { it.id }.groupingBy { it.sgbd }.eachCount()
        if (perEcu.values.any { it > maxPerBlock }) return visible
        return all
    }
}
