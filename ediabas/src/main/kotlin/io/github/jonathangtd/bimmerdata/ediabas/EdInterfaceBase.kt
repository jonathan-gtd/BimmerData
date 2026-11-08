/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdInterfaceBase.cs.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import java.io.Closeable

/**
 * Communication interface used by the interpreter (EdInterfaceBase.cs), reduced to the members the
 * opcodes and the job machinery use. Concrete interfaces (OBD/FTDI, ENET, ELM, Bluetooth...) are
 * ported later. Simulation files (.sim), the transmit cache and the interface mutex are not ported:
 * [isSimulationMode] is always false.
 *
 * C# `bool Xxx(byte[] send, out byte[] receive)` methods are mapped to `fun xxx(send): ByteArray?`,
 * where null means `false`.
 */
abstract class EdInterfaceBase : Closeable {
    protected var ediabasProtected: EdiabasNet? = null
    protected var commRepeatsProtected: UInt = 0u
    protected var commParameterProtected: UIntArray? = null
    protected val commAnswerLenProtected: ShortArray = ShortArray(2)
    private var responseCounter: Long = 0

    open var ediabas: EdiabasNet?
        get() = ediabasProtected
        set(value) {
            ediabasProtected = value
        }

    open var ifhName: String? = null
    open var unitName: String? = null
    open var applicationName: String? = null
    open var connectParameter: Any? = null

    abstract fun isValidInterfaceName(name: String): Boolean

    open fun interfaceConnect(): Boolean {
        commRepeatsProtected = 0u
        commParameterProtected = null
        commAnswerLenProtected[0] = 0
        commAnswerLenProtected[1] = 0
        responseCount = 0
        return true
    }

    open fun interfaceDisconnect(): Boolean = true

    /** Simulation (.sim files) is not ported. */
    open fun isSimulationMode(): Boolean = false

    open fun loadSgbdSimFile(fileName: String): Boolean = false

    open fun unloadInterfaceSimFile(): Boolean = true

    open val ubatCurrentSimulation: Long get() = -1
    open val ubatHistorySimulation: Long get() = -1
    open val ignitionCurrentSimulation: Long get() = -1
    open val ignitionHistorySimulation: Long get() = -1

    abstract fun interfaceReset(): Boolean

    abstract fun interfaceBoot(): Boolean

    /** EdInterfaceBase.TransmitData: send a telegram and return the response, or null on failure. */
    abstract fun transmitData(sendData: ByteArray): ByteArray?

    abstract fun transmitFrequent(sendData: ByteArray): Boolean

    abstract fun receiveFrequent(): ByteArray?

    abstract fun stopFrequent(): Boolean

    abstract fun rawData(sendData: ByteArray): ByteArray?

    abstract fun transmitCancel(cancel: Boolean): Boolean

    var commRepeats: UInt
        get() = commRepeatsProtected
        set(value) {
            commRepeatsProtected = value
        }

    open var commParameter: UIntArray?
        get() = commParameterProtected
        set(value) {
            commParameterProtected = value
        }

    open var commAnswerLen: ShortArray
        get() = commAnswerLenProtected
        set(value) {
            if (value.size >= 2) {
                commAnswerLenProtected[0] = value[0]
                commAnswerLenProtected[1] = value[1]
                ediabasProtected?.logFormat(
                    EdLogLevel.Ifh, "Answer length: {0:X04} {1:X04}",
                    commAnswerLenProtected[0], commAnswerLenProtected[1],
                )
            }
        }

    abstract val bmwFastProtocol: Boolean

    abstract val interfaceType: String

    abstract val interfaceVersion: UInt

    abstract val interfaceName: String

    open val interfaceVerName: String get() = "IFH-STD Version 7.6.0"

    abstract val keyBytes: ByteArray?

    abstract val state: ByteArray?

    abstract val loopTest: UInt

    abstract val batteryVoltage: Long

    abstract val ignitionVoltage: Long

    abstract fun getPort(index: UInt): Long

    abstract fun setPort(index: UInt, value: UInt)

    abstract fun setProgramVoltage(voltage: UInt)

    abstract fun switchSiRelais(time: UInt)

    abstract val connected: Boolean

    abstract val isEcuConnected: Boolean

    open var responseCount: Long
        get() = synchronized(this) { responseCounter }
        set(value) {
            synchronized(this) { responseCounter = value }
        }

    override fun close() {}

    companion object {
        val CommParameterBmwFast: UIntArray = uintArrayOf(0x0000010Fu, 0x0001C200u, 0x000004B0u, 0x00000014u, 0x0000000Au, 0x00000002u, 0x00001388u)

        @JvmField
        val CommAnswerLenBmwFast: ShortArray = shortArrayOf(0x0000, 0x0000)

        /** Telegram length without checksum. */
        fun telLengthBmwFast(dataBuffer: ByteArray): Int {
            var telLength = dataBuffer[0].toInt() and 0x3F
            if (telLength == 0) { // with length byte
                telLength = if (dataBuffer[3].toInt() == 0) {
                    ((dataBuffer[4].toInt() and 0xFF) shl 8) + (dataBuffer[5].toInt() and 0xFF) + 6
                } else {
                    (dataBuffer[3].toInt() and 0xFF) + 4
                }
            } else {
                telLength += 3
            }
            if (telLength > dataBuffer.size) {
                telLength = dataBuffer.size
            }
            return telLength
        }

        /** Returns (data length, data offset); length is -1 if the buffer is too short. */
        fun dataLengthBmwFast(dataBuffer: ByteArray): Pair<Int, Int> {
            var dataOffset = 3
            var telLength = dataBuffer[0].toInt() and 0x3F
            if (telLength == 0) { // with length byte
                if (dataBuffer[3].toInt() == 0) {
                    dataOffset = 6
                    telLength = ((dataBuffer[4].toInt() and 0xFF) shl 8) + (dataBuffer[5].toInt() and 0xFF)
                } else {
                    dataOffset = 4
                    telLength = dataBuffer[3].toInt() and 0xFF
                }
            }
            if (telLength + dataOffset > dataBuffer.size) {
                return Pair(-1, dataOffset)
            }
            return Pair(telLength, dataOffset)
        }

        fun calcChecksumBmwFast(data: ByteArray, length: Int): Byte {
            var sum = 0
            for (i in 0 until length) sum += data[i].toInt()
            return sum.toByte()
        }
    }
}
