/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of parts of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0):
 * the BMW-FAST path of EdiabasLib/EdCustomAdapterCommon.cs (InterfaceSendData / InterfaceReceiveData
 * with RawMode off and 115200 baud) and the interface functions of EdiabasLib/EdBluetoothInterface*.cs
 * (no DTR/RTS, DSR always set, read timeout offsets).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas.deepobd

import io.github.jonathangtd.bimmerdata.ediabas.SerialPort
import java.io.IOException

/**
 * An adapter with the Deep OBD firmware (ELM327 with the replacement firmware, Deep OBD Bluetooth
 * or Wi-Fi adapters) seen as a K+DCAN cable, on top of the raw byte stream [link] (Bluetooth
 * RFCOMM socket, simulated adapter in tests).
 *
 * For BMW-FAST at 115200 baud the firmware takes the raw telegrams, sends them on the K-line (or
 * D-CAN, depending on its CAN mode), echoes them and returns the ECU answers: exactly what
 * [io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd] does with an FTDI cable (keep its
 * `adapterEcho = true`). What differs:
 * - [open] connects [link] and identifies the adapter ([DeepObdProtocol.detect]); a stock ELM327
 *   firmware or a silent device is refused with a readable [IOException];
 * - the serial parameters are fixed: only 115200 baud (BMW-FAST) can be used. The other K-line
 *   rates need the adapter's own telegram format, not ported yet;
 * - no DTR / RTS, and no ignition line: [getDsr] is always true (EdBluetoothInterface does the
 *   same; on a K-line car the adapter has no clamp status either);
 * - Bluetooth adds latency: [extraReceiveTimeoutMs] follows EdBluetoothInterface's read timeout
 *   offsets (100 ms right after traffic, 1000 ms otherwise). Only a missing answer gets slower;
 * - internal telegrams that would change the adapter (EEPROM settings, reset) are refused when a
 *   job sends them; the app changes the CAN mode only through [writeCanMode].
 */
class DeepObdPort(
    val link: SerialPort,
    private val timeouts: Timeouts = Timeouts(),
) : SerialPort {

    class Timeouts(
        /** Adapter answers (detection, settings). */
        val adapterMs: Int = DeepObdProtocol.DefaultTimeoutMs,
        /** Added to receive timeouts right after traffic (ReadTimeoutOffsetShort). */
        val shortOffsetMs: Int = 100,
        /** Added to receive timeouts otherwise (ReadTimeoutOffsetLong). */
        val longOffsetMs: Int = 1000,
        /** "Right after traffic" window. */
        val recentMs: Int = 100,
    )

    /** What the adapter reported while connecting; null while closed. */
    @Volatile var info: AdapterInfo? = null
        private set

    override val name: String get() = link.name

    override val isOpen: Boolean get() = info != null && link.isOpen

    @Volatile private var baudRate = 0
    @Volatile private var lastIoNanos = 0L

    @Throws(IOException::class)
    override fun open() {
        if (isOpen) return
        link.open()
        try {
            info = DeepObdProtocol.detect(link, timeouts.adapterMs)
            touch()
        } catch (ex: IOException) {
            info = null
            link.close()
            throw ex
        }
    }

    override fun close() {
        info = null
        link.close()
    }

    /** Remembers the rate: [write] refuses anything else than 115200 baud (BMW-FAST). */
    override fun setParameters(baudRate: Int, dataBits: Int, parity: SerialPort.Parity, stopBits: Int) {
        checkOpen()
        this.baudRate = baudRate
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        checkOpen()
        val frame = if (offset == 0) data else data.copyOfRange(offset, offset + length)
        if (DeepObdProtocol.isConfigWrite(frame, length)) {
            throw IOException("Adapter configuration telegram refused (change the adapter settings in BimmerData's settings)")
        }
        if (!DeepObdProtocol.isInternal(frame, length)) {
            if (baudRate != BmwFastBaud) {
                throw IOException("Only BMW-FAST (115200 baud) is supported over a Deep OBD adapter, not $baudRate baud")
            }
            if (DeepObdProtocol.isLongTelegram(frame, length) && (info?.firmwareVersion ?: 0) < DeepObdProtocol.LongTelegramVersion) {
                throw IOException("Long telegrams need adapter firmware 0.12 or newer")
            }
        }
        link.write(data, offset, length)
        touch()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        checkOpen()
        val n = link.read(buffer, offset, length, timeoutMs)
        if (n > 0) touch()
        return n
    }

    override fun purgeInput() {
        checkOpen()
        link.purgeInput()
    }

    override fun setDtr(value: Boolean) = checkOpen()

    override fun setRts(value: Boolean) = checkOpen()

    /** No ignition line over Bluetooth (EdBluetoothInterface.InterfaceGetDsr). */
    override fun getDsr(): Boolean {
        checkOpen()
        return true
    }

    override val extraReceiveTimeoutMs: Int
        get() = if (System.nanoTime() - lastIoNanos < timeouts.recentMs * 1_000_000L) timeouts.shortOffsetMs else timeouts.longOffsetMs

    override fun cancelRead() = link.cancelRead()

    // ---- adapter settings (call between jobs: on the thread that owns the interface) ----

    /** Reads the CAN mode again and updates [info]. */
    @Throws(IOException::class)
    fun readCanMode(): Int? {
        checkOpen()
        val mode = DeepObdProtocol.readConfig(link, DeepObdProtocol.CmdCanMode, timeouts.adapterMs)
        info = info?.copy(canModeCode = mode)
        touch()
        return mode
    }

    /**
     * Stores [mode] in the adapter's EEPROM. Only after an explicit confirmation of the user: it
     * decides whether the adapter talks K-line, D-CAN or probes both.
     */
    @Throws(IOException::class)
    fun writeCanMode(mode: CanMode): CanMode? {
        checkOpen()
        val stored = DeepObdProtocol.writeConfig(link, DeepObdProtocol.CmdCanMode, mode.code, timeouts.adapterMs)
        info = info?.copy(canModeCode = stored)
        touch()
        return CanMode.fromCode(stored)
    }

    /** Reads the supply voltage again and updates [info]. */
    @Throws(IOException::class)
    fun readVoltage(): Double? {
        checkOpen()
        val v = DeepObdProtocol.readVoltage(link, timeouts.adapterMs)
        info = info?.copy(voltage = v)
        touch()
        return v
    }

    private fun touch() {
        lastIoNanos = System.nanoTime()
    }

    private fun checkOpen() {
        if (!isOpen) throw IOException("Adapter not connected")
    }

    companion object {
        const val BmwFastBaud = 115200
    }
}
