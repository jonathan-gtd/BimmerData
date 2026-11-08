/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

/**
 * Last line of defence against jobs that could brick or re-code an ECU. Checked by the interface
 * right before a telegram goes on the bus, whatever SGBD job produced it.
 *
 * Blocked KWP2000 services (unless [allowDangerous]):
 * - 0x34 requestDownload, 0x35 requestUpload, 0x36 transferData, 0x37 requestTransferExit (flashing)
 * - 0x2E writeDataByCommonIdentifier, 0x3B writeDataByLocalIdentifier (coding / data writes)
 * - 0x3D writeMemoryByAddress (raw memory write)
 * - 0x27 securityAccess (seed/key, only needed before writes and flashing)
 * - 0x10 0x85 startDiagnosticSession(programmingSession)
 *
 * Reading, fault memory clear (0x14), dynamic record definition (0x2C), routines (0x31) and
 * actuator control (0x30) stay allowed.
 */
class SafetyGuard(@Volatile var allowDangerous: Boolean = false) {

    /** Returns null if the KWP2000 service payload ([data] starts with the service id) may be sent, else the reason. */
    fun checkKwpService(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String? {
        if (allowDangerous || length <= 0) return null
        val sid = data[offset].toInt() and 0xFF
        val reason = when (sid) {
            0x34 -> "requestDownload (flash)"
            0x35 -> "requestUpload (flash)"
            0x36 -> "transferData (flash)"
            0x37 -> "requestTransferExit (flash)"
            0x2E -> "writeDataByCommonIdentifier (coding)"
            0x3B -> "writeDataByLocalIdentifier (coding)"
            0x3D -> "writeMemoryByAddress"
            0x27 -> "securityAccess"
            0x10 -> if (length >= 2 && (data[offset + 1].toInt() and 0xFF) == 0x85) "programmingSession" else null
            else -> null
        } ?: return null
        return String.format("service 0x%02X %s blocked by SafetyGuard", sid, reason)
    }

    /**
     * Same check on a BMW-FAST telegram (format, target, source, [length], data, [checksum]).
     * The service id is located from the header alone, so a telegram whose length field does not
     * match its size is still checked on the bytes that would actually be sent.
     */
    fun checkBmwFastTelegram(telegram: ByteArray, length: Int = telegram.size): String? {
        if (allowDangerous || length < 4) return null
        val dataOffset = when {
            (telegram[0].toInt() and 0x3F) != 0 -> 3
            telegram[3].toInt() != 0 -> 4
            else -> 6
        }
        if (dataOffset >= length) return null
        return checkKwpService(telegram, dataOffset, length - dataOffset)
    }
}
