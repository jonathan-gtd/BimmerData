/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import java.util.EnumMap

/**
 * A screen that shows live values (phone dashboard, Android Auto, the phone Graph tab), or the
 * REST API (measurements requested by scripts / AI agents).
 */
enum class MeasurementClient { Phone, Car, Graph, Api }

/**
 * Decides what the single acquisition engine reads when several screens show live values (the
 * phone dashboard and the Android Auto screen): what is on screen comes first in the read cycle,
 * and [LiveRepository] adds the rest of the active profile ([ReadScope]).
 *
 * - Clients in the foreground (phone dashboard on screen, BimmerData shown in Android Auto) get
 *   the union of their measurements, in [MeasurementClient] order, without duplicates.
 * - When no client is in the foreground, the background requests are kept (the phone dashboard
 *   keeps its page read with the screen off, as before Android Auto existed), so a phone in the
 *   pocket never steals read cycles from the car screen, and vice versa.
 *
 * The CSV recorder pins its own columns separately ([LiveDataProxy.setPinned]).
 * Thread safe; [apply] is called with the merged list whenever it changes.
 */
class MeasurementRequests(private val apply: (List<Measurement>) -> Unit) {
    private class Request(val measurements: List<Measurement>, val foreground: Boolean)

    private val requests = EnumMap<MeasurementClient, Request>(MeasurementClient::class.java)
    private var applied: List<Measurement>? = null

    @Synchronized
    fun set(client: MeasurementClient, measurements: List<Measurement>, foreground: Boolean) {
        requests[client] = Request(measurements, foreground)
        publish()
    }

    @Synchronized
    fun clear(client: MeasurementClient) {
        if (requests.remove(client) != null) publish()
    }

    /** The list currently sent to the engine. */
    @Synchronized
    fun merged(): List<Measurement> = merge()

    private fun merge(): List<Measurement> {
        val active = requests.values.filter { it.foreground }.ifEmpty { requests.values.toList() }
        val seen = HashSet<String>()
        return active.flatMap { it.measurements }.filter { seen.add(it.id) }
    }

    private fun publish() {
        val merged = merge()
        if (merged == applied) return
        applied = merged
        apply(merged)
    }
}
