/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationRefreshTest {
    private val day = TranslationRefresh.IntervalMs

    @Test
    fun `a check is due once per day, at first start and when the clock went back`() {
        val now = 1_800_000_000_000L
        assertTrue(TranslationRefresh.isDue(0, now))
        assertFalse(TranslationRefresh.isDue(now - day + 1, now))
        assertTrue(TranslationRefresh.isDue(now - day, now))
        assertTrue(TranslationRefresh.isDue(now + 60_000, now))
    }

    @Test
    fun `validators round trip and give the conditional headers`() {
        val v = TranslationRefresh.Validators("W/\"abc123\"", "Sun, 08 Nov 2026 21:00:00 GMT")
        val back = TranslationRefresh.Validators.decode(v.encode())
        assertEquals(v, back)
        assertEquals(
            mapOf("If-None-Match" to "W/\"abc123\"", "If-Modified-Since" to "Sun, 08 Nov 2026 21:00:00 GMT"),
            back.conditionalHeaders(),
        )
    }

    @Test
    fun `missing or broken validator files give no conditional header`() {
        assertTrue(TranslationRefresh.Validators.decode(null).isEmpty)
        assertTrue(TranslationRefresh.Validators.decode("garbage\n:\n").conditionalHeaders().isEmpty())
        assertEquals(mapOf("If-None-Match" to "\"x\""), TranslationRefresh.Validators.decode("ETag: \"x\"\n").conditionalHeaders())
    }
}
