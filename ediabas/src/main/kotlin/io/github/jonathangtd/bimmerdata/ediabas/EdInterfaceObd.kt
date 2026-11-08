/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdInterfaceObd.cs (BMW-FAST subset) with the FTDI transport logic of
 * EdiabasLib/EdFtdiInterfaceAndroid.cs.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import java.io.IOException

/**
 * "STD:OBD" interface (EdInterfaceObd.cs) on top of a [SerialPort], for K+DCAN style cables.
 *
 * Ported: communication parameter handling of concept 0x010F (BMW-FAST, KWP2000 with BMW header
 * over K-line), TransBmwFast/TransKwp2000 (checksum, echo removal, short / one-byte / two-byte
 * length formats, NR78 "response pending" and NR21/NR23 handling, regeneration time), ObdTrans
 * retries, DTR/RTS setup and DSR as ignition / battery state, and the FTDI send/receive timing of
 * EdFtdiInterfaceAndroid.cs.
 *
 * Not ported yet: the communication thread (calls run synchronously on the job thread, which is
 * fine without idle/keep-alive traffic), frequent mode, the transmit cache, EDIC simulation,
 * simulation files, and the other concepts: DS2 (0x0006), KWP2000* (0x010D), D-CAN (0x0110),
 * KWP2000 BMW (0x010C), KWP1281, concept 1/3, TP2.0, ISO-TP. They report EDIABAS_IFH_0014
 * ("concept not implemented") and plug in as further [Protocol] values.
 *
 * Extensions: [safetyGuard] blocks dangerous services before they are sent, [frameListener]
 * reports every telegram with a timestamp, [stats] counts telegrams and protocol waits.
 */
