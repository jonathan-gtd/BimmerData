/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of parts of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0):
 * EdiabasLib/EdCustomAdapterCommon.cs (UpdateAdapterInfo: adapter detection over the internal
 * "F1 F1" telegrams) and the adapter configuration commands of BmwDeepObd/CanAdapterActivity.cs
 * (AdapterCommand / AdapterCommandCustom), checked against the replacement firmware for ELM327
 * (EdiabasLib/CanAdapterElm/CanAdapterElm.X/main.c, internal_telegram).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas.deepobd

import io.github.jonathangtd.bimmerdata.ediabas.SerialPort
import java.io.IOException

/** CAN setting stored in the adapter (firmware `can_mode`, EEPROM). */
enum class CanMode(val code: Int) {
    /** CAN off: K-line only (E-series before 03/2007, DS2 cars). */
    KLineOnly(0x00),

    /** D-CAN 500 kbit/s only (E-series from 03/2007). */
    DCan500(0x01),

    /** CAN 100 kbit/s (VAG). */
    Can100(0x09),

    /** Automatic: the adapter probes D-CAN and falls back to the K-line (factory setting). */
    Auto(0xFF),
    ;

    /** True when BMW-FAST telegrams can reach a K-line car in this mode. */
    val reachesKLine: Boolean get() = this == KLineOnly || this == Auto

    companion object {
        fun fromCode(code: Int): CanMode? = entries.firstOrNull { it.code == code }
    }
}

/** What the adapter told about itself while connecting. */
data class AdapterInfo(
    /** Firmware family (see [DeepObdProtocol.typeName]). */
    val adapterType: Int,
    /** Firmware version, high byte = major (0x000F = 0.15). */
    val firmwareVersion: Int,
    /** 8 byte id of the processor, null on old firmware. */
    val serial: ByteArray?,
    /** Supply voltage at the OBD socket in volts, null when not supported. */
    val voltage: Double?,
    /** CAN mode stored in the adapter, null when it could not be read. */
    val canModeCode: Int?,
) {
    val canMode: CanMode? get() = canModeCode?.let { CanMode.fromCode(it) }
    val firmwareText: String get() = "%d.%d".format(firmwareVersion shr 8, firmwareVersion and 0xFF)
    val typeName: String get() = DeepObdProtocol.typeName(adapterType)
    val serialText: String? get() = serial?.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    override fun equals(other: Any?): Boolean =
        other is AdapterInfo && adapterType == other.adapterType && firmwareVersion == other.firmwareVersion &&
            serial.contentEqualsNullable(other.serial) && voltage == other.voltage && canModeCode == other.canModeCode

    override fun hashCode(): Int = ((adapterType * 31 + firmwareVersion) * 31 + (serial?.contentHashCode() ?: 0)) * 31 + (canModeCode ?: -1)

    private fun ByteArray?.contentEqualsNullable(o: ByteArray?) = if (this == null) o == null else o != null && contentEquals(o)
}

/** The device answered, but it is not an adapter with the Deep OBD firmware. */
class NotDeepObdException(message: String) : IOException(message)

/**
 * Internal telegrams of the Deep OBD adapters: BMW-FAST frames addressed from F1 to F1, which the
 * firmware answers itself instead of sending them on the bus. The adapter first echoes the request,
 * then sends its answer (`0x80 + data length`, `F1 F1`, command, data, checksum).
 *
 * | Request            | Answer data                  |
 * |--------------------|------------------------------|
 * | `82 F1 F1 FE FE`   | ignition (bit 0, 0x80 = invalid on ELM boards) |
 * | `84 F1 F1 06 ...`  | escape mode (XOR 0x55)       |
 * | `82 F1 F1 FD FD`   | type (2 bytes), version (2 bytes) |
 * | `82 F1 F1 FB FB`   | processor id (8 bytes)       |
 * | `82 F1 F1 FC FC`   | voltage × 10                 |
 * | `82 F1 F1 FA FA`   | clamp status (CAN only)      |
 * | `82 F1 F1 80/81/82 00` | read block size / separation time / CAN mode |
 * | `82 F1 F1 00/01/02 v`  | **write** them (EEPROM)  |
 * | `82 F1 F1 FF FF`   | reset                        |
 */
