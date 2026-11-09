/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Connection logic follows EdiabasLib/EdBluetoothInterfaceAndroid.cs of EdiabasLib
 * (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0): secure RFCOMM socket for
 * a bonded device, insecure otherwise, a second attempt, then the hidden createRfcommSocket(1).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import io.github.jonathangtd.bimmerdata.ediabas.SerialPort
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Raw byte stream of a Bluetooth classic adapter (Serial Port Profile, RFCOMM), as a
 * [SerialPort]. A reader thread fills a buffer; [read] waits on it with a timeout. Serial
 * parameters and modem lines do not exist over RFCOMM: the module's UART speed is fixed (115200
 * for the Deep OBD firmware with a modified Bluetooth module). Used under
 * [io.github.jonathangtd.bimmerdata.ediabas.deepobd.DeepObdPort].
 */
@SuppressLint("MissingPermission") // checked in open(): BluetoothAccess.hasConnectPermission
class BluetoothSppPort(
    private val context: Context,
    val address: String,
    private val displayName: String,
) : SerialPort {

    override val name: String get() = "Bluetooth $displayName"

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val buffer = ByteArray(BufferSize)
    private var head = 0
    private var count = 0
    private var cancelled = false

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var failure: IOException? = null
    @Volatile private var reader: Thread? = null

    override val isOpen: Boolean get() = socket != null && failure == null

    @Throws(IOException::class)
    override fun open() {
        if (isOpen) return
        close() // a lost connection: start over with a new socket
        if (!BluetoothAccess.hasConnectPermission(context)) throw IOException("Bluetooth permission not granted")
        val adapter = BluetoothAccess.adapter(context) ?: throw IOException("This phone has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = try {
            adapter.getRemoteDevice(address.uppercase())
        } catch (ex: IllegalArgumentException) {
            throw IOException("Invalid Bluetooth address $address")
        }
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            throw IOException("$displayName is a Bluetooth LE device: only Bluetooth classic (SPP) adapters are supported")
        }
        // A running discovery slows RFCOMM down, but cancelling it needs BLUETOOTH_SCAN, which the app
        // does not ask for (it only connects to paired adapters): best effort only.
        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        val s = connectSocket(device)
        failure = null
        lock.withLock {
            head = 0
            count = 0
            cancelled = false
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
        reader = Thread({ readLoop(s) }, "bt-spp-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun connectSocket(device: BluetoothDevice): BluetoothSocket {
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        var last: IOException? = null
        repeat(2) { // "sometimes the second connect is working"
            val s = if (bonded) device.createRfcommSocketToServiceRecord(SppUuid) else device.createInsecureRfcommSocketToServiceRecord(SppUuid)
            try {
                s.connect()
                return s
            } catch (ex: IOException) {
                last = ex
                runCatching { s.close() }
            }
        }
        // hidden API used by EdiabasLib as last resort (channel 1)
        try {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val s = m.invoke(device, 1) as BluetoothSocket
            try {
                s.connect()
                return s
            } catch (ex: IOException) {
                runCatching { s.close() }
                last = ex
            }
        } catch (ex: ReflectiveOperationException) {
            Log.w(Tag, "createRfcommSocket not available", ex)
        }
        throw IOException("Cannot connect to $displayName: ${last?.message ?: "no answer"} (adapter off, out of range or connected to another phone?)")
    }

    private fun readLoop(s: BluetoothSocket) {
        val tmp = ByteArray(512)
        try {
            val stream = s.inputStream
            while (true) {
                val n = stream.read(tmp)
                if (n < 0) throw IOException("Bluetooth connection closed by the adapter")
                if (n == 0) continue
                lock.withLock {
                    for (i in 0 until n) {
                        if (count == buffer.size) { // overflow: drop the oldest byte
                            head = (head + 1) % buffer.size
                            count--
                        }
                        buffer[(head + count) % buffer.size] = tmp[i]
                        count++
                    }
                    dataAvailable.signalAll()
                }
            }
        } catch (ex: IOException) {
            if (socket === s) failure = IOException("Bluetooth connection lost: ${ex.message}")
            lock.withLock { dataAvailable.signalAll() }
        }
    }

    override fun close() {
        val s = socket
        socket = null
        input = null
        output = null
        runCatching { s?.close() }
        reader = null
        lock.withLock {
            count = 0
            dataAvailable.signalAll()
        }
    }

    override fun setParameters(baudRate: Int, dataBits: Int, parity: SerialPort.Parity, stopBits: Int) = checkOpen()

    override fun write(data: ByteArray, offset: Int, length: Int) {
        checkOpen()
        val out = output ?: throw IOException("Bluetooth not connected")
        out.write(data, offset, length)
        out.flush()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        lock.withLock {
            while (count == 0) {
                failure?.let { throw it }
                if (socket == null) throw IOException("Bluetooth not connected")
                if (cancelled) {
                    cancelled = false
                    return 0
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return 0
                dataAvailable.awaitNanos(remaining)
            }
            val n = minOf(length, count)
            for (i in 0 until n) {
                buffer[offset + i] = this.buffer[head]
                head = (head + 1) % this.buffer.size
            }
            count -= n
            return n
        }
    }

    override fun purgeInput() {
        checkOpen()
        lock.withLock {
            head = 0
            count = 0
        }
    }

    override fun setDtr(value: Boolean) = checkOpen()

    override fun setRts(value: Boolean) = checkOpen()

    override fun getDsr(): Boolean {
        checkOpen()
        return true
    }

    override fun cancelRead() = lock.withLock {
        cancelled = true
        dataAvailable.signalAll()
    }

    private fun checkOpen() {
        failure?.let { throw it }
        if (socket == null) throw IOException("Bluetooth not connected")
    }

    companion object {
        private const val Tag = "BluetoothSppPort"
        private const val BufferSize = 8192
        val SppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
