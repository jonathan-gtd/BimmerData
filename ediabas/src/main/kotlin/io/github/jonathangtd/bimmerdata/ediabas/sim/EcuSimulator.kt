/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas.sim

import io.github.jonathangtd.bimmerdata.ediabas.BmwFast

/**
 * Plausible KWP2000 / BMW-FAST ECU for [io.github.jonathangtd.bimmerdata.ediabas.FakeInterface]
 * and [SimulatedKLine] (tests and the app's simulation mode): answers the services used by the
 * MSV70 jobs with positive responses of a realistic shape, whatever the ECU address. Values are
 * invented.
 */
class EcuSimulator(
    /** Extra per-service overrides: return null to fall back to the defaults. */
    private val custom: (BmwFast.Telegram) -> ByteArray? = { null },
) {
    /**
     * Raw values (big endian, as stored by the ECU) of common identifiers used in dynamically defined
     * records: 0x4402 oil temperature (signed int * 0.1 -> 90.0 degC), 0x4300 coolant temperature
     * (unsigned char * 0.75 - 48 -> 88.5 degC).
     */
    val commonIdValues: MutableMap<Int, ByteArray> = mutableMapOf(
        0x4402 to bytes(0x03, 0x84),
        0x4300 to bytes(0xB6),
    )

    /** Dynamically defined record (KWP2000 $2C defineByCommonIdentifier), filled on $21 requests. */
    private var dynamicRecord = ByteArray(0)

    fun respond(request: ByteArray): ByteArray? {
        val tel = BmwFast.parse(request) ?: return null
        val data = custom(tel) ?: defaultResponse(tel.data) ?: return null
        // answer: target = tester (source of the request), source = ECU address
        return BmwFast.buildTelegram(tel.source, tel.target, data)
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun defaultResponse(req: ByteArray): ByteArray? {
        if (req.isEmpty()) return null
        val sid = req[0].toInt() and 0xFF
        return when (sid) {
            0x1A -> { // readEcuIdentification
                val option = if (req.size > 1) req[1].toInt() and 0xFF else 0x80
                // 5A 80 + BMW identification block: part number, hardware, coding index, diagnosis
                // index 0x0000, variant index "34" (ASCII, as listed in T_GRTB for MSV70),
                // production date (BCD year/month/day), supplier, software numbers
                bytes(0x5A, option) + bytes(
                    0x00, 0x00, 0x00, 0x75, 0x61, 0x23, 0x45, 0x08, 0x00, 0x00, 0x33, 0x34,
                    0x00, 0x06, 0x10, 0x17, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                    0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22,
                )
            }
            0x18 -> { // readDiagnosticTroubleCodesByStatus: 2 DTCs
                bytes(0x58, 0x02, 0x29, 0xCD, 0x24, 0x2A, 0xFA, 0x60)
            }
            0x17 -> { // readStatusOfDiagnosticTroubleCodes
                val hi = if (req.size > 1) req[1].toInt() and 0xFF else 0
                val lo = if (req.size > 2) req[2].toInt() and 0xFF else 0
                bytes(0x57, 0x01, hi, lo, 0x24) + ByteArray(20) { (0x10 + it).toByte() }
            }
            0x2C -> { // dynamicallyDefineLocalIdentifier
                defineDynamicRecord(req)
                bytes(0x6C, if (req.size > 1) req[1].toInt() and 0xFF else 0)
            }
            0x21 -> { // readDataByLocalIdentifier
                val id = if (req.size > 1) req[1].toInt() and 0xFF else 0
                bytes(0x61, id) + if (id == 0xF0) dynamicRecord else ByteArray(8) { (0x40 + it * 3).toByte() }
            }
            0x22 -> { // readDataByCommonIdentifier
                bytes(0x62) + req.copyOfRange(1, minOf(3, req.size)) + bytes(0x12, 0x34, 0x56, 0x78)
            }
            0x10 -> bytes(0x50, if (req.size > 1) req[1].toInt() and 0xFF else 0x81)
            0x3E -> bytes(0x7E)
            0x14 -> bytes(0x54, 0xFF, 0xFF)
            0x31 -> bytes(0x71) + req.copyOfRange(1, minOf(2, req.size))
            0x30 -> bytes(0x70) + req.copyOfRange(1, minOf(3, req.size))
            else -> bytes(0x7F, sid, 0x11) // serviceNotSupported
        }
    }

    /**
     * "2C F0 04" clears the record; "2C F0 [02 pos size cidHi cidLo posInCid]..." (each entry
     * repeats definition mode 02 = defineByCommonIdentifier) maps values of common identifiers
     * to 1-based record positions.
     */
    private fun defineDynamicRecord(req: ByteArray) {
        var i = 2
        while (i < req.size) {
            when (req[i].toInt() and 0xFF) {
                0x04 -> {
                    dynamicRecord = ByteArray(0)
                    i += 1
                }
                0x02 -> {
                    if (i + 6 > req.size) return
                    val pos = req[i + 1].toInt() and 0xFF
                    val size = req[i + 2].toInt() and 0xFF
                    val cid = ((req[i + 3].toInt() and 0xFF) shl 8) or (req[i + 4].toInt() and 0xFF)
                    val posInCid = req[i + 5].toInt() and 0xFF
                    val value = commonIdValues[cid] ?: ByteArray(size)
                    if (dynamicRecord.size < pos - 1 + size) dynamicRecord = dynamicRecord.copyOf(pos - 1 + size)
                    for (k in 0 until size) {
                        dynamicRecord[pos - 1 + k] = value.getOrElse(posInCid - 1 + k) { 0 }
                    }
                    i += 6
                }
                else -> return
            }
        }
    }
}