object DeepObdProtocol {

    /** Firmware families (`ADAPTER_TYPE` of the firmware). */
    fun typeName(type: Int): String = when (type) {
        0x00 -> "Echo only (no Deep OBD firmware)"
        0x01 -> "Deep OBD D-CAN/K-line cable (AVR)"
        0x02 -> "ELM327 Deep OBD, 38400 baud"
        0x03 -> "ELM327 Deep OBD, BC04 Bluetooth"
        0x04 -> "ELM327 Deep OBD, HC04/HC05 Bluetooth"
        0x05 -> "ELM327 Deep OBD, SPP_UART Bluetooth"
        0x06 -> "ELM327 Deep OBD, YC1021 Bluetooth"
        0x07 -> "ELM327 Deep OBD, SPP_UART2 Bluetooth"
        0x08 -> "ELM327 Deep OBD, 115200 baud"
        0x09 -> "ELM327 Deep OBD, HC06 Bluetooth LE"
        0x10 -> "ELM327 Deep OBD, ESP8266 Wi-Fi"
        else -> "Deep OBD adapter type 0x%04X".format(type)
    }

    const val CmdIgnition = 0xFE
    const val CmdVersion = 0xFD
    const val CmdVoltage = 0xFC
    const val CmdSerial = 0xFB
    const val CmdClamp = 0xFA
    const val CmdEscape = 0x06
    const val CmdCanMode = 0x02
    const val CmdBtPin = 0x04
    const val CmdBtName = 0x05
    private const val ReadFlag = 0x80

    private const val EscapeXor = 0x55
    private const val EscapeCodeDefault = 0xFF
    private const val EscapeMaskDefault = 0x80

    /** Firmware version from which long telegrams (2 byte length) are accepted. */
    const val LongTelegramVersion = 0x000C

    fun checksum(data: ByteArray, length: Int = data.size): Byte {
        var sum = 0
        for (i in 0 until length) sum += data[i].toInt()
        return sum.toByte()
    }

    /** `(0x81 + n) F1 F1 command data[0..n) checksum` */
    fun telegram(command: Int, vararg data: Int): ByteArray {
        val t = ByteArray(4 + data.size + 1)
        t[0] = (0x81 + data.size).toByte()
        t[1] = 0xF1.toByte()
        t[2] = 0xF1.toByte()
        t[3] = command.toByte()
        data.forEachIndexed { i, v -> t[4 + i] = v.toByte() }
        t[t.size - 1] = checksum(t, t.size - 1)
        return t
    }

    /** Frame handled by the adapter itself (never sent on the bus). */
    fun isInternal(frame: ByteArray, length: Int = frame.size): Boolean =
        (length >= 4 && (frame[1].toInt() and 0xFF) == 0xF1 && (frame[2].toInt() and 0xFF) == 0xF1) ||
            (length == 5 && (frame[0].toInt() and 0xFF) == 0x81 && frame[1].toInt() == 0 && frame[2].toInt() == 0)

    /**
     * Internal frame that changes the adapter (EEPROM write, Bluetooth pin / name, reset, legacy
     * `81 00 00 mode`). Jobs must never send these: only [DeepObdPort.writeCanMode] after the
     * user's confirmation.
     */
    fun isConfigWrite(frame: ByteArray, length: Int = frame.size): Boolean {
        if (!isInternal(frame, length)) return false
        if ((frame[0].toInt() and 0xFF) == 0x81 && frame[1].toInt() == 0) return true // legacy CAN mode write
        val cmd = frame[3].toInt() and 0xFF
        if (cmd == 0xFF) return true // reset
        val base = cmd and 0x7F
        val write = (cmd and ReadFlag) == 0
        return write && base in 0x00..0x05
    }

    /** Long BMW-FAST format: length 0 in the format byte and in byte 3, 2 byte length follows. */
    fun isLongTelegram(frame: ByteArray, length: Int = frame.size): Boolean =
        length >= 6 && (frame[0].toInt() and 0x3F) == 0 && frame[3].toInt() == 0