class EdInterfaceObd(
    val port: SerialPort,
    /**
     * The adapter echoes every byte sent (EdInterfaceObd.AdapterEcho, true for plain K+DCAN cables:
     * the K-line is a single wire). Selects DTR on during the session and echo removal.
     */
    val adapterEcho: Boolean = true,
    val safetyGuard: SafetyGuard = SafetyGuard(),
) : EdInterfaceBase() {

    /** Transmit function selected by the concept (C# ParTransmitFunc delegate). */
    enum class Protocol { None, BmwFast }

    enum class FrameDirection { Tx, Echo, Rx, Error }

    /** One telegram (or an error note) seen by the interface; [timeNanos] is System.nanoTime(). */
    class Frame(val timeNanos: Long, val direction: FrameDirection, val data: ByteArray, val text: String? = null)

    /** Counters for speed measurement; reset with [resetStats]. */
    class Stats {
        @Volatile var telegrams: Long = 0
        @Volatile var nr78: Long = 0
        @Volatile var retries: Long = 0
        /** Time spent waiting for the regeneration time (protocol pause, not software overhead). */
        @Volatile var regenWaitNanos: Long = 0
    }

    @Volatile
    var frameListener: ((Frame) -> Unit)? = null

    val stats = Stats()

    fun resetStats() {
        stats.telegrams = 0
        stats.nr78 = 0
        stats.retries = 0
        stats.regenWaitNanos = 0
    }

    private val transLock = Any()
    private val sendBuffer = ByteArray(TransBufferSize)
    private val recBuffer = ByteArray(TransBufferSize)
    private val nr78Dict = HashMap<Int, Int>()

    @Volatile
    private var cancelRequested = false

    private var connectedProtected = false
    private var protocol = Protocol.None
    private var currentBaudRate = 9600
    private var currentParity = SerialParity.None
    private var currentDataBits = 8
    private var ecuConnected = false
    private var lastResponseNanos: Long? = null
    private var keyBytesProtected = ByteArray(0)

    private var parTimeoutStd = 0
    private var parTimeoutTelEnd = 0
    private var parInterbyteTime = 0
    private var parRegenTime = 0
    private var parRequestTimeNr21 = 0
    private var parRequestTimeNr23 = 0
    private var parRetryNr21 = 0
    private var parRetryNr23 = 0
    private var parTimeoutNr78 = 0
    private var parRetryNr78 = 0
    private var parChecksumByUser = false
    private var parChecksumNoCheck = false
    private var parSendSetDtr = false
    private var parHasKeyBytes = false
    private var parSupportFrequent = false

    /** EdInterfaceObd.cs AddRecTimeout: extra receive delay for internal signal transitions (config ObdAddRecTimeout). */
    var addRecTimeout = 20

    /** EdInterfaceObd.cs DtrTimeCorrFtdi: DTR hold time correction in ms (config ObdDtrTimeCorrFtdi). */
    var dtrTimeCorr = 0.3

    var batteryVoltageValue = 12000
    var ignitionVoltageValue = 12000

    override var ediabas: EdiabasNet?
        get() = ediabasProtected
        set(value) {
            ediabasProtected = value
            if (value == null) return
            value.getConfigProperty("ObdAddRecTimeout")?.let { addRecTimeout = EdiabasNet.stringToValue(it).toInt() }
            value.getConfigProperty("ObdBatteryVoltage")?.let { batteryVoltageValue = EdiabasNet.stringToValue(it).toInt() }
            value.getConfigProperty("ObdIgnitionVoltage")?.let { ignitionVoltageValue = EdiabasNet.stringToValue(it).toInt() }
        }

    // ---- communication parameters ----

    override var commParameter: UIntArray?
        get() = commParameterProtected
        set(value) {
            commParameterProtected = value
            commAnswerLenProtected[0] = 0
            commAnswerLenProtected[1] = 0

            protocol = Protocol.None
            parTimeoutStd = 0
            parTimeoutTelEnd = 0
            parInterbyteTime = 0
            parRegenTime = 0
            parRequestTimeNr21 = 0
            parRequestTimeNr23 = 0
            parRetryNr21 = 0
            parRetryNr23 = 0
            parTimeoutNr78 = 0
            parRetryNr78 = 0
            parChecksumByUser = false
            parChecksumNoCheck = false
            parSendSetDtr = false
            parHasKeyBytes = false
            parSupportFrequent = false
            keyBytesProtected = ByteArray(0)
            nr78Dict.clear()
            ecuConnected = false
            lastResponseNanos = null // C#: DateTime.MinValue.Ticks

            if (value == null) return // clear parameter
            if (value.isEmpty()) {
                logFormat("*** Parameter length too short: {0}", value.size)
                setError(ErrorCodes.EDIABAS_IFH_0041)
                return
            }
            ediabasProtected?.logString(
                EdLogLevel.Ifh,
                "$interfaceName CommParameter Port=${port.name}: " + value.joinToString(" ") { "%08X".format(it.toInt()) },
            )

            val baudRate: Int
            val parity: SerialParity
            val dataBits = 8
            var stateDtr = false
            val stateRts = false
            val concept = value[0].toInt()
            when (concept) {
                0x010F -> { // BMW-FAST
                    if (value.size < 7) {
                        setError(ErrorCodes.EDIABAS_IFH_0041)
                        return
                    }
                    if (value.size >= 8) {
                        evalChecksumPar(value[7])
                    }
                    baudRate = value[1].toInt()
                    parity = SerialParity.None
                    stateDtr = adapterEcho
                    protocol = Protocol.BmwFast
                    parTimeoutStd = value[2].toInt()
                    parRegenTime = value[3].toInt()
                    parTimeoutTelEnd = value[4].toInt()
                    parRetryNr78 = value[5].toInt()
                    parTimeoutNr78 = value[6].toInt()
                    parSendSetDtr = !adapterEcho
                }

                // Planned: DS2 (0x0006), KWP2000* (0x010D), D-CAN (0x0110). See EdInterfaceObd.cs.
                0x0006, 0x010D, 0x0110 -> {
                    logFormat("*** Concept not implemented yet: {0:X04}", concept)
                    setError(ErrorCodes.EDIABAS_IFH_0014)
                    return
                }

                else -> {
                    logFormat("*** Concept not implemented: {0:X04}", concept)
                    setError(ErrorCodes.EDIABAS_IFH_0014)
                    return
                }
            }

            // EdFtdiInterface.InterfaceSetConfig + InterfaceSetDtr/Rts
            try {
                if (!port.isOpen) throw IOException("port not open")
                port.setParameters(baudRate, dataBits, parity.toPortParity(), 1)
                port.purgeInput()
                port.setDtr(stateDtr)
                port.setRts(stateRts)
            } catch (ex: IOException) {
                logFormat("*** Set interface configuration failed: {0}", ex.toString())
                setError(ErrorCodes.EDIABAS_IFH_0041)
                return
            }
            currentBaudRate = baudRate
            currentParity = parity
            currentDataBits = dataBits
        }

    private fun evalChecksumPar(value: UInt) {
        parChecksumByUser = (value and 0x01u) == 0u
        parChecksumNoCheck = (value and 0x02u) != 0u
    }

    // ---- connection ----

    override fun isValidInterfaceName(name: String): Boolean {
        val parts = name.split(':')
        return parts.size > 1 && parts[0].equals("STD", ignoreCase = true) &&
            (parts[1].equals("OBD", ignoreCase = true) || parts[1].equals("OMITEC", ignoreCase = true))
    }

    override fun interfaceConnect(): Boolean {
        if (!super.interfaceConnect()) {
            setError(ErrorCodes.EDIABAS_IFH_0018)
            return false
        }
        currentBaudRate = 9600
        currentParity = SerialParity.None
        currentDataBits = 8
        if (port.isOpen && connectedProtected) return true
        try {
            port.open()
            port.setParameters(9600, 8, SerialPort.Parity.None, 1)
            port.setDtr(false)
            port.setRts(false)
            port.purgeInput()
        } catch (ex: IOException) {
            logFormat("*** Connect failed: {0}", ex.toString())
            connectedProtected = false
            port.close()
            setError(ErrorCodes.EDIABAS_IFH_0018)
            return false
        }
        connectedProtected = true
        ediabasProtected?.logString(EdLogLevel.Ifh, "Connected: ${port.name}")
        return true
    }

    override fun interfaceDisconnect(): Boolean {
        super.interfaceDisconnect()
        connectedProtected = false
        port.close()
        return true
    }

    override fun interfaceReset(): Boolean {
        if (!connected) {
            setError(ErrorCodes.EDIABAS_IFH_0056)
        }
        commParameter = null
        return true
    }

    override fun interfaceBoot(): Boolean {
        commParameter = null
        return true
    }

    override fun close() {
        interfaceDisconnect()
    }

    // ---- transmission ----

    override fun transmitData(sendData: ByteArray): ByteArray? {
        if (commParameterProtected == null) {
            setError(ErrorCodes.EDIABAS_IFH_0006)
            return null
        }
        if (sendData.size > sendBuffer.size) {
            setError(ErrorCodes.EDIABAS_IFH_0031)
            return null
        }
        val blocked = when (protocol) {
            Protocol.BmwFast -> safetyGuard.checkBmwFastTelegram(sendData)
            Protocol.None -> null
        }
        if (blocked != null) {
            logString("*** $blocked")
            emitFrame(FrameDirection.Error, sendData, blocked)
            setError(ErrorCodes.EDIABAS_IFH_0006)
            return null
        }
        synchronized(transLock) {
            System.arraycopy(sendData, 0, sendBuffer, 0, sendData.size)
            val (errorCode, receiveLength) = obdTrans(sendBuffer, sendData.size)
            if (errorCode != ErrorCodes.EDIABAS_ERR_NONE) {
                setError(errorCode)
                return null
            }
            return recBuffer.copyOf(receiveLength)
        }
    }

    // EdInterfaceObd.cs: ObdTrans, returns (error, receive length)
    private fun obdTrans(sendData: ByteArray, sendDataLength: Int): Pair<ErrorCodes, Int> {
        if (!port.isOpen) return Pair(ErrorCodes.EDIABAS_IFH_0019, 0)
        if (protocol == Protocol.None) return Pair(ErrorCodes.EDIABAS_IFH_0006, 0)

        var errorCode = ErrorCodes.EDIABAS_ERR_NONE
        var receiveLength = 0
        var retries = commRepeatsProtected.toLong()
        val retryComm = ediabasProtected?.getConfigProperty("RetryComm")
        if (retryComm != null && EdiabasNet.stringToValue(retryComm) == 0L) {
            retries = 0
        }
        for (i in 0..retries) {
            if (i > 0) stats.retries++
            val result = when (protocol) {
                Protocol.BmwFast -> transBmwFast(sendData, sendDataLength, recBuffer)
                Protocol.None -> Pair(ErrorCodes.EDIABAS_IFH_0006, 0)
            }
            errorCode = result.first
            receiveLength = result.second
            if (errorCode == ErrorCodes.EDIABAS_ERR_NONE) return result
            if (errorCode == ErrorCodes.EDIABAS_IFH_0003) break // interface error
            if (errorCode == ErrorCodes.EDIABAS_IFH_0011) break // unknown interface
            if (sendDataLength <= 0) break // no data to send
            if (cancelRequested) break
        }
        if (errorCode == ErrorCodes.EDIABAS_IFH_0011) {
            errorCode = ErrorCodes.EDIABAS_IFH_0010 // hide interface error
        }
        return Pair(errorCode, receiveLength)
    }

    // EdInterfaceObd.cs: TransBmwFast -> TransKwp2000(..., enableLogging = true)
    private fun transBmwFast(sendData: ByteArray, sendDataLength: Int, receiveData: ByteArray): Pair<ErrorCodes, Int> {
        var nrSendCount = 0
        restart@ while (true) {
            if (sendDataLength > 0) {
                nr78Dict.clear()
                // C# uses the header length only; clamped to the bytes the job really provided
                var sendLength = minOf(telLengthBmwFast(sendData), sendDataLength)
                if (!parChecksumByUser) {
                    sendData[sendLength] = calcChecksumBmwFast(sendData, sendLength)
                }
                sendLength++
                logData(sendData, 0, sendLength, "Send")

                val regenStart = System.nanoTime()
                val lastResponse = lastResponseNanos
                if (lastResponse != null) {
                    while (System.nanoTime() - lastResponse < parRegenTime * NanosPerMs && !cancelRequested) {
                        Thread.sleep(1)
                    }
                }
                stats.regenWaitNanos += System.nanoTime() - regenStart

                emitFrame(FrameDirection.Tx, sendData.copyOf(sendLength))
                stats.telegrams++
                if (!sendTelegram(sendData, sendLength, parSendSetDtr, parInterbyteTime)) {
                    errorNote("*** Sending failed")
                    return Pair(ErrorCodes.EDIABAS_IFH_0003, 0)
                }
                if (adapterEcho) {
                    // remove remote echo
                    if (!receiveBytes(receiveData, 0, sendLength, EchoTimeout, parTimeoutTelEnd)) {
                        errorNote("*** No echo received")
                        receiveBytes(receiveData, 0, receiveData.size, EchoTimeout, parTimeoutTelEnd, true)
                        return Pair(ErrorCodes.EDIABAS_IFH_0003, 0)
                    }
                    logData(receiveData, 0, sendLength, "Echo")
                    emitFrame(FrameDirection.Echo, receiveData.copyOf(sendLength))
                    for (i in 0 until sendLength) {
                        if (receiveData[i] != sendData[i]) {
                            errorNote("*** Echo incorrect")
                            receiveBytes(receiveData, 0, receiveData.size, parTimeoutStd, parTimeoutTelEnd, true)
                            return Pair(ErrorCodes.EDIABAS_IFH_0003, 0)
                        }
                    }
                }
            }

            while (true) {
                val timeout = if (nr78Dict.isNotEmpty()) parTimeoutNr78 else parTimeoutStd
                // header bytes
                var headLength = 4
                if (!receiveBytes(receiveData, 0, headLength, timeout, parTimeoutTelEnd)) {
                    errorNote("*** No header received")
                    return Pair(ErrorCodes.EDIABAS_IFH_0009, 0)
                }
                if ((receiveData[0].toInt() and 0xC0) != 0x80) {
                    logData(receiveData, 0, 4, "Head")
                    errorNote("*** Invalid header")
                    receiveBytes(receiveData, 0, receiveData.size, timeout, parTimeoutTelEnd, true)
                    return Pair(ErrorCodes.EDIABAS_IFH_0009, 0)
                }
                if ((receiveData[0].toInt() and 0x3F) == 0x00 && receiveData[3].toInt() == 0) {
                    // 2 byte length
                    if (!receiveBytes(receiveData, headLength, 2, timeout, parTimeoutTelEnd)) {
                        errorNote("*** No length received")
                        return Pair(ErrorCodes.EDIABAS_IFH_0009, 0)
                    }
                    headLength += 2
                }

                val recLength = telLengthBmwFast(receiveData)
                if (!receiveBytes(receiveData, headLength, recLength - headLength + 1, parTimeoutTelEnd, parTimeoutTelEnd)) {
                    logData(receiveData, 0, 4, "*** Rec")
                    errorNote("*** No tail received: Length=$recLength")
                    return Pair(ErrorCodes.EDIABAS_IFH_0009, 0)
                }
                logData(receiveData, 0, recLength + 1, "Resp")
                emitFrame(FrameDirection.Rx, receiveData.copyOf(recLength + 1))
                if (!parChecksumNoCheck) {
                    if (calcChecksumBmwFast(receiveData, recLength) != receiveData[recLength]) {
                        errorNote("*** Checksum incorrect")
                        receiveBytes(receiveData, 0, receiveData.size, timeout, parTimeoutTelEnd, true)
                        return Pair(ErrorCodes.EDIABAS_IFH_0009, 0)
                    }
                }

                var dataLen = receiveData[0].toInt() and 0x3F
                var dataStart = 3
                if (dataLen == 0) { // with length byte
                    dataLen = receiveData[3].toInt() and 0xFF
                    dataStart++
                }
                val negative = dataLen == 3 && (receiveData[dataStart].toInt() and 0xFF) == 0x7F
                val nrc = if (negative) receiveData[dataStart + 2].toInt() and 0xFF else 0
                if (negative) {
                    var nrRequestTime = 0
                    var nrRetries = 0
                    if (nrc == 0x21) {
                        nrRequestTime = parRequestTimeNr21
                        nrRetries = parRetryNr21
                    }
                    if (nrc == 0x23) {
                        nrRequestTime = parRequestTimeNr23
                        nrRetries = parRetryNr23
                    }
                    if (nrRequestTime > 0 && nrRetries > 0) {
                        if (nrSendCount >= nrRetries) {
                            logString("*** NR21/23 exceeded")
                            break
                        }
                        logString("NR21/23 request")
                        Thread.sleep(nrRequestTime.toLong())
                        nrSendCount++
                        continue@restart
                    }
                }

                val deviceAddr = receiveData[2].toInt() and 0xFF
                if (negative && nrc == 0x78) { // negative response 0x78: response pending
                    stats.nr78++
                    nr78DictAdd(deviceAddr)
                } else {
                    nr78DictRemove(deviceAddr)
                    break
                }
                if (nr78Dict.isEmpty()) break
                if (cancelRequested) return Pair(ErrorCodes.EDIABAS_IFH_0009, 0)
            }
            break
        }

        lastResponseNanos = System.nanoTime()
        return Pair(ErrorCodes.EDIABAS_ERR_NONE, telLengthBmwFast(receiveData) + 1)
    }

    private fun nr78DictAdd(deviceAddr: Int) {
        val retries = nr78Dict.remove(deviceAddr)
        if (retries != null) {
            val count = retries + 1
            if (count <= parRetryNr78) {
                logFormat("NR78({0:X02}) count={1}", deviceAddr, count)
                nr78Dict[deviceAddr] = count
            } else {
                logFormat("*** NR78({0:X02}) exceeded", deviceAddr)
            }
        } else {
            logFormat("NR78({0:X02}) added", deviceAddr)
            nr78Dict[deviceAddr] = 0
        }
    }

    private fun nr78DictRemove(deviceAddr: Int) {
        if (nr78Dict.remove(deviceAddr) != null) {
            logFormat("NR78({0:X02}) removed", deviceAddr)
        }
    }

    // EdInterfaceObd.cs: SendData(sendData, length, setDtr, interbyteTime)
    private fun sendTelegram(data: ByteArray, length: Int, setDtr: Boolean, interbyteTime: Int): Boolean {
        if (length <= 0) return true
        try {
            if (interbyteTime > 0) {
                val interbyteNanos = ((interbyteTime + byteTimeMs()) * NanosPerMs).toLong()
                port.purgeInput()
                val single = ByteArray(1)
                for (i in 0 until length) {
                    single[0] = data[i]
                    val dtrHandling = (i + 1) >= length && setDtr
                    val startTime = System.nanoTime()
                    sendRaw(single, 1, dtrHandling, keepInBuffer = true)
                    busyWaitUntil(startTime + interbyteNanos)
                }
            } else {
                sendRaw(data, length, setDtr, keepInBuffer = false)
            }
        } catch (ex: IOException) {
            logFormat("*** Stream failure: {0}", ex.toString())
            return false
        }
        return true
    }

    // EdFtdiInterface.InterfacePurgeInBuffer + InterfaceSendData
    private fun sendRaw(data: ByteArray, length: Int, setDtr: Boolean, keepInBuffer: Boolean) {
        if (!keepInBuffer) port.purgeInput()
        val byteTime = byteTimeMs()
        if (setDtr) {
            val waitNanos = ((dtrTimeCorr + byteTime * length) * NanosPerMs).toLong()
            port.setDtr(true)
            val startTime = System.nanoTime()
            port.write(data, 0, length)
            busyWaitUntil(startTime + waitNanos)
            port.setDtr(false)
        } else {
            val waitTime = (byteTime * length).toLong()
            port.write(data, 0, length)
            if (waitTime > 10) Thread.sleep(waitTime)
        }
    }

    private fun byteTimeMs(): Double {
        val bitCount = if (currentParity == SerialParity.None) currentDataBits + 2 else currentDataBits + 3
        return 1000.0 / currentBaudRate * bitCount
    }

    private fun busyWaitUntil(endNanos: Long) {
        while (System.nanoTime() < endNanos) {
            // spin: Thread.sleep is far too coarse for DTR timing
        }
    }

    /**
     * EdInterfaceObd.cs ReceiveData + EdFtdiInterface.InterfaceReceiveData: waits [timeout] ms for
     * the first byte, then at most [timeoutTelEnd] ms for each further chunk. Both get
     * [addRecTimeout] and the port's [SerialPort.extraReceiveTimeoutMs] added.
     */
    private fun receiveBytes(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeout: Int,
        timeoutTelEnd: Int,
        logResponse: Boolean = false,
    ): Boolean {
        if (length <= 0) return true
        val extra = addRecTimeout + port.extraReceiveTimeoutMs
        val firstNanos = (timeout + extra) * NanosPerMs
        val nextNanos = (timeoutTelEnd + extra) * NanosPerMs
        var recLen = 0
        var deadline = System.nanoTime() + firstNanos
        try {
            while (recLen < length) {
                if (cancelRequested) break
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                val waitMs = minOf((remaining + NanosPerMs - 1) / NanosPerMs, ReadSliceMs).toInt()
                val n = port.read(buffer, offset + recLen, length - recLen, waitMs)
                if (n > 0) {
                    recLen += n
                    deadline = System.nanoTime() + nextNanos
                }
            }
        } catch (ex: IOException) {
            logFormat("*** Stream failure: {0}", ex.toString())
            return false
        }
        if (logResponse && recLen > 0) {
            logData(buffer, offset, recLen, "Rec ")
        }
        if (recLen < length) return false
        responseCount = responseCount + 1
        return true
    }

    // ---- frequent mode (not supported by BMW-FAST) ----

    override fun transmitFrequent(sendData: ByteArray): Boolean {
        ediabasProtected?.logData(EdLogLevel.Ifh, sendData, 0, sendData.size, "Send Frequent")
        if (commParameterProtected == null || !parSupportFrequent || sendData.isEmpty()) {
            logString("Frequent not supported")
            setError(ErrorCodes.EDIABAS_IFH_0006)
            return false
        }
        return false
    }

    override fun receiveFrequent(): ByteArray? {
        if (commParameterProtected == null) {
            setError(ErrorCodes.EDIABAS_IFH_0006)
            return null
        }
        return ByteArray(0)
    }

    override fun stopFrequent(): Boolean = true

    // EdInterfaceObd.cs RawData: answers locally, nothing goes on the bus
    override fun rawData(sendData: ByteArray): ByteArray? {
        ediabasProtected?.logData(EdLogLevel.Ifh, sendData, 0, sendData.size, "Send Raw")
        val receiveData = if (sendData.size == 2 && sendData[0] == 0xF1.toByte() && sendData[1] == 0x07.toByte()) {
            byteArrayOf(0x05, 0x03, 0x00)
        } else {
            ByteArray(0)
        }
        ediabasProtected?.logData(EdLogLevel.Ifh, receiveData, 0, receiveData.size, "Resp Raw")
        return receiveData
    }

    /** Called with true to abort (from any thread), with false by the interpreter at job start. */
    override fun transmitCancel(cancel: Boolean): Boolean {
        cancelRequested = cancel
        if (cancel) port.cancelRead()
        return true
    }

    // ---- properties ----

    override val bmwFastProtocol: Boolean get() = protocol == Protocol.BmwFast

    override val interfaceType: String get() = "OBD"

    override val interfaceVersion: UInt get() = 209u

    override val interfaceName: String get() = "STD:OBD"

    override val keyBytes: ByteArray?
        get() {
            logString("Read key bytes")
            if (!connected) {
                setError(ErrorCodes.EDIABAS_IFH_0056)
                return null
            }
            if (commParameterProtected != null && parHasKeyBytes && ecuConnected) {
                return keyBytesProtected
            }
            return ByteArray(0)
        }

    override val state: ByteArray?
        get() {
            if (!connected) {
                setError(ErrorCodes.EDIABAS_IFH_0056)
                return null
            }
            return byteArrayOf(0x00, if (getDsrState()) 0x00 else 0x30)
        }

    override val loopTest: UInt
        get() {
            logString("Loop test")
            if (!connected) {
                setError(ErrorCodes.EDIABAS_IFH_0056)
                return 0u
            }
            return 1u
        }

    override val batteryVoltage: Long
        get() {
            logString("Read battery voltage")
            if (!connected) {
                setError(ErrorCodes.EDIABAS_IFH_0056)
                return Long.MIN_VALUE
            }
            val voltage = if (getDsrState()) batteryVoltageValue.toLong() else 0L
            logFormat("Battery voltage: {0}", voltage)
            return voltage
        }

    /** Plain FTDI cables have no ignition status command: the ignition comes from DSR. */
    override val ignitionVoltage: Long
        get() {
            logString("Read ignition voltage")
            if (!connected) {
                setError(ErrorCodes.EDIABAS_IFH_0056)
                return Long.MIN_VALUE
            }
            val voltage = if (getDsrState()) ignitionVoltageValue.toLong() else 0L
            logFormat("Ignition voltage from DSR: {0}", voltage)
            return voltage
        }

    /** DSR line (ignition on K+DCAN cables), null if it cannot be read. No error is raised. */
    fun readDsr(): Boolean? = try {
        if (port.isOpen) port.getDsr() else null
    } catch (_: IOException) {
        null
    }

    private fun getDsrState(): Boolean {
        val dsr = readDsr()
        if (dsr != null) return dsr
        setError(ErrorCodes.EDIABAS_IFH_0019)
        return false
    }

    override fun getPort(index: UInt): Long = 0

    override fun setPort(index: UInt, value: UInt) {}

    override fun setProgramVoltage(voltage: UInt) {}

    override fun switchSiRelais(time: UInt) {}

    override val connected: Boolean get() = connectedProtected && port.isOpen

    override val isEcuConnected: Boolean get() = ecuConnected

    // ---- helpers ----

    private fun setError(error: ErrorCodes) {
        ediabasProtected?.setError(error)
    }

    private fun logString(text: String) {
        ediabasProtected?.logString(EdLogLevel.Ifh, text)
    }

    private fun logFormat(format: String, vararg args: Any?) {
        ediabasProtected?.logFormat(EdLogLevel.Ifh, format, *args)
    }

    private fun logData(data: ByteArray, offset: Int, length: Int, info: String) {
        ediabasProtected?.logData(EdLogLevel.Ifh, data, offset, length, info)
    }

    private fun errorNote(text: String) {
        logString(text)
        emitFrame(FrameDirection.Error, ByteArray(0), text.removePrefix("*** "))
    }

    private fun emitFrame(direction: FrameDirection, data: ByteArray, text: String? = null) {
        frameListener?.invoke(Frame(System.nanoTime(), direction, data, text))
    }

    /** EdInterfaceObd.SerialParity */
    enum class SerialParity { None, Odd, Even, Mark, Space }

    private fun SerialParity.toPortParity(): SerialPort.Parity = when (this) {
        SerialParity.None -> SerialPort.Parity.None
        SerialParity.Odd -> SerialPort.Parity.Odd
        SerialParity.Even -> SerialPort.Parity.Even
        SerialParity.Mark -> SerialPort.Parity.Mark
        SerialParity.Space -> SerialPort.Parity.Space
    }

    companion object {
        /** Transmit buffer size (EdInterfaceObd.cs TransBufferSize). */
        const val TransBufferSize = 0x800

        /** Echo timeout in ms (EdInterfaceObd.cs EchoTimeout). */
        const val EchoTimeout = 100

        private const val NanosPerMs = 1_000_000L

        /** Port reads are sliced so that [transmitCancel] is seen quickly even if the port cannot be woken up. */
        private const val ReadSliceMs = 20L
    }
}
