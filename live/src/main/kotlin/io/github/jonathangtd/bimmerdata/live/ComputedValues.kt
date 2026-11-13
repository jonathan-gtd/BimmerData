/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/** Role of a torque input (N·m). */
const val ROLE_TORQUE = "torque"

/** Role of an engine speed input (rpm). */
const val ROLE_RPM = "rpm"

/** N·m × rpm per kW: 60000 / 2π. */
const val NM_RPM_PER_KW = 60000.0 / (2 * PI)

/** Metric horsepower per kW. */
const val CH_PER_KW = 1.35962

/**
 * What a computed value is made of. Each kind names the [roles] of its inputs (in this order in
 * [MeasurementSource.Computed.inputs]) and computes its value from theirs. Add a kind here (and
 * its texts in the app) to offer another computed value.
 */
enum class ComputedKind(val unit: String, val roles: List<String>, val min: Double?, val max: Double?) {
    /**
     * Engine power in metric horsepower (ch / PS) from a torque in N·m and an engine speed in
     * rpm: P [kW] = T × n / 9549.3 (60000 / 2π), 1 kW = 1.35962 ch. A negative torque (engine
     * braking) gives 0: the value is the power the engine gives, not the one it absorbs.
     */
    Power("ch", listOf(ROLE_TORQUE, ROLE_RPM), 0.0, 400.0) {
        override fun compute(inputs: List<Double>): Double = max(0.0, inputs[0] * inputs[1] / NM_RPM_PER_KW * CH_PER_KW)
    },
    ;

    /** The value from the input values, in the order of [roles]. */
    abstract fun compute(inputs: List<Double>): Double

