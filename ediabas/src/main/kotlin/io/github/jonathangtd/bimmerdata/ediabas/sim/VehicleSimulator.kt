/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas.sim

import io.github.jonathangtd.bimmerdata.ediabas.BmwFast

/**
 * A whole car on one BMW-FAST bus: several simulated ECUs behind the diagnosis connector, for
 * [io.github.jonathangtd.bimmerdata.ediabas.FakeInterface.multiResponder] (tests) and
 * [SimulatedKLine] (the app's simulation mode, see [kLine]).
 *
 * Physical requests (format 0x80) are answered by the ECU at the target address. Functional
 * requests (format 0xC0, target 0xEF = all ECUs or a bus address such as 0xEA PT-CAN, see the
 * FUNKTIONALEADRESSE table of the vehicle SGBDs) are answered by every ECU of that bus, one
 * telegram each, in address order: the functional jobs of E89X / E60 / E70... then collect them
 * with receive-only exchanges until the bus is silent.
 *
 * Every ECU answers:
 * - `1A 80` identification with its diagnosis index and variant index, so that both the
 *   ZUORDNUNGSTABELLE of T_GRTB (job IDENT_FUNKTIONAL) and the group file (job IDENTIFIKATION of
 *   D_MOTOR, D_DSC...) resolve the intended variant SGBD,
 * - `1A 90` short VIN (last 7 characters, job C_FG_LESEN_FUNKTIONAL result FG_NR) and
 *   `22 10 10` full VIN (CAS job STATUS_FAHRGESTELLNUMMER) if [SimEcu.storesVin],
 * - `18 02 FF FF` fault memory, `17 hi lo` (most SGBDs) or `21 0A hi lo` (MSV70) one fault in
 *   detail, `14 hi lo` clear (all faults for FF FB..FF FF, else that code),
 * - anything else through its [SimEcu.delegate] (e.g. an [EcuSimulator] with live values for the
 *   engine), else `7F sid 11` serviceNotSupported.
 */
