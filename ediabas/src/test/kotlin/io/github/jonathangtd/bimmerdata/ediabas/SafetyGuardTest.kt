/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObdTest.Companion.bytes
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SafetyGuardTest {
    private val guard = SafetyGuard()

    private fun tel(data: String) = BmwFast.buildTelegram(0x12, 0xF1, bytes(data)).let { it.copyOf(it.size - 1) }

    @Test
    fun `flash, write, security access and programming session are blocked`() {
        for (data in listOf(
            "34 00 00 00 00 10", "35 00 00 00", "36 01 02 03", "37",
            "2E 30 00 01", "3B 90 41 42", "3D 00 10 00 01 FF",
            "27 01", "27 02 12 34",
            "10 85",
        )) {
            assertNotNull(guard.checkBmwFastTelegram(tel(data)), data)
        }
    }

    @Test
    fun `diagnostics, fault clear and dynamic records are allowed`() {
        for (data in listOf(
            "1A 80", "18 02 FF FF", "17 29 CD", "14 FF FF", "2C F0 04", "21 F0", "22 44 02",
            "10 86", "10 89", "3E", "31 01", "30 01 07 01", "11 01",
        )) {
            assertNull(guard.checkBmwFastTelegram(tel(data)), data)
        }
    }

    @Test
    fun `long header formats are inspected too`() {
        assertNotNull(guard.checkBmwFastTelegram(tel("36 " + List(0x50) { "AA" }.joinToString(" "))))
        assertNotNull(guard.checkBmwFastTelegram(tel("36 " + List(0x120) { "AA" }.joinToString(" "))))
        assertNull(guard.checkBmwFastTelegram(tel("61 " + List(0x50) { "AA" }.joinToString(" "))))
        // length field larger than the telegram: the bytes that would be sent are still checked
        assertNotNull(guard.checkBmwFastTelegram(bytes("85 12 F1 34 00")))
    }

    @Test
    fun `allowDangerous lifts the block`() {
        val open = SafetyGuard(allowDangerous = true)
        assertNull(open.checkBmwFastTelegram(tel("34 00 00 00 00 10")))
        assertNull(open.checkBmwFastTelegram(tel("10 85")))
    }

    @Test
    fun `plain service payload check`() {
        assertNotNull(guard.checkKwpService(bytes("2E 01")))
        assertNull(guard.checkKwpService(bytes("10")))
        assertNull(guard.checkKwpService(ByteArray(0)))
    }
}