    /**
     * Sends an internal telegram on [link] and returns the answer (without the echo), or null when
     * the adapter only echoed. Throws on silence, a wrong echo or a wrong checksum, with a message
     * that tells a stock ELM327 firmware apart ([NotDeepObdException]).
     */
    @Throws(IOException::class)
    fun exchange(link: SerialPort, request: ByteArray, timeoutMs: Int): ByteArray? {
        link.purgeInput()
        link.write(request)
        val buf = ByteArray(256)
        var n = 0
        val echoLen = request.size
        var deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var expected = -1
        while (true) {
            val wanted = if (expected > 0) expected - n else (echoLen + 1 - n).coerceAtLeast(1)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            val got = link.read(buf, n, minOf(wanted, buf.size - n), ((remaining + 999_999) / 1_000_000).toInt().coerceAtLeast(1))
            if (got > 0) {
                n += got
                deadline = System.nanoTime() + timeoutMs * 1_000_000L // like UpdateAdapterInfo: timeout since the last byte
                if (n >= echoLen && !buf.startsWith(request, echoLen)) throw unexpected(buf, n)
                if (expected < 0 && n > echoLen) expected = echoLen + (buf[echoLen].toInt() and 0x3F) + 4
                if (expected in 1..n) break
            }
        }
        if (n == 0) throw IOException("The adapter does not answer")
        if (n < echoLen || !buf.startsWith(request, echoLen)) throw unexpected(buf, n)
        if (n == echoLen) return null // echo only
        if (expected < 0 || n < expected) throw IOException("Incomplete adapter answer: ${hex(buf, n)}")
        val answer = buf.copyOfRange(echoLen, expected)
        if (checksum(answer, answer.size - 1) != answer.last()) throw IOException("Adapter answer with a wrong checksum: ${hex(answer, answer.size)}")
        if ((answer[3].toInt() and 0xFF) != (request[3].toInt() and 0xFF)) throw IOException("Adapter answered another command: ${hex(answer, answer.size)}")
        return answer
    }

    /**
     * UpdateAdapterInfo(forceUpdate = true): ignition, escape mode off, type + version, id and
     * voltage, plus the CAN mode. Throws when the device is not a Deep OBD adapter.
     */
    @Throws(IOException::class)
    fun detect(link: SerialPort, timeoutMs: Int = DefaultTimeoutMs): AdapterInfo {
        val ignition = try {
            exchange(link, telegram(CmdIgnition, CmdIgnition), timeoutMs)
        } catch (ex: NotDeepObdException) {
            throw ex
        } catch (ex: IOException) {
            // silent: maybe the stock ELM327 firmware waiting for an AT command
            probeStockElm(link, timeoutMs)?.let { throw it }
            throw ex
        } ?: throw probeStockElm(link, timeoutMs) // a stock ELM327 echoes the bytes too (ATE1)
            ?: NotDeepObdException(
                "The adapter only echoes the telegrams: it is a plain K-line interface, not an adapter with the Deep OBD firmware",
            )
        check(ignition.size >= 6)
        // escape mode off (only used by some car radios): an echo without answer is fine
        exchange(link, telegram(CmdEscape, 0x00 xor EscapeXor, EscapeCodeDefault xor EscapeXor, EscapeMaskDefault xor EscapeXor), timeoutMs)
        val version = exchange(link, telegram(CmdVersion, CmdVersion), timeoutMs)
            ?: throw IOException("The adapter does not report its firmware version")
        if (version.size < 9) throw IOException("Short version answer: ${hex(version, version.size)}")
        val type = ((version[4].toInt() and 0xFF) shl 8) or (version[5].toInt() and 0xFF)
        val fw = ((version[6].toInt() and 0xFF) shl 8) or (version[7].toInt() and 0xFF)
        var serial: ByteArray? = null
        var voltage: Double? = null
        var canMode: Int? = null
        if (type >= 0x0002) {
            exchange(link, telegram(CmdSerial, CmdSerial), timeoutMs)?.takeIf { it.size >= 13 }?.let { serial = it.copyOfRange(4, 12) }
            exchange(link, telegram(CmdVoltage, CmdVoltage), timeoutMs)?.let { voltage = (it[4].toInt() and 0xFF) * 0.1 }
            canMode = readConfig(link, CmdCanMode, timeoutMs)
        }
        return AdapterInfo(type, fw, serial, voltage, canMode)
    }

