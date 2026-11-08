/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import io.github.jonathangtd.bimmerdata.ediabas.BmwFast

/**
 * Native decoding of a dynamically defined KWP2000 record, without the interpreter.
 *
 * STATUS_MESSWERTBLOCK_LESEN defines local identifier F0 with
 * `2C F0 [02 pos size cidHi cidLo posInCid]...` (mode 02 = defineByCommonIdentifier, pos is the
 * 1-based position in the record) and reads it with `21 F0`; the answer is `61 F0 <record>`.
 * The layout is taken from the definition telegram the job really sent, so the decoder never
 * guesses sizes: it only applies the MESSWERTETAB formula (raw * MUL / DIV + ADD).
 */
object MesswertDecoder {

    /** One value in the record: [position] is 1-based, as in the definition telegram. */
    data class Slot(val row: MesswertRow, val position: Int, val size: Int)

    class Layout(val localId: Int, val slots: List<Slot>) {
        /** Record length the ECU must return (bytes after "61 F0"). */
        val recordLength: Int = slots.maxOfOrNull { it.position - 1 + it.size } ?: 0
    }

    data class DefEntry(val position: Int, val size: Int, val commonId: Int, val positionInId: Int)

    sealed interface Decoded {
        class Values(val values: List<Pair<Slot, Sample>>) : Decoded
        /** The ECU answered, but not with the record: negative response or a record of another shape. */
        class NotDefined(val reason: String) : Decoded
        class Invalid(val reason: String) : Decoded
    }

    /** Parses a `2C <lid> 02 ...` request (BMW-FAST telegram, checksum optional). */
    fun parseDefinition(telegram: ByteArray): Pair<Int, List<DefEntry>>? {
        val data = BmwFast.parse(telegram)?.data ?: return null
        if (data.size < 2 || u8(data, 0) != 0x2C) return null
        val entries = ArrayList<DefEntry>()
        var i = 2
        while (i < data.size) {
            if (u8(data, i) != 0x02 || i + 6 > data.size) return null
            entries += DefEntry(u8(data, i + 1), u8(data, i + 2), (u8(data, i + 3) shl 8) or u8(data, i + 4), u8(data, i + 5))
            i += 6
        }
        return u8(data, 1) to entries
    }

    /** Layout of [defineTelegram] with rows of [tab]; null if an entry is unknown or partial. */
    fun layout(tab: MesswerteTab, defineTelegram: ByteArray): Layout? {
        val (lid, entries) = parseDefinition(defineTelegram) ?: return null
        if (entries.isEmpty()) return null
        val slots = entries.map { e ->
            val row = tab.row(e.commonId) ?: return null
            if (e.positionInId != 1 || e.size != row.dataType.size) return null
            Slot(row, e.position, e.size)
        }
        return Layout(lid, slots)
    }

    /** Layout the job would build for [rows]: values packed in argument order (used when no telegram is available). */
    fun packedLayout(rows: List<MesswertRow>, localId: Int = 0xF0): Layout {
        var pos = 1
        return Layout(localId, rows.map { r -> Slot(r, pos, r.dataType.size).also { pos += r.dataType.size } })
    }

    /** Decodes an ECU answer (complete BMW-FAST telegram, checksum optional) to `21 <lid>`. */
    fun decode(tab: MesswerteTab, layout: Layout, response: ByteArray, timestampMs: Long): Decoded {
        val tel = BmwFast.parse(response) ?: return Decoded.Invalid("not a BMW-FAST telegram")
        val data = tel.data
        if (data.isEmpty()) return Decoded.Invalid("empty answer")
        if (u8(data, 0) == 0x7F) {
            return Decoded.NotDefined("negative response %02X".format(if (data.size > 2) u8(data, 2) else 0))
        }
        if (u8(data, 0) != 0x61 || data.size < 2) return Decoded.Invalid("unexpected service %02X".format(u8(data, 0)))
        if (u8(data, 1) != layout.localId) return Decoded.NotDefined("answer for local id %02X".format(u8(data, 1)))
        val recordLength = data.size - 2
        if (recordLength != layout.recordLength) {
            return Decoded.NotDefined("record has $recordLength bytes, ${layout.recordLength} expected")
        }
        return Decoded.Values(layout.slots.map { slot -> slot to sample(tab, slot.row, data, 2 + slot.position - 1, timestampMs) })
    }

    fun sample(tab: MesswerteTab, row: MesswertRow, buf: ByteArray, offset: Int, timestampMs: Long): Sample {
        val type = row.dataType
        var bits = 0L
        for (k in 0 until type.size) {
            val b = u8(buf, offset + if (row.littleEndian) type.size - 1 - k else k).toLong()
            bits = (bits shl 8) or b
        }
        val raw: Double = when {
            type.float -> java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
            type.signed -> {
                val shift = 64 - 8 * type.size
                ((bits shl shift) shr shift).toDouble()
            }
            else -> bits.toDouble()
        }
        val text = if (row.conversionTable != null && !type.float) tab.stateText(row, raw.toLong()) else null
        return Sample(row.physical(raw), text, timestampMs)
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}