    companion object {
        const val TORQUE = ROLE_TORQUE
        const val RPM = ROLE_RPM

        fun of(name: String): ComputedKind? = entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * One computed value of a profile, as the app stores it: measurement id `calc/<key>`, its [kind],
 * the measurement id of each input by role (e.g. "torque" -> "<sgbd>/<id>") and a label.
 */
data class ComputedDef(
    val key: String,
    val kind: ComputedKind,
    val inputs: Map<String, String>,
    val label: String,
) {
    val id: String get() = ComputedValues.idOf(key)

    /** Input ids in the order of [ComputedKind.roles]; null when one is missing. */
    fun inputIds(): List<String>? = kind.roles.map { inputs[it] ?: return null }
}

/**
 * Values computed by the app from other measurements (pseudo ECU [CALC], ids `calc/<key>`), next to
 * the ECU and phone sensor ones: cards, graphs, the CSV recording, the Telegraf export (tag
 * `ecu=calc`) and the REST API handle them like any other value.
 *
 * - The acquisition never reads a computed value itself: [expand] replaces it by its inputs.
 * - A sample is computed whenever an input gets a new one ([evaluate]), only when every input has
 *   a value no older than [MAX_SKEW_MS] from the others; its time is the newest input's time.
 */
object ComputedValues {
    /** Pseudo SGBD of the computed values. */
    const val CALC = "calc"

    /** Inputs further apart than this give no value (one of them is stale). */
    const val MAX_SKEW_MS = 2_000L

    /** Keys: lower case letters, digits and "_", 1..24 characters. */
    val KEY_PATTERN = Regex("[a-z0-9_]{1,24}")

    const val MAX_LABEL_LENGTH = 40

    fun idOf(key: String): String = "$CALC/$key"

    fun isComputedId(id: String): Boolean = id.startsWith("$CALC/")

    fun isComputed(m: Measurement): Boolean = m.source is MeasurementSource.Computed

    /**
     * The measurement of [def], its inputs looked up with [resolve]; null when an input is missing
     * (its ECU file is not imported, or the definition lacks a role).
     */
    fun measurement(def: ComputedDef, resolve: (String) -> Measurement?): Measurement? {
        val ids = def.inputIds() ?: return null
        val inputs = ids.map { resolve(it) ?: return null }
        return Measurement(
            id = def.id, sgbd = CALC, label = def.label.ifBlank { def.kind.name }, unit = def.kind.unit,
            source = MeasurementSource.Computed(def.kind, inputs), min = def.kind.min, max = def.kind.max,
            description = inputs.joinToString(" · ") { it.label },
        )
    }

    /** [list] with each computed value replaced by its inputs (what must be read), without duplicates. */
    fun expand(list: List<Measurement>): List<Measurement> = list.flatMap { m ->
        when (val s = m.source) {
            is MeasurementSource.Computed -> s.inputs
            else -> listOf(m)
        }
    }.distinctBy { it.id }

    /** [list] plus the inputs of its computed values (computed values kept), without duplicates. */
    fun withInputs(list: List<Measurement>): List<Measurement> = (list + list.flatMap { m ->
        (m.source as? MeasurementSource.Computed)?.inputs.orEmpty()
    }).distinctBy { it.id }

    /** Ids of [ids] with each `calc/<key>` replaced by its input ids from [defs] (kept as is when unknown). */
    fun expandIds(ids: Collection<String>, defs: List<ComputedDef>): Set<String> {
        val byId = defs.associateBy { it.id }
        return ids.flatMapTo(LinkedHashSet()) { id -> byId[id]?.inputIds() ?: listOf(id) }
    }

    /** The sample of computed value [m] from the latest [samples]; null when an input lacks a fresh number. */
    fun evaluate(m: Measurement, samples: Map<String, Sample>): Sample? {
        val src = m.source as? MeasurementSource.Computed ?: return null
        if (src.inputs.isEmpty()) return null
        val ins = src.inputs.map { samples[it.id] ?: return null }
        val values = ins.map { s -> s.value?.takeIf { it.isFinite() } ?: return null }
        val newest = ins.maxOf { it.timestampMs }
        val oldest = ins.minOf { it.timestampMs }
        if (abs(newest - oldest) > MAX_SKEW_MS) return null
        val v = src.kind.compute(values)
        return if (v.isFinite()) Sample(v, null, newest) else null
    }

    /**
     * New samples of the [computed] values after [fresh] input samples arrived; [all] = every
     * latest sample, [fresh] included. Only values with an input among [fresh] are computed.
     */
    fun update(computed: List<Measurement>, fresh: Map<String, Sample>, all: Map<String, Sample>): Map<String, Sample> {
        if (computed.isEmpty() || fresh.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Sample>()
        for (m in computed) {
            val src = m.source as? MeasurementSource.Computed ?: continue
            if (src.inputs.none { it.id in fresh }) continue
            evaluate(m, all)?.let { out[m.id] = it }
        }
        return out
    }

    /** Computed values of [computed] with an input among [unavailable]: id -> reason. */
    fun unavailable(computed: List<Measurement>, unavailable: Map<String, String>): Map<String, String> {
        if (unavailable.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (m in computed) {
            val src = m.source as? MeasurementSource.Computed ?: continue
            src.inputs.firstOrNull { it.id in unavailable }?.let { out[m.id] = "${it.label}: ${unavailable[it.id]}" }
        }
        return out
    }
}

/**
 * [MeasurementCatalog] with the computed values of the active profile ([defs]) as the pseudo ECU
 * [ComputedValues.CALC], their inputs looked up in [base]. The picker lists it with [description].
 */
class ComputedCatalog(
    private val base: MeasurementCatalog,
    private val defs: () -> List<ComputedDef>,
    private val description: String = "Computed values",
) : MeasurementCatalog {
    override fun sgbdsWithMeasurements(): List<String> =
        base.sgbdsWithMeasurements().filter { it != ComputedValues.CALC } + ComputedValues.CALC

    override fun measurements(sgbd: String): List<Measurement> =
        if (sgbd.equals(ComputedValues.CALC, ignoreCase = true)) computed() else base.measurements(sgbd)

    override fun sgbdInfos(): List<SgbdInfo> =
        base.sgbdInfos().filter { it.name != ComputedValues.CALC } + SgbdInfo(ComputedValues.CALC, description, defs().size)

    /** The computed values whose inputs are all known. */
    fun computed(): List<Measurement> {
        val bySgbd = HashMap<String, Map<String, Measurement>>()
        fun resolve(id: String): Measurement? {
            if (!id.contains('/')) return null
            val sgbd = id.substringBefore('/').lowercase()
            val map = bySgbd.getOrPut(sgbd) { runCatching { base.measurements(sgbd) }.getOrDefault(emptyList()).associateBy { it.id } }
            return map[id]
        }
        return defs().mapNotNull { ComputedValues.measurement(it, ::resolve) }
    }
}
