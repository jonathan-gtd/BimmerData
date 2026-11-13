/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.profiles

import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.live.ComputedDef
import io.github.jonathangtd.bimmerdata.live.ComputedKind
import io.github.jonathangtd.bimmerdata.live.ComputedValues
import kotlinx.serialization.Serializable

/**
 * A value computed by the app, defined in a profile: measurement id `calc/<key>`, e.g.
 * `{"key":"power","kind":"Power","inputs":{"torque":"<sgbd>/<id>","rpm":"<sgbd>/<id>"},"label":"Power"}`.
 * The inputs are measurement ids by role ([ComputedKind.roles]). Unknown kinds (newer app) are kept
 * as they are and give no value.
 */
@Serializable
data class ComputedValue(
    val key: String,
    val kind: String,
    val inputs: Map<String, String> = emptyMap(),
    val label: String = "",
) {
    val id: String get() = ComputedValues.idOf(key)

    fun toDef(): ComputedDef? = ComputedKind.of(kind)?.let { ComputedDef(key, it, inputs, label) }
}

/** Pure operations on the computed values of a profile. */
object Computeds {
    /** Computed values per profile. */
    const val MAX = 8

    fun defs(list: List<ComputedValue>): List<ComputedDef> = list.mapNotNull { it.toDef() }

    /** Repairs a stored / imported list: valid keys, no duplicates, trimmed labels, at most [MAX]. */
    fun sanitize(list: List<ComputedValue>): List<ComputedValue> {
        val seen = HashSet<String>()
        return list.mapNotNull { c ->
            val key = c.key.trim().lowercase()
            if (!ComputedValues.KEY_PATTERN.matches(key) || !seen.add(key)) return@mapNotNull null
            c.copy(
                key = key, kind = c.kind.trim(), label = c.label.trim().take(ComputedValues.MAX_LABEL_LENGTH),
                inputs = c.inputs.mapKeys { it.key.trim() }.mapValues { it.value.trim() }.filter { it.key.isNotEmpty() && it.value.isNotEmpty() },
            )
        }.take(MAX)
    }

    /** A key for a new value of [kind]: "power", else "power_2", "power_3"... */
    fun newKey(existing: List<ComputedValue>, kind: ComputedKind): String {
        val base = kind.name.lowercase()
        val taken = existing.map { it.key }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    /** Problem of [c] (null = valid): kind known, every role given as "<sgbd>/<id>", label length. */
    fun problem(c: ComputedValue): String? {
        if (!ComputedValues.KEY_PATTERN.matches(c.key)) return "'key' must be 1..24 characters among a-z, 0-9 and _"
        val kind = ComputedKind.of(c.kind) ?: return "Unknown kind '${c.kind}' (known: ${ComputedKind.entries.joinToString { it.name }})"
        for (role in kind.roles) {
            val id = c.inputs[role] ?: return "Input '$role' is required for ${kind.name}"
            if (!id.contains('/') || id.startsWith('/') || id.endsWith('/')) return "Input '$role' must be a measurement id \"<sgbd>/<id>\""
            if (ComputedValues.isComputedId(id)) return "Input '$role' cannot be another computed value"
        }
        val extra = c.inputs.keys - kind.roles.toSet()
        if (extra.isNotEmpty()) return "Unknown input(s) ${extra.joinToString()} for ${kind.name} (inputs: ${kind.roles.joinToString()})"
        if (c.label.length > ComputedValues.MAX_LABEL_LENGTH) return "'label' has at most ${ComputedValues.MAX_LABEL_LENGTH} characters"
        return null
    }

    /** Cards (phone and Android Auto) and graph values of [p] showing computed value [id]. */
    fun uses(p: Profile, id: String): Pair<Int, Int> {
        val cards = (p.dashboard.phonePages + p.dashboard.carPages).sumOf { page -> page.cards.count { id in it.measurementIds } }
        val series = p.graphs.sumOf { g -> g.series.count { it.measurementId == id } }
        return cards to series
    }

    /** [p] without computed value [key], its cards and graph values removed like an ECU's ([io.github.jonathangtd.bimmerdata.cars.EcuUsage.remove]). */
    fun remove(p: Profile, key: String): Profile {
        val id = ComputedValues.idOf(key)
        fun Page.without() = copy(cards = cards.filterNot { id in it.measurementIds })
        return p.copy(
            computed = p.computed.filterNot { it.key == key },
            dashboard = p.dashboard.copy(phonePages = p.dashboard.phonePages.map { it.without() }, carPages = p.dashboard.carPages.map { it.without() }),
            graphs = p.graphs.map { g -> g.copy(series = g.series.filterNot { it.measurementId == id }) },
        )
    }
}
