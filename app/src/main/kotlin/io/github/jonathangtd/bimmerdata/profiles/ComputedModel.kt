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
 * A value computed by the app (formula), defined in a profile and belonging to the ECU [ecu] whose
 * values are its inputs: measurement id `<ecu>/<key>`, listed with that ECU's values, e.g.
 * `{"ecu":"<sgbd>","key":"power","kind":"Power","inputs":{"torque":"<sgbd>/<id>","rpm":"<sgbd>/<id2>"},"label":"Power"}`.
 * The inputs are measurement ids of [ecu] by role ([ComputedKind.roles]). Unknown kinds (newer
 * app) are kept as they are and give no value. Older files have no [ecu] (ids `calc/<key>`):
 * [Computeds.ownedByEcu] gives them the ECU of their inputs.
 */
@Serializable
data class ComputedValue(
    val key: String,
    val kind: String,
    val inputs: Map<String, String> = emptyMap(),
    val label: String = "",
    val ecu: String = "",
) {
    val id: String get() = ComputedValues.idOf(ecu, key)

    fun toDef(): ComputedDef? = ComputedKind.of(kind)?.takeIf { ecu.isNotEmpty() }?.let { ComputedDef(ecu, key, it, inputs, label) }
}

/** Pure operations on the computed values of a profile. */
object Computeds {
    /** Computed values per profile. */
    const val MAX = 8

    fun defs(list: List<ComputedValue>): List<ComputedDef> = list.mapNotNull { it.toDef() }

    /**
     * Repairs a stored / imported list: valid keys, no duplicates, trimmed labels, at most [MAX]; a
     * value without ECU (older files) gets the ECU of its inputs.
     */
    fun sanitize(list: List<ComputedValue>): List<ComputedValue> {
        val seen = HashSet<String>()
        return list.mapNotNull { c ->
            val key = c.key.trim().lowercase()
            val inputs = c.inputs.mapKeys { it.key.trim() }.mapValues { it.value.trim() }.filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
            val ecu = c.ecu.trim().lowercase().ifEmpty { ecuOfInputs(inputs) ?: "" }
            if (!ComputedValues.KEY_PATTERN.matches(key) || !seen.add(key)) return@mapNotNull null
            c.copy(key = key, kind = c.kind.trim(), label = c.label.trim().take(ComputedValues.MAX_LABEL_LENGTH), inputs = inputs, ecu = ecu)
        }.take(MAX)
    }

    /** The ECU of [inputs] (that of the first one); null without inputs. */
    fun ecuOfInputs(inputs: Map<String, String>): String? =
        inputs.values.firstOrNull { it.contains('/') }?.substringBefore('/')?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * [dashboard] and [graphs] with the ids of the older pseudo ECU (`calc/<key>`) replaced by the id of
     * the computed value of [computed] with that key (`<ecu>/<key>`, see [sanitize]).
     */
    fun ownedByEcu(
        computed: List<ComputedValue>, dashboard: io.github.jonathangtd.bimmerdata.dashboard.Dashboard, graphs: List<io.github.jonathangtd.bimmerdata.graph.Graph>,
    ): Pair<io.github.jonathangtd.bimmerdata.dashboard.Dashboard, List<io.github.jonathangtd.bimmerdata.graph.Graph>> {
        val legacy = ComputedValues.LEGACY_SGBD + "/"
        val all = dashboard.phonePages + dashboard.carPages
        if (all.none { p -> p.cards.any { c -> c.measurementIds.any { it.startsWith(legacy) } } } &&
            graphs.none { g -> g.series.any { it.measurementId.startsWith(legacy) } }) return dashboard to graphs
        val byKey = computed.associateBy { it.key }
        fun map(id: String): String = if (id.startsWith(legacy)) byKey[id.removePrefix(legacy)]?.id ?: id else id
        fun Page.mapped() = copy(cards = cards.map { c -> c.copy(measurementIds = c.measurementIds.map(::map)) })
        return dashboard.copy(phonePages = dashboard.phonePages.map { it.mapped() }, carPages = dashboard.carPages.map { it.mapped() }) to
            graphs.map { g -> g.copy(series = g.series.map { it.copy(measurementId = map(it.measurementId)) }) }
    }

    /** A key for a new value of [kind] (keys are unique in a profile): "power", else "power_2", "power_3"... */
    fun newKey(existing: List<ComputedValue>, kind: ComputedKind): String {
        val base = kind.name.lowercase()
        val taken = existing.map { it.key }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    /**
     * Problem of [c] (null = valid): ECU given, key not like an ECU value id, kind known, every role
     * given as a value of that ECU, not another computed value of [others], label length.
     */
    fun problem(c: ComputedValue, others: List<ComputedValue> = emptyList()): String? {
        if (c.ecu.isBlank()) return "'ecu' is required: the ECU the formula belongs to (that of its inputs)"
        if (!ComputedValues.KEY_PATTERN.matches(c.key)) return "'key' must be 1..24 characters among a-z, 0-9 and _"
        if (ComputedValues.looksLikeEcuId(c.key)) return "'key' cannot start with 0x: that is how the ECU's own values are named"
        val kind = ComputedKind.of(c.kind) ?: return "Unknown kind '${c.kind}' (known: ${ComputedKind.entries.joinToString { it.name }})"
        val formulaIds = others.map { it.id }.toSet()
        for (role in kind.roles) {
            val id = c.inputs[role] ?: return "Input '$role' is required for ${kind.name}"
            if (!id.contains('/') || id.startsWith('/') || id.endsWith('/')) return "Input '$role' must be a measurement id \"<sgbd>/<id>\""
            if (!id.substringBefore('/').equals(c.ecu, ignoreCase = true)) {
                return "Input '$role' ($id) is not a value of ${c.ecu}: a formula belongs to one ECU, all its inputs must come from it"
            }
            if (id in formulaIds) return "Input '$role' cannot be another computed value"
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
        val id = p.computed.firstOrNull { it.key == key }?.id ?: return p
        fun Page.without() = copy(cards = cards.filterNot { id in it.measurementIds })
        return p.copy(
            computed = p.computed.filterNot { it.key == key },
            dashboard = p.dashboard.copy(phonePages = p.dashboard.phonePages.map { it.without() }, carPages = p.dashboard.carPages.map { it.without() }),
            graphs = p.graphs.map { g -> g.copy(series = g.series.filterNot { it.measurementId == id }) },
        )
    }
}
