/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeChannel
import io.github.jonathangtd.bimmerdata.gauges.GaugeFormat
import io.github.jonathangtd.bimmerdata.gauges.GaugeSpec
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.live.Measurement

/** Turns a layout [Card] plus catalog metadata into what the renderer draws. */
object CardSpecs {
    fun build(card: Card, index: Map<String, Measurement>, format: GaugeFormat): GaugeSpec {
        val measurements = card.measurementIds.map { index[it] }
        val first = measurements.firstOrNull()
        val rawUnit = first?.unit.orEmpty()
        val channels = card.measurementIds.mapIndexed { i, id ->
            val m = measurements[i]
            val label = m?.label ?: id
            GaugeChannel(
                measurementId = id,
                label = card.channelLabels?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: shortLabel(label),
                unit = format.displayUnit(m?.unit.orEmpty(), label),
                decimals = card.decimals ?: GaugeFormat.defaultDecimals(m?.unit.orEmpty(), label),
            )
        }
        val (autoMin, autoMax) = when (card.type) {
            GaugeType.BipolarBar -> -25.0 to 25.0
            else -> if (first?.min != null && first.max != null) first.min!! to first.max!! else GaugeFormat.defaultRange(rawUnit)
        }
        val min = card.min ?: autoMin
        val max = (card.max ?: autoMax).let { if (it > min) it else min + 1.0 }
        val title = card.title?.takeIf { it.isNotBlank() } ?: first?.label ?: card.measurementIds.firstOrNull().orEmpty()
        return GaugeSpec(card.type, title, null, min, max, card.warnBelow, card.warnAbove, channels)
    }

    private val bank = Regex("""\bBank\s*(\d)""", RegexOption.IGNORE_CASE)
    private val cylinder = Regex("""\bZyl(?:inder)?\.?\s*(\d)""", RegexOption.IGNORE_CASE)

    /** "Integrator Bank 1" → "B1", "Zündwinkel Zylinder 1" → "Cyl. 1", else the label itself. */
    fun shortLabel(label: String): String {
        bank.find(label)?.let { return "B" + it.groupValues[1] }
        cylinder.find(label)?.let { return "Cyl. " + it.groupValues[1] }
        return label
    }
}
