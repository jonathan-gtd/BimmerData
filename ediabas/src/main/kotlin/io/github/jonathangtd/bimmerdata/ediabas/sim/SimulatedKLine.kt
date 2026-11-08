/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas.sim

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceBase
import io.github.jonathangtd.bimmerdata.ediabas.SerialPort
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A K+DCAN cable plugged into a simulated K-line, as a [SerialPort]: every written byte comes back
 * as echo (single-wire bus), and complete BMW-FAST requests with a valid checksum are answered by
 * [responder] after a delay, at the line's byte rate. With [realisticTiming] off every byte is
 * available immediately, which isolates the software cost for measurements.
 *
 * No threads: replies are scheduled at write time with a "visible from" timestamp per byte.
 */
class SimulatedKLine(
    /** ECU behaviour: complete request telegram (checksum included) to the replies, empty = silent. */
    var responder: (request: ByteArray) -> List<Reply>,
    var realisticTiming: Boolean = true,
    /** Baud rate the ECU talks at; requests sent at another rate are not understood. */
    var ecuBaudRate: Int = 115200,
) : SerialPort {

    /** One ECU telegram, sent [delayMs] after the end of the request (or of the previous reply). */
    class Reply(val telegram: ByteArray, val delayMs: Int = DefaultP2Ms)

    override val name: String = "Simulated K-line"

    /** DSR as reported by the cable: ignition on. */
    @Volatile var dsrState: Boolean = true

    /** Echo on/off and an optional corruption of the echoed bytes (wiring fault simulation). */
    @Volatile var echo: Boolean = true
    @Volatile var echoTransform: ((ByteArray) -> ByteArray)? = null

    val writes: MutableList<ByteArray> = java.util.Collections.synchronizedList(ArrayList())
    val requests: MutableList<ByteArray> = java.util.Collections.synchronizedList(ArrayList())
    @Volatile var checksumErrors: Int = 0
        private set
    @Volatile var baudRate: Int = 9600
        private set
    @Volatile var dtr: Boolean = false
        private set
    @Volatile var rts: Boolean = false
        private set

    private class TimedByte(val visibleAt: Long, val value: Byte)

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private val rxQueue = ArrayDeque<TimedByte>()
    private val pending = ArrayList<Byte>()
    private var lineBusyUntil = 0L
    private var open = false
    private var cancelled = false

    override val isOpen: Boolean get() = lock.withLock { open }

    override fun open() = lock.withLock { open = true }

    override fun close() = lock.withLock {
        open = false
        rxQueue.clear()
        pending.clear()
        dataAvailable.signalAll()
    }

    override fun setParameters(baudRate: Int, dataBits: Int, parity: SerialPort.Parity, stopBits: Int) {
        checkOpen()
        this.baudRate = baudRate
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        checkOpen()
        val bytes = data.copyOfRange(offset, offset + length)
        writes.add(bytes)
        lock.withLock {
            val now = System.nanoTime()
            var t = maxOf(now, lineBusyUntil)
            val echoBytes = echoTransform?.invoke(bytes) ?: bytes
            for (b in echoBytes) {
                t += byteNanos()
                if (echo) rxQueue.addLast(TimedByte(if (realisticTiming) t else now, b))
            }
            lineBusyUntil = if (realisticTiming) t else now
            pending.addAll(bytes.toList())
            assembleRequests()
            dataAvailable.signalAll()
        }
    }

    /** Extracts complete telegrams from the written bytes and schedules the ECU replies. */
    private fun assembleRequests() {
        while (pending.size >= 4) {
            val head = ByteArray(minOf(pending.size, 6)) { pending[it] }
            if ((head[0].toInt() and 0xC0) != 0x80) { // not a BMW-FAST header: drop a byte
                pending.removeAt(0)
                continue
            }
            if ((head[0].toInt() and 0x3F) == 0 && head[3].toInt() == 0 && head.size < 6) return
            val telLength = telLength(head)
            if (pending.size < telLength + 1) return
            val request = ByteArray(telLength + 1) { pending[it] }
            repeat(telLength + 1) { pending.removeAt(0) }
            requests.add(request)
            if (EdInterfaceBase.calcChecksumBmwFast(request, telLength) != request[telLength]) {
                checksumErrors++
                continue // the ECU ignores corrupted telegrams
            }
            if (baudRate != ecuBaudRate) continue
            val now = System.nanoTime()
            var t = if (realisticTiming) lineBusyUntil else now
            for (reply in responder(request)) {
                if (realisticTiming) t += reply.delayMs * 1_000_000L
                for (b in reply.telegram) {
                    if (realisticTiming) t += byteNanos()
                    rxQueue.addLast(TimedByte(t, b))
                }
            }
            lineBusyUntil = maxOf(lineBusyUntil, t)
        }
    }

    private fun telLength(head: ByteArray): Int {
        val len = head[0].toInt() and 0x3F
        if (len != 0) return len + 3
        if (head[3].toInt() != 0) return (head[3].toInt() and 0xFF) + 4
        return ((head[4].toInt() and 0xFF) shl 8) + (head[5].toInt() and 0xFF) + 6
    }

    private fun byteNanos(): Long = 10L * 1_000_000_000L / baudRate

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        checkOpen()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        lock.withLock {
            while (true) {
                if (cancelled) {
                    cancelled = false
                    return 0
                }
                val now = System.nanoTime()
                var n = 0
                while (n < length && rxQueue.isNotEmpty() && rxQueue.first().visibleAt <= now) {
                    buffer[offset + n] = rxQueue.removeFirst().value
                    n++
                }
                if (n > 0) return n
                if (now >= deadline || !open) return 0
                val next = rxQueue.firstOrNull()?.visibleAt ?: deadline
                dataAvailable.awaitNanos(minOf(deadline, next) - now)
            }
        }
    }

    /** Drops what has been received so far; bytes still "on the line" arrive later, like on a real bus. */
    override fun purgeInput() = lock.withLock {
        val now = System.nanoTime()
        while (rxQueue.isNotEmpty() && rxQueue.first().visibleAt <= now) rxQueue.removeFirst()
    }

    override fun setDtr(value: Boolean) {
        dtr = value
    }

    override fun setRts(value: Boolean) {
        rts = value
    }

    override fun getDsr(): Boolean {
        checkOpen()
        return dsrState
    }

    override fun cancelRead() = lock.withLock {
        cancelled = true
        dataAvailable.signalAll()
    }

    private fun checkOpen() {
        if (!isOpen) throw IOException("$name not open")
    }

    companion object {
        /** Typical delay between request end and the first answer byte of a K-line ECU (P2). */
        const val DefaultP2Ms = 8

        /** Line with one ECU answering through a single-telegram responder (null = silent), e.g. [EcuSimulator.respond]. */
        fun withEcu(realisticTiming: Boolean = true, p2Ms: Int = DefaultP2Ms, respond: (ByteArray) -> ByteArray?): SimulatedKLine =
            SimulatedKLine({ request -> respond(request)?.let { listOf(Reply(it, p2Ms)) } ?: emptyList() }, realisticTiming)

        /** Line with [simulator] answering after its per-service [EcuSimulator.responseDelayMs] (MSV70-like timing). */
        fun withSimulator(simulator: EcuSimulator, realisticTiming: Boolean = true): SimulatedKLine =
            SimulatedKLine(
                { request -> simulator.respond(request)?.let { listOf(Reply(it, simulator.responseDelayMs(request))) } ?: emptyList() },
                realisticTiming,
            )
    }
}
