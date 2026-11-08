/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * FTDI transport modelled on EdiabasLib/EdFtdiInterfaceAndroid.cs
 * (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import io.github.jonathangtd.bimmerdata.ediabas.SerialPort
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * K+DCAN (FT232R) cable through usb-serial-for-android.
 *
 * Reception: a [SerialInputOutputManager] thread keeps [ReadQueueBuffers] USB read requests
 * posted at all times (usb-serial-for-android 3.x read queue) and appends everything to a ring
 * buffer that [read] consumes, like the ReadQueue of EdFtdiInterfaceAndroid.cs.
 *
 * Latency timer: the FT232R holds received bytes until 62 are pending or the latency timer
 * expires, so every response chunk reaches the phone up to "latency" ms late. EdiabasLib uses
 * 50 ms on Android ("large default value required to prevent data loss"): with its single
 * read request, a short timer means many small USB packets and gaps without a posted request
 * in which the chip's 256 byte receive FIFO can overflow. Here several requests are always
 * queued, so the chip can always flush and a short timer is safe; [latencyTimerMs] defaults to
 * [DefaultLatencyTimerMs] (2 ms) to keep the added delay per chunk small. If data loss shows up
 * in the car (checksum / tail errors), raise it (16 = FTDI default, 50 = EdiabasLib).
 *
 * Receive timeouts: EdiabasLib adds 1000 ms on Android; here [extraReceiveTimeoutMs] is
 * 100 ms + latency, enough for USB and scheduling delays. It only lengthens failure paths.
 */
class UsbFtdiSerialPort(
    private val usbManager: UsbManager,
    val device: UsbDevice,
    private val latencyTimerMs: Int = DefaultLatencyTimerMs,
) : SerialPort {

    override val name: String =
        "%s %04X:%04X".format(device.productName ?: "FTDI", device.vendorId, device.productId)

    override val extraReceiveTimeoutMs: Int get() = 100 + latencyTimerMs

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val ring = ByteArray(RingSize)
    private var ringStart = 0
    private var ringCount = 0
    private var cancelled = false

    @Volatile private var usbPort: UsbSerialPort? = null
    @Volatile private var ioManager: SerialInputOutputManager? = null
    @Volatile private var ioError: Exception? = null

    /** Latency timer actually set in the chip (read back after open). */
    @Volatile var latencyTimerActual: Int = -1
        private set

    override val isOpen: Boolean get() = usbPort != null && ioError == null

    private val listener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            lock.withLock {
                for (b in data) {
                    if (ringCount == RingSize) { // overflow: drop the oldest byte
                        ringStart = (ringStart + 1) % RingSize
                        ringCount--
                    }
                    ring[(ringStart + ringCount) % RingSize] = b
                    ringCount++
                }
                dataAvailable.signalAll()
            }
        }

        override fun onRunError(e: Exception) {
            ioError = e
            lock.withLock { dataAvailable.signalAll() }
        }
    }

    override fun open() {
        if (usbPort != null && ioError == null) return
        close()
        if (!usbManager.hasPermission(device)) throw IOException("No USB permission for $name")
        val connection = usbManager.openDevice(device) ?: throw IOException("Cannot open $name")
        val port = FtdiSerialDriver(device).ports.firstOrNull() ?: run {
            connection.close()
            throw IOException("$name has no serial port")
        }
        try {
            port.open(connection)
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val ftdi = port as FtdiSerialDriver.FtdiSerialPort
            ftdi.latencyTimer = latencyTimerMs
            latencyTimerActual = ftdi.latencyTimer
            if (latencyTimerActual != latencyTimerMs) throw IOException("Setting the latency timer failed ($latencyTimerActual)")
            port.dtr = false
            port.rts = false
            lock.withLock { ringCount = 0 }
            ioError = null
            val manager = SerialInputOutputManager(port, listener)
            manager.readTimeout = 0 // UsbRequest based reads, required by the read queue
            manager.readBufferSize = UsbBufferSize
            manager.setReadQueue(ReadQueueBuffers)
            manager.start()
            usbPort = port
            ioManager = manager
        } catch (ex: Exception) {
            runCatching { port.close() }
            throw ex as? IOException ?: IOException(ex.message, ex)
        }
    }

    override fun close() {
        ioManager?.let { runCatching { it.stop() } }
        ioManager = null
        usbPort?.let { runCatching { it.close() } }
        usbPort = null
        lock.withLock {
            ringCount = 0
            dataAvailable.signalAll()
        }
    }

    private fun port(): UsbSerialPort {
        ioError?.let { throw IOException("USB connection lost: ${it.message}", it) }
        return usbPort ?: throw IOException("$name not open")
    }

    override fun setParameters(baudRate: Int, dataBits: Int, parity: SerialPort.Parity, stopBits: Int) {
        val p = when (parity) {
            SerialPort.Parity.None -> UsbSerialPort.PARITY_NONE
            SerialPort.Parity.Odd -> UsbSerialPort.PARITY_ODD
            SerialPort.Parity.Even -> UsbSerialPort.PARITY_EVEN
            SerialPort.Parity.Mark -> UsbSerialPort.PARITY_MARK
            SerialPort.Parity.Space -> UsbSerialPort.PARITY_SPACE
        }
        val s = if (stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1
        port().setParameters(baudRate, dataBits, s, p)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        val bytes = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
        port().write(bytes, WriteTimeoutMs)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (length <= 0) return 0
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        lock.withLock {
            while (true) {
                if (ringCount > 0) {
                    val n = minOf(length, ringCount)
                    for (i in 0 until n) {
                        buffer[offset + i] = ring[(ringStart + i) % RingSize]
                    }
                    ringStart = (ringStart + n) % RingSize
                    ringCount -= n
                    return n
                }
                port() // throws if the device is gone
                if (cancelled) {
                    cancelled = false
                    return 0
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return 0
                dataAvailable.awaitNanos(remaining)
            }
        }
    }

    /**
     * Purges the chip buffers and the received data. Both directions are purged: before a
     * request nothing may be pending in either, and it avoids depending on the argument order of
     * purgeHwBuffers, which differs between library versions (EdiabasLib passes (true, false)).
     */
    override fun purgeInput() {
        port().purgeHwBuffers(true, true)
        lock.withLock { ringCount = 0 }
    }

    override fun setDtr(value: Boolean) {
        port().dtr = value
    }

    override fun setRts(value: Boolean) {
        port().rts = value
    }

    override fun getDsr(): Boolean = port().dsr

    override fun cancelRead() {
        lock.withLock {
            cancelled = true
            dataAvailable.signalAll()
        }
    }

    companion object {
        const val FtdiVendorId = 0x0403
        const val DefaultLatencyTimerMs = 2
        private const val WriteTimeoutMs = 500
        private const val UsbBufferSize = 4096
        private const val ReadQueueBuffers = 4
        private const val RingSize = 0x4000
    }
}