class VehicleSimulator(
    ecus: List<SimEcu>,
    /** 17-character VIN, answered by the ECUs that store it. */
    val vin: String,
) {
    /** One fault memory entry: KWP2000 DTC code and status byte, as in the `58` answer. */
    data class SimFault(val code: Int, val status: Int = 0x24)

    /** ECU bus, for the functional addresses that are not "all ECUs". */
    enum class Bus(val functionalAddress: Int) {
        PtCan(0xEA), KCan(0xE9), Most(0xEC), Si(0xEB), Lin(0xE8), FlexRay(0xE6)
    }

    class SimEcu(
        val address: Int,
        /** Short name, for logs and tests only (the SGBDs give the real names). */
        val name: String,
        /** Diagnosis index (bytes 8-9 of the `5A 80` identification). */
        val diagIndex: Int,
        /** Variant index (2 ASCII characters, bytes 10-11), "00" when the SGBDs do not care. */
        val variantIndex: String = "00",
        val bus: Bus = Bus.KCan,
        faults: List<SimFault> = emptyList(),
        val storesVin: Boolean = false,
        /** BMW part number, 7 digits. */
        val partNumber: String = "0000000",
        /** Answers the services this class does not handle (null = serviceNotSupported). */
        val delegate: EcuSimulator? = null,
        /** Delay of its answer after the request (or after the previous ECU's answer), ms. */
        val responseDelayMs: Int = SimulatedKLine.DefaultP2Ms,
        /**
         * Number of `7F sid 78` (response pending) telegrams sent before the answer. On a functional
         * request the final answer then comes after the other ECUs' answers.
         */
        val responsePending: Int = 0,
    ) {
        internal val faults: MutableList<SimFault> = faults.toMutableList()

        /** Faults as stored by the ECU right now. */
        val currentFaults: List<SimFault> get() = synchronized(this) { faults.toList() }

        /** No answer at all while true (ECU without power, broken wire...). */
        @Volatile var silent: Boolean = false

        /** Number of fault clear requests this ECU accepted. */
        @Volatile var clears: Int = 0
            internal set
    }

    val ecus: List<SimEcu> = ecus.sortedBy { it.address }

    private val byAddress = this.ecus.associateBy { it.address }

    fun ecu(address: Int): SimEcu? = byAddress[address]

    /** False: functional requests stay unanswered (gateway without functional routing), physical ones work. */
    @Volatile var functionalAddressing: Boolean = true

    /** Number of functional requests received. */
    @Volatile var functionalRequests: Int = 0
        private set

    /** All answers to [request], in the order they appear on the bus; empty = silence. */
    fun respond(request: ByteArray): List<ByteArray> {
        val tel = BmwFast.parse(request) ?: return emptyList()
        val targets = if (tel.functional) {
            functionalRequests++
            if (!functionalAddressing) return emptyList()
            ecus.filter { tel.target == AllEcus || it.bus.functionalAddress == tel.target }
        } else {
            listOfNotNull(byAddress[tel.target])
        }
        val now = ArrayList<ByteArray>()
        val later = ArrayList<ByteArray>()
        for (ecu in targets.filter { !it.silent }) {
            val data = answer(ecu, tel) ?: continue
            val answer = BmwFast.buildTelegram(tel.source, ecu.address, data)
            if (ecu.responsePending > 0 && tel.data.isNotEmpty()) {
                repeat(ecu.responsePending) { now += BmwFast.buildTelegram(tel.source, ecu.address, bytes(0x7F, tel.data[0].toInt() and 0xFF, 0x78)) }
                (if (tel.functional) later else now) += answer
            } else {
                now += answer
            }
        }
        return now + later
    }

    /** [respond] with each ECU's delay, for [SimulatedKLine]. */
    fun replies(request: ByteArray): List<SimulatedKLine.Reply> {
        val tel = BmwFast.parse(request) ?: return emptyList()
        return respond(request).map { telegram ->
            val ecu = byAddress[telegram[2].toInt() and 0xFF]
            val delay = when {
                ecu == null -> SimulatedKLine.DefaultP2Ms
                !tel.functional && ecu.delegate != null -> ecu.delegate.responseDelayMs(request)
                else -> ecu.responseDelayMs
            }
            SimulatedKLine.Reply(telegram, delay)
        }
    }

    /** The car on a simulated K+DCAN line. */
    fun kLine(realisticTiming: Boolean = true): SimulatedKLine = SimulatedKLine(::replies, realisticTiming)

    private fun answer(ecu: SimEcu, tel: BmwFast.Telegram): ByteArray? {
        val req = tel.data
        if (req.isEmpty()) return null
        val sid = req[0].toInt() and 0xFF
        val p1 = if (req.size > 1) req[1].toInt() and 0xFF else -1
        return synchronized(ecu) {
            when {
                sid == 0x1A && (p1 == 0x80 || p1 == -1) -> identification(ecu)
                sid == 0x1A && p1 == 0x90 -> if (ecu.storesVin) bytes(0x5A, 0x90) + vin.takeLast(7).toByteArray(Charsets.US_ASCII) else negative(sid, 0x12)
                sid == 0x22 && req.size >= 3 && p1 == 0x10 && (req[2].toInt() and 0xFF) == 0x10 && ecu.storesVin ->
                    bytes(0x62, 0x10, 0x10) + vin.toByteArray(Charsets.US_ASCII)
                sid == 0x18 -> faultList(ecu)
                sid == 0x17 && req.size >= 3 -> faultDetail(ecu, ((req[1].toInt() and 0xFF) shl 8) or (req[2].toInt() and 0xFF))?.let { bytes(0x57, 0x01) + it }
                    ?: bytes(0x57, 0x00)
                sid == 0x21 && p1 == 0x0A && req.size >= 4 -> faultDetail(ecu, ((req[2].toInt() and 0xFF) shl 8) or (req[3].toInt() and 0xFF))
                    ?.let { bytes(0x61, 0x0A, 0x01) + it } ?: negative(sid, 0x31)
                sid == 0x14 -> clear(ecu, if (req.size >= 3) ((req[1].toInt() and 0xFF) shl 8) or (req[2].toInt() and 0xFF) else 0xFFFF)
                tel.functional -> null // only the services above are answered on functional requests
                ecu.delegate != null -> ecu.delegate.respond(BmwFast.buildTelegram(ecu.address, tel.source, req))
                    ?.let { BmwFast.parse(it)?.data }
                else -> negative(sid, 0x11)
            }
        }
    }

    private fun identification(ecu: SimEcu): ByteArray {
        val part = ecu.partNumber.padStart(12, '0').takeLast(12)
        val partBcd = ByteArray(6) { ((part[2 * it].digitToInt() shl 4) or part[2 * it + 1].digitToInt()).toByte() }
        val v = ecu.variantIndex.padEnd(2, '0')
        return bytes(0x5A, 0x80) + partBcd + bytes(
            0x01, // hardware number
            0x08, // coding index
            ecu.diagIndex shr 8, ecu.diagIndex and 0xFF,
            v[0].code, v[1].code,
            0x20, 0x06, 0x09, 0x14, // production date 14.09.2006 (BCD)
            0x11, // supplier
            0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23,
        )
    }

    private fun faultList(ecu: SimEcu): ByteArray {
        val out = bytes(0x58, ecu.faults.size)
        return ecu.faults.fold(out) { acc, f -> acc + bytes(f.code shr 8, f.code and 0xFF, f.status) }
    }

    /** Code, status and an environment block (frequency, logistic counter, mileage, values); null if not stored. */
    private fun faultDetail(ecu: SimEcu, code: Int): ByteArray? {
        val fault = ecu.faults.firstOrNull { it.code == code } ?: return null
        return bytes(code shr 8, code and 0xFF, fault.status) +
            bytes(0x03, 0x28, 0x01, 0x6A, 0x2C, 0x5A, 0x78, 0x90, 0x00, 0x46, 0x11, 0x20, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA)
    }

    private fun clear(ecu: SimEcu, code: Int): ByteArray {
        if (code >= 0xFFFB) ecu.faults.clear() else ecu.faults.removeAll { it.code == code }
        ecu.clears++
        return bytes(0x54, code shr 8, code and 0xFF)
    }

    private fun negative(sid: Int, nrc: Int) = bytes(0x7F, sid, nrc)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    companion object {
        /** Functional address "all ECUs" (FUNKTIONALEADRESSE ALL). */
        const val AllEcus = 0xEF

        /** DME fault 2F08 "Ansauglufttemperatursensor, Signal" exactly as the user's MSV70 sent it (Tool32 trace). */
        val Msv70IntakeAirFault = SimFault(0x2F08, 0x21)

        /**
         * A plausible E90 330i (N52, manual gearbox: no EGS) from 09/2006, K-line diagnosis through
         * the junction box. Diagnosis / variant indexes come from the ZUORDNUNGSTABELLE of
         * T_GRTB, so every ECU resolves to a real variant SGBD. A few faults are stored.
         */
        fun e90(engine: EcuSimulator? = EcuSimulator(liveValues = true)): VehicleSimulator = VehicleSimulator(
            listOf(
                SimEcu(0x00, "JBBF", 0x08C0, bus = Bus.KCan, storesVin = true, partNumber = "6943804"),
                SimEcu(0x01, "MRS", 0x08E0, bus = Bus.Si, partNumber = "6962039"),
                SimEcu(0x02, "SZL", 0x01C8, bus = Bus.KCan, partNumber = "6955176"),
                SimEcu(
                    0x12, "DME", 0x0000, variantIndex = "34", bus = Bus.PtCan, storesVin = true, partNumber = "7561234",
                    faults = listOf(Msv70IntakeAirFault), delegate = engine,
                ),
                SimEcu(0x17, "EKP", 0x0611, bus = Bus.PtCan, partNumber = "7194928"),
                SimEcu(0x29, "DSC", 0x05B0, bus = Bus.PtCan, faults = listOf(SimFault(0x5DF0, 0x24)), partNumber = "6779250"),
                SimEcu(0x40, "CAS", 0x06A0, bus = Bus.KCan, storesVin = true, partNumber = "6954710"),
                SimEcu(0x56, "FZD", 0x05A0, bus = Bus.KCan, partNumber = "6957964"),
                SimEcu(0x60, "KOMBI", 0x08D0, bus = Bus.KCan, storesVin = true, faults = listOf(SimFault(0x931B, 0x24)), partNumber = "9110227"),
                SimEcu(0x62, "MOSTGW", 0x0F90, bus = Bus.Most, partNumber = "9177203"),
                SimEcu(0x63, "RAD2", 0x0FA0, bus = Bus.Most, partNumber = "9177203"),
                SimEcu(0x64, "PDC", 0x09E0, bus = Bus.KCan, partNumber = "6982389"),
                SimEcu(0x72, "FRM", 0x05C1, bus = Bus.KCan, storesVin = true, faults = listOf(SimFault(0x9CAC, 0x24)), partNumber = "6976440"),
                SimEcu(0x78, "IHKA", 0x05D0, bus = Bus.KCan, partNumber = "6983747"),
            ),
            vin = "WBAVB71070VA12345",
        )
    }
}