    /** Reads a one-byte setting (block size 0x00, separation time 0x01, CAN mode 0x02); null if not answered. */
    @Throws(IOException::class)
    fun readConfig(link: SerialPort, command: Int, timeoutMs: Int = DefaultTimeoutMs): Int? =
        exchange(link, telegram(command or ReadFlag, 0x00), timeoutMs)?.takeIf { it.size >= 6 }?.let { it[4].toInt() and 0xFF }

    /** Writes a one-byte setting to the adapter's EEPROM and returns the value read back. */
    @Throws(IOException::class)
    fun writeConfig(link: SerialPort, command: Int, value: Int, timeoutMs: Int = DefaultTimeoutMs): Int {
        require(command in 0x00..0x02) { "only block size, separation time and CAN mode" }
        val answer = exchange(link, telegram(command, value), timeoutMs) ?: throw IOException("The adapter did not confirm the setting")
        return answer[4].toInt() and 0xFF
    }

    /** Supply voltage in volts. */
    @Throws(IOException::class)
    fun readVoltage(link: SerialPort, timeoutMs: Int = DefaultTimeoutMs): Double? =
        exchange(link, telegram(CmdVoltage, CmdVoltage), timeoutMs)?.let { (it[4].toInt() and 0xFF) * 0.1 }

    /** Sends `ATI` like an ELM327 app would; returns an explanation if a stock firmware answers. */
    private fun probeStockElm(link: SerialPort, timeoutMs: Int): NotDeepObdException? {
        return try {
            link.purgeInput()
            link.write("\rATI\r".toByteArray(Charsets.US_ASCII))
            val text = readText(link, timeoutMs)
            if (looksLikeElm(text)) stockElmMessage(text) else null
        } catch (_: IOException) {
            null
        }
    }

    private fun readText(link: SerialPort, timeoutMs: Int): String {
        val buf = ByteArray(128)
        var n = 0
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (n < buf.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            val got = link.read(buf, n, buf.size - n, ((remaining + 999_999) / 1_000_000).toInt().coerceAtLeast(1))
            if (got > 0) {
                n += got
                if (buf[n - 1] == '>'.code.toByte()) break // ELM prompt
            }
        }
        return String(buf, 0, n, Charsets.US_ASCII)
    }

    private fun stockElmMessage(text: String): NotDeepObdException {
        val version = Regex("""ELM327\s*v?[\d.]+""", RegexOption.IGNORE_CASE).find(text)?.value
        return NotDeepObdException(
            (version?.let { "Standard ELM327 firmware ($it)" } ?: "Standard ELM327 firmware") +
                ": BimmerData needs the Deep OBD replacement firmware on this adapter",
        )
    }

    /** ELM327 text answer: its name, the `>` prompt or `?` (unknown command). Our own echo has none. */
    private fun looksLikeElm(text: String): Boolean =
        text.contains("ELM", ignoreCase = true) || text.contains('>') || text.contains('?')

    private fun unexpected(buf: ByteArray, n: Int): IOException {
        val text = String(buf, 0, n, Charsets.US_ASCII)
        val printable = text.count { it == '\r' || it == '\n' || it in ' '..'~' }
        if (printable * 10 >= n * 8 && looksLikeElm(text)) return stockElmMessage(text)
        return IOException("Unexpected answer from the adapter: ${hex(buf, n)}")
    }

    private fun ByteArray.startsWith(prefix: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (this[i] != prefix[i]) return false
        return true
    }

    fun hex(data: ByteArray, length: Int): String = (0 until minOf(length, 32)).joinToString(" ") { "%02X".format(data[it].toInt() and 0xFF) } +
        if (length > 32) " …" else ""

    /** EdBluetoothInterface ReadTimeoutOffsetLong: the adapter's answers wait at most this long. */
    const val DefaultTimeoutMs = 1000
}
