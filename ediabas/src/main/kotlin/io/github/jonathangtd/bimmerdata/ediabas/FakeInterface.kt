/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 *
 * Scripted communication interface (no hardware) built on the EdInterfaceBase port of EdiabasLib
 * (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0), file EdiabasLib/EdInterfaceBase.cs.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

/**
 * Interface without hardware: every telegram the job sends (send_and_receive / xsend) is recorded
 * in [requests] and answered by [responder]. A null answer behaves like a silent ECU
 * (EDIABAS_IFH_0009 "NO RESPONSE FROM CONTROLUNIT").
 *
 * Responses must be complete telegrams as a real interface returns them (for BMW-FAST: header,
 * target, source, [length], data and checksum; see [BmwFast.buildTelegram]).
 *
 * Functional addressing (one request, several ECUs answer): set [multiResponder]. The first answer
 * is returned to the request, the others to the following receive-only exchanges (the job calls
 * send_and_receive with an empty request, EdInterfaceObd.cs TransKwp2000 with sendDataLength 0),
 * then the bus is silent (EDIABAS_IFH_0009), which ends the job's receive loop.
 */
class FakeInterface(
    var responder: (request: ByteArray) -> ByteArray? = { null },
) : EdInterfaceBase() {

    /** One exchange: request as sent by the job, response returned (null = no response). */
    class Exchange(val request: ByteArray, val response: ByteArray?)

    val exchanges: MutableList<Exchange> = ArrayList()

    /** Several answers per request (functional addressing); takes precedence over [responder]. */
    var multiResponder: ((request: ByteArray) -> List<ByteArray>)? = null

    /** Answers of the last request not yet received. */
    private val pending = ArrayDeque<ByteArray>()

    val requests: List<ByteArray> get() = exchanges.map { it.request }

    private var isConnected = false

    var interfaceTypeValue: String = "OBD"
    var interfaceVersionValue: UInt = 209u
    var batteryVoltageValue: Long = 12500
    var ignitionVoltageValue: Long = 12500
    var keyBytesValue: ByteArray? = ByteArray(0)
    var stateValue: ByteArray? = ByteArray(0)

    override fun isValidInterfaceName(name: String): Boolean = name.equals(interfaceName, ignoreCase = true)

    override fun interfaceConnect(): Boolean {
        super.interfaceConnect()
        isConnected = true
        return true
    }

    override fun interfaceDisconnect(): Boolean {
        super.interfaceDisconnect()
        isConnected = false
        return true
    }

    override fun interfaceReset(): Boolean = true

    override fun interfaceBoot(): Boolean = true

    override fun transmitData(sendData: ByteArray): ByteArray? {
        val request = sendData.copyOf()
        ediabas?.logData(EdLogLevel.Ifh, request, 0, request.size, "Send")
        val multi = multiResponder
        val response = when {
            request.isEmpty() && (multi != null || pending.isNotEmpty()) -> pending.removeFirstOrNull()
            multi != null -> {
                pending.clear()
                pending.addAll(multi(request))
                pending.removeFirstOrNull()
            }
            else -> {
                pending.clear()
                responder(request)
            }
        }
        exchanges.add(Exchange(request, response?.copyOf()))
        if (response == null) {
            ediabas?.logString(EdLogLevel.Ifh, "*** No response")
            ediabas?.setError(ErrorCodes.EDIABAS_IFH_0009)
            return null
        }
        ediabas?.logData(EdLogLevel.Ifh, response, 0, response.size, "Resp")
        return response
    }

    override fun transmitFrequent(sendData: ByteArray): Boolean = true

    override fun receiveFrequent(): ByteArray? = ByteArray(0)

    override fun stopFrequent(): Boolean = true

    override fun rawData(sendData: ByteArray): ByteArray? = transmitData(sendData)

    override fun transmitCancel(cancel: Boolean): Boolean = true

    override val bmwFastProtocol: Boolean get() = commParameter?.firstOrNull() == 0x010Fu

    override val interfaceType: String get() = interfaceTypeValue

    override val interfaceVersion: UInt get() = interfaceVersionValue

    override val interfaceName: String get() = "FAKE"

    override val keyBytes: ByteArray? get() = keyBytesValue

    override val state: ByteArray? get() = stateValue

    override val loopTest: UInt get() = 0u

    override val batteryVoltage: Long get() = batteryVoltageValue

    override val ignitionVoltage: Long get() = ignitionVoltageValue

    override fun getPort(index: UInt): Long = 0

    override fun setPort(index: UInt, value: UInt) {}

    override fun setProgramVoltage(voltage: UInt) {}

    override fun switchSiRelais(time: UInt) {}

    override val connected: Boolean get() = isConnected

    override val isEcuConnected: Boolean get() = isConnected
}

/** BMW-FAST (KWP2000 over K-line/D-CAN with BMW header) telegram helpers. */
object BmwFast {
    /**
     * Parsed telegram: [data] is the service payload (service id first), checksum excluded.
     * [functional]: format byte 0xC0 | length, [target] is then a functional address (0xEF = all
     * ECUs, 0xE9 K-CAN, 0xEA PT-CAN... see the FUNKTIONALEADRESSE table of the vehicle SGBDs).
     */
    class Telegram(val target: Int, val source: Int, val data: ByteArray, val functional: Boolean = false)

    /** Format byte of a functionally addressed telegram (bit 6 set), physical is 0x80. */
    const val FunctionalFormat = 0xC0

    /** Parses a request as sent by a job (the checksum byte, if present, is ignored). */
    fun parse(telegram: ByteArray): Telegram? {
        if (telegram.size < 4) return null
        if ((telegram[0].toInt() and 0x80) != 0x80) return null
        val (length, offset) = EdInterfaceBase.dataLengthBmwFast(telegram)
        if (length < 0) return null
        return Telegram(
            telegram[1].toInt() and 0xFF, telegram[2].toInt() and 0xFF, telegram.copyOfRange(offset, offset + length),
            functional = (telegram[0].toInt() and 0xC0) == FunctionalFormat,
        )
    }

    /** Builds a complete telegram with header and checksum, as returned by an interface. */
    fun buildTelegram(target: Int, source: Int, data: ByteArray): ByteArray {
        val header = if (data.size in 1..0x3F) {
            byteArrayOf((0x80 or data.size).toByte(), target.toByte(), source.toByte())
        } else if (data.size <= 0xFF) {
            byteArrayOf(0x80.toByte(), target.toByte(), source.toByte(), data.size.toByte())
        } else {
            byteArrayOf(0x80.toByte(), target.toByte(), source.toByte(), 0, (data.size shr 8).toByte(), data.size.toByte())
        }
        val frame = header + data
        return frame + EdInterfaceBase.calcChecksumBmwFast(frame, frame.size)
    }
}
