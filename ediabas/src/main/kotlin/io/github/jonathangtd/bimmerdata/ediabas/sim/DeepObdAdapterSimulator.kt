/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 *
 * Behaviour modelled on the replacement firmware for ELM327 of EdiabasLib
 * (https://github.com/uholeschak/ediabaslib, CanAdapterElm/main.c, internal_telegram).
 */
package io.github.jonathangtd.bimmerdata.ediabas.sim

import io.github.jonathangtd.bimmerdata.ediabas.SerialPort
import io.github.jonathangtd.bimmerdata.ediabas.deepobd.CanMode
import io.github.jonathangtd.bimmerdata.ediabas.deepobd.DeepObdProtocol
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A Bluetooth adapter plugged into a simulated car, as the raw byte stream the phone sees
 * (what the RFCOMM socket gives). In [Kind.DeepObd] it behaves like the Deep OBD firmware:
 * internal `F1 F1` telegrams are answered by the adapter (echo + answer), everything else goes
 * to the K-line [car] (which echoes and lets the ECUs answer) when the CAN mode reaches the
 * K-line, or is only echoed when the adapter is set to D-CAN.
 */
class DeepObdAdapterSimulator(
    val car: SimulatedKLine,
    var kind: Kind = Kind.DeepObd,
) : SerialPort {

    enum class Kind {
        /** Replacement firmware. */
        DeepObd,

        /** Stock ELM327 firmware: echoes characters, answers AT commands with text and a `>` prompt. */
        StockElm,

        /** Plain K-line interface: every byte is echoed, nothing else. */
        EchoOnly,

        /** Nothing comes back (adapter off, wrong device). */
        Silent,
    }

    var adapterType = 0x0005 // SPP_UART Bluetooth
    var firmwareVersion = 0x000F
    var canMode = CanMode.Auto.code
    var voltageTenths = 124
    val serial = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08)

    /** Internal telegrams received, in order. */
    val internalRequests: MutableList<ByteArray> = java.util.Collections.synchronizedList(ArrayList())

    override val name: String = "Simulated Deep OBD adapter"

    private val lock = ReentrantLock()
    private val own = ArrayDeque<Byte>()
    private val pending = ArrayList<Byte>()
    private val text = StringBuilder()
    private var open = false

    override val isOpen: Boolean get() = lock.withLock { open }

    override fun open() {
        lock.withLock { open = true }
        car.open()
        car.setParameters(115200) // the firmware talks BMW-FAST at 115200 on the K-line
    }

    override fun close() {
        lock.withLock {
            open = false
            own.clear()
            pending.clear()
        }
        car.close()
    }

    override fun setParameters(baudRate: Int, dataBits: Int, parity: SerialPort.Parity, stopBits: Int) = checkOpen()

    override fun write(data: ByteArray, offset: Int, length: Int) {
        checkOpen()
        val bytes = data.copyOfRange(offset, offset + length)
        when (kind) {
            Kind.Silent -> Unit
            Kind.EchoOnly -> lock.withLock { bytes.forEach { own.addLast(it) } }
            Kind.StockElm -> lock.withLock { stockElm(bytes) }
            Kind.DeepObd -> {
                val forward = lock.withLock {
                    pending.addAll(bytes.toList())
                    assemble()
                }
                for (frame in forward) car.write(frame)
            }
        }
    }

    /** Complete telegrams to forward to the car; internal ones are answered here. */
    private fun assemble(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (pending.size >= 4) {
            val head = ByteArray(minOf(pending.size, 6)) { pending[it] }
            val len = telLength(head) ?: return out
            if (pending.size < len) return out
            val frame = ByteArray(len) { pending[it] }
            repeat(len) { pending.removeAt(0) }
            if (DeepObdProtocol.isInternal(frame)) {
                internalRequests += frame
                frame.forEach { own.addLast(it) } // echo
                answer(frame)?.forEach { own.addLast(it) }
            } else if (CanMode.fromCode(canMode)?.reachesKLine == true) {
                out += frame
            } else {
                frame.forEach { own.addLast(it) } // D-CAN mode: echoed, nobody answers on CAN
            }
        }
        return out
    }

    /** Total telegram length including checksum, null until enough header bytes are there. */
    private fun telLength(head: ByteArray): Int? {
        val len = head[0].toInt() and 0x3F
        if (len != 0) return len + 4
        if (head.size < 4) return null
        val l1 = head[3].toInt() and 0xFF
        if (l1 != 0) return l1 + 5
        if (head.size < 6) return null
        return (((head[4].toInt() and 0xFF) shl 8) or (head[5].toInt() and 0xFF)) + 7
    }

    // internal_telegram() of the firmware
    private fun answer(t: ByteArray): ByteArray? {
        val cmd = t[3].toInt() and 0xFF
        fun reply(vararg data: Int): ByteArray {
            val r = ByteArray(4 + data.size + 1)
            r[0] = (0x81 + data.size).toByte() // length counts the command byte
            r[1] = 0xF1.toByte()
            r[2] = 0xF1.toByte()
            r[3] = t[3]
            data.forEachIndexed { i, v -> r[4 + i] = v.toByte() }
            r[r.size - 1] = DeepObdProtocol.checksum(r, r.size - 1)
            return r
        }
        return when {
            t.size == 6 && cmd and 0x7F == 0x02 -> {
                if (cmd and 0x80 == 0) canMode = t[4].toInt() and 0xFF
                reply(canMode)
            }
            t.size == 6 && cmd and 0x7F in 0x00..0x01 -> reply(0)
            t.size == 8 && cmd and 0x7F == 0x06 -> reply(0x55, 0xFF xor 0x55, 0x80 xor 0x55)
            cmd == 0xFE -> reply(0x80) // ELM board: ignition invalid
            cmd == 0xFD -> reply(adapterType shr 8, adapterType and 0xFF, firmwareVersion shr 8, firmwareVersion and 0xFF)
            cmd == 0xFC -> reply(voltageTenths)
            cmd == 0xFB -> reply(*serial.map { it.toInt() and 0xFF }.toIntArray())
            cmd == 0xFA -> reply(0x00, 0x00) // K-line: no clamp status
            else -> null
        }
    }

    private fun stockElm(bytes: ByteArray) {
        bytes.forEach { own.addLast(it) } // ATE1
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == '\r') {
                val cmd = text.toString().trim().uppercase()
                text.clear()
                val response = when {
                    cmd.isEmpty() -> ""
                    cmd == "ATI" -> "ELM327 v1.5"
                    else -> "?"
                }
                if (cmd.isNotEmpty()) "$response\r\r>".toByteArray(Charsets.US_ASCII).forEach { own.addLast(it) }
            } else {
                text.append(c)
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        checkOpen()
        val n = lock.withLock {
            var k = 0
            while (k < length && own.isNotEmpty()) buffer[offset + k++] = own.removeFirst()
            k
        }
        if (n > 0) return n
        if (kind != Kind.DeepObd) {
            Thread.sleep(minOf(timeoutMs.toLong(), 5L))
            return 0
        }
        return car.read(buffer, offset, length, timeoutMs)
    }

    override fun purgeInput() {
        checkOpen()
        lock.withLock { own.clear() }
        car.purgeInput()
    }

    override fun setDtr(value: Boolean) = checkOpen()

    override fun setRts(value: Boolean) = checkOpen()

    override fun getDsr(): Boolean = true

    override fun cancelRead() = car.cancelRead()

    private fun checkOpen() {
        if (!isOpen) throw IOException("Simulated adapter not connected")
    }
}
