/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas.sim

import io.github.jonathangtd.bimmerdata.ediabas.BmwFast
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Plausible KWP2000 / BMW-FAST ECU for [io.github.jonathangtd.bimmerdata.ediabas.FakeInterface]
 * and [SimulatedKLine] (tests and the app's simulation mode): answers the services used by the
 * MSV70 jobs with positive responses of a realistic shape, whatever the ECU address. Values are
 * invented.
 *
 * With [liveValues] the common MSV70 measurement identifiers (see [LiveSignals]) follow a small
 * engine model driven by the time elapsed since construction (or [restartEngine]): idle around
 * 700 rpm with noise and a rev every 30 s, coolant and oil warming up, lambda oscillating around
 * 1.0, integrators +-3 %... Without it (default, for tests) the values are fixed:
 * [commonIdValues], zeros otherwise.
 */
class EcuSimulator(
    /** Extra per-service overrides: return null to fall back to the defaults. */
    private val custom: (BmwFast.Telegram) -> ByteArray? = { null },
    /** Time-driven values for the common measurement ids instead of fixed ones. */
    val liveValues: Boolean = false,
    /** Monotonic clock in nanoseconds (tests can drive it). */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /**
     * Fixed raw values (big endian, as stored by the ECU) of common identifiers used in dynamically
     * defined records: 0x4402 oil temperature (signed int * 0.1 -> 90.0 degC), 0x4300 coolant
     * temperature (unsigned char * 0.75 - 48 -> 88.5 degC). With [liveValues] they are only used
     * for ids the engine model does not know.
     */
    val commonIdValues: MutableMap<Int, ByteArray> = mutableMapOf(
        0x4402 to bytes(0x03, 0x84),
        0x4300 to bytes(0xB6),
    )

    /** Silent ECU (ignition off, wrong address...): no answer at all while true. */
    @Volatile var silent: Boolean = false

    /** Number of dynamic record reads ($21 F0) answered, and of definitions ($2C ... 02). */
    @Volatile var recordReads: Long = 0
        private set
    @Volatile var recordDefinitions: Long = 0
        private set

    private class Entry(val pos: Int, val size: Int, val cid: Int, val posInCid: Int)

    /** Dynamically defined record (KWP2000 $2C defineByCommonIdentifier); null = not defined. */
    private var dynamicEntries: MutableList<Entry>? = null
    private val lock = Any()
    @Volatile private var engineStart = nanoTime()

    /** Starts the engine model again (cold engine). */
    fun restartEngine() {
        engineStart = nanoTime()
    }

    /** ECU reset: the dynamic record definition is lost, like after a power cycle. */
    fun reset() = synchronized(lock) {
        dynamicEntries = null
    }

    fun respond(request: ByteArray): ByteArray? {
        if (silent) return null
        val tel = BmwFast.parse(request) ?: return null
        val data = custom(tel) ?: synchronized(lock) { defaultResponse(tel.data) } ?: return null
        // answer: target = tester (source of the request), source = ECU address
        return BmwFast.buildTelegram(tel.source, tel.target, data)
    }

    /**
     * ECU response time (P2, from the end of the request to the first answer byte) fitted on the
     * MSV70 exchanges of the user's Tool32 trace. That interface waits the 20 ms regeneration time
     * inside the measured exchange, so it is subtracted here (our interface waits it before
     * sending): clearing the record ~8 ms, a definition ~3 ms + 0.37 ms per data byte (10 ids:
     * ~26 ms + 6 ms on the wire), a record read ~8 ms + 0.8 ms per record byte (10 ids: ~22 ms).
     */
    fun responseDelayMs(request: ByteArray): Int {
        val tel = BmwFast.parse(request) ?: return SimulatedKLine.DefaultP2Ms
        val d = tel.data
        if (d.isEmpty()) return SimulatedKLine.DefaultP2Ms
        return when (d[0].toInt() and 0xFF) {
            0x2C -> if (d.size <= 3) 8 else 3 + d.size * 3 / 8
            0x21 -> 8 + (recordLength() * 4 / 5)
            0x1A -> 20
            0x18, 0x17 -> 12
            else -> 10
        }
    }

    private fun recordLength(): Int = synchronized(lock) { dynamicEntries?.maxOfOrNull { it.pos - 1 + it.size } ?: 0 }

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
                if (id == 0xF0) {
                    val record = buildRecord() ?: return bytes(0x7F, 0x21, 0x31) // requestOutOfRange: not defined
                    recordReads++
                    bytes(0x61, id) + record
                } else {
                    bytes(0x61, id) + ByteArray(8) { (0x40 + it * 3).toByte() }
                }
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
     * to 1-based record positions. Values are taken when the record is read.
     */
    private fun defineDynamicRecord(req: ByteArray) {
        var i = 2
        while (i < req.size) {
            when (req[i].toInt() and 0xFF) {
                0x04 -> {
                    dynamicEntries = null
                    i += 1
                }
                0x02 -> {
                    if (i + 6 > req.size) return
                    val entries = dynamicEntries ?: ArrayList<Entry>().also { dynamicEntries = it }
                    entries += Entry(
                        pos = req[i + 1].toInt() and 0xFF,
                        size = req[i + 2].toInt() and 0xFF,
                        cid = ((req[i + 3].toInt() and 0xFF) shl 8) or (req[i + 4].toInt() and 0xFF),
                        posInCid = req[i + 5].toInt() and 0xFF,
                    )
                    recordDefinitions++
                    i += 6
                }
                else -> return
            }
        }
    }

    private fun buildRecord(): ByteArray? {
        val entries = dynamicEntries ?: return null
        val record = ByteArray(entries.maxOfOrNull { it.pos - 1 + it.size } ?: 0)
        val t = (nanoTime() - engineStart) / 1e9
        for (e in entries) {
            val value = valueOf(e.cid, e.size, t)
            for (k in 0 until e.size) {
                record[e.pos - 1 + k] = value.getOrElse(e.posInCid - 1 + k) { 0 }
            }
        }
        return record
    }

    private fun valueOf(cid: Int, size: Int, t: Double): ByteArray {
        if (liveValues) LiveSignals.byId[cid]?.let { return it.encode(it.value(t, cid)) }
        return commonIdValues[cid] ?: ByteArray(size)
    }

    /**
     * Engine model for the common MSV70 MESSWERTETAB ids. Each signal is stored like the ECU does
     * (DATENTYP, MUL, ADD of the MSV70 table), so the SGBD formulas give back the modelled values.
     */
    object LiveSignals {
        class Signal(val id: Int, val size: Int, val signed: Boolean, val mul: Double, val add: Double, val value: (Double, Int) -> Double) {
            fun encode(physical: Double): ByteArray {
                var raw = ((physical - add) / mul).roundToLong()
                val bits = 8 * size
                val min = if (signed) -(1L shl (bits - 1)) else 0L
                val max = if (signed) (1L shl (bits - 1)) - 1 else (1L shl bits) - 1
                raw = raw.coerceIn(min, max)
                return ByteArray(size) { k -> (raw shr (8 * (size - 1 - k))).toByte() }
            }
        }

        /** 0..1: a rev to ~3000 rpm lasting 4 s every 30 s (smooth up and down). */
        fun rev(t: Double): Double {
            val p = (t % 30.0) - 22.0
            if (p < 0 || p > 4) return 0.0
            return sin(PI * p / 4).let { it * it }
        }

        private fun noise(t: Double, id: Int, amplitude: Double): Double {
            // deterministic pseudo noise: sum of incommensurate sines, different per id
            val s = id * 0.37
            return amplitude * (0.6 * sin(t * 7.3 + s) + 0.3 * sin(t * 13.7 + 2 * s) + 0.1 * sin(t * 29.1 + 3 * s))
        }

        private fun warm(t: Double, from: Double, to: Double, tauS: Double) = to - (to - from) * exp(-t / tauS)

        val all: List<Signal> = listOf(
            // rpm: idle 700 +-15, revs to 3000
            Signal(0x4807, 2, false, 1.0, 0.0) { t, id -> 700 + 2300 * rev(t) + noise(t, id, 15.0) },
            // idle target speed
            Signal(0x4808, 2, false, 1.0, 0.0) { t, _ -> if (t < 60) 750.0 else 700.0 },
            // calculated load %
            Signal(0x5804, 1, false, 0.390625, 0.0) { t, id -> 18 + 45 * rev(t) + noise(t, id, 1.0) },
            // coolant degC: 20 -> 90
            Signal(0x4300, 1, false, 0.75, -47.9999985694886) { t, _ -> warm(t, 20.0, 90.0, 240.0) },
            Signal(0x4301, 1, false, 0.75, -47.9999985694886) { t, _ -> warm(t, 18.0, 70.0, 400.0) },
            // oil degC: 18 -> 100, slower
            Signal(0x4402, 2, true, 0.100000001490116, 0.0) { t, _ -> warm(t, 18.0, 100.0, 420.0) },
            Signal(0x4408, 2, true, 0.100000001490116, 0.0) { t, _ -> warm(t, 18.0, 99.0, 420.0) },
            // intake air / ambient degC
            Signal(0x4200, 1, false, 0.75, -47.9999985694886) { t, _ -> warm(t, 15.0, 32.0, 300.0) },
            Signal(0x4204, 1, false, 0.75, -47.9999985694886) { _, _ -> 15.0 },
            // ambient / manifold pressure hPa, air mass kg/h
            Signal(0x4201, 2, false, 0.0829175263643265, 0.0) { _, _ -> 1013.0 },
            Signal(0x4202, 2, false, 0.0829175263643265, 0.0) { t, id -> 330 + 600 * rev(t) + noise(t, id, 8.0) },
            Signal(0x4203, 2, false, 0.03125, 0.0) { t, id -> 14 + 110 * rev(t) + noise(t, id, 0.5) },
            // throttle angle, pedal
            Signal(0x4600, 2, false, 0.00729414634406567, 0.0) { t, id -> 1.8 + 25 * rev(t) + abs(noise(t, id, 0.1)) },
            Signal(0x480B, 1, false, 0.390625, 0.0) { t, _ -> 40 * rev(t) },
            // battery voltage V
            Signal(0x460A, 2, false, 0.0149999996647239, 0.0) { t, id -> 14.1 + noise(t, id, 0.05) },
            // lambda bank 1/2 ~1.0 +-0.03 at ~0.8 Hz
            Signal(0x5A50, 2, false, 9.765625E-4, 0.0) { t, _ -> 1.0 + 0.03 * sin(2 * PI * t / 1.25) },
            Signal(0x5A51, 2, false, 9.765625E-4, 0.0) { t, _ -> 1.0 + 0.03 * sin(2 * PI * t / 1.25 + 1.3) },
            // lambda integrators %: +-3 %
            Signal(0x5A81, 2, true, 0.00152587890625, 2.22044609888115E-14) { t, _ -> 3 * sin(2 * PI * t / 6.0) },
            Signal(0x5A82, 2, true, 0.00152587890625, 2.22044609888115E-14) { t, _ -> 3 * sin(2 * PI * t / 6.0 + 2.0) },
            // multiplicative adaptations % (as read on the user's car)
            Signal(0x5A85, 2, true, 0.00152587890625, 2.22044609888115E-14) { _, _ -> 0.3692627 },
            Signal(0x5A86, 2, true, 0.00152587890625, 2.22044609888115E-14) { _, _ -> -1.643372 },
            // ignition advance cylinder 1 degCRK
            Signal(0x580E, 1, false, 0.5, -64.0) { t, id -> 10 + 18 * rev(t) + noise(t, id, 1.5) },
            // VANOS intake / exhaust spread degCRK (intake: target and actual)
            Signal(0x4505, 2, true, 0.100000001490116, 0.0) { t, _ -> 95 - 25 * rev(t) },
            Signal(0x4508, 2, true, 0.100000001490116, 0.0) { t, id -> 95 - 25 * rev(maxOf(0.0, t - 0.3)) + noise(t, id, 0.4) },
            Signal(0x4509, 1, false, -0.375, -39.9999978542329) { t, id -> -95 + 10 * rev(t) + noise(t, id, 0.4) },
            // vehicle speed km/h (standing)
            Signal(0x580D, 1, false, 1.0, 0.0) { _, _ -> 0.0 },
        )

        val byId: Map<Int, Signal> = all.associateBy { it.id }
    }
}
