/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import java.io.Closeable
import java.io.IOException

/**
 * Byte-level serial transport used by [EdInterfaceObd]: a USB FTDI cable (K+DCAN), later a
 * Bluetooth adapter, or a simulated K-line in tests. It replaces the EdiabasLib "InterfaceXxxFunc"
 * delegate set (EdFtdiInterface, EdBluetoothInterface...) with one small interface.
 *
 * All methods are called from the job thread, except [cancelRead] which may be called from any
 * thread. Failures are reported as [IOException].
 */
interface SerialPort : Closeable {
    enum class Parity { None, Odd, Even, Mark, Space }

    /** Human readable name for logs and the UI (e.g. "FT232R 0403:6001"). */
    val name: String

    val isOpen: Boolean

    /** Opens the device. Calling it on an open port does nothing. */
    @Throws(IOException::class)
    fun open()

    /** Closes the device; never throws. */
    override fun close()

    @Throws(IOException::class)
    fun setParameters(baudRate: Int, dataBits: Int = 8, parity: Parity = Parity.None, stopBits: Int = 1)

    /** Hands [length] bytes to the device (returns before they are physically on the wire). */
    @Throws(IOException::class)
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset)

    /**
     * Copies up to [length] already received bytes into [buffer]. If none is available, waits at
     * most [timeoutMs] for data. Returns the number of bytes copied, 0 on timeout. Bytes beyond
     * [length] stay buffered for the next call.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** Discards received bytes (driver queue and device buffer). */
    @Throws(IOException::class)
    fun purgeInput()

    @Throws(IOException::class)
    fun setDtr(value: Boolean)

    @Throws(IOException::class)
    fun setRts(value: Boolean)

    /** DSR input. On K+DCAN cables it follows the ignition (KL15) line. */
    @Throws(IOException::class)
    fun getDsr(): Boolean

    /**
     * Extra time the transport adds to every receive timeout (USB latency timer, OS scheduling).
     * Only failure paths (no answer) get slower when this is generous.
     */
    val extraReceiveTimeoutMs: Int get() = 0

    /** Wakes up a blocked [read] (it then returns 0). Optional: [EdInterfaceObd] also polls. */
    fun cancelRead() {}
}
