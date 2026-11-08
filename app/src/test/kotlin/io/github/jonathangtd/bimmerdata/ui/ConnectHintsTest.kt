/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import io.github.jonathangtd.bimmerdata.live.LiveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Interface instructions follow the mode in use, never one kind of hardware for everyone. */
@RunWith(RobolectricTestRunner::class)
class ConnectHintsTest {
    private val res get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `instructions per mode`() {
        val any = ConnectHints.prepare(res, ConnectHints.Target(null, null))!!
        assertTrue(any, any.contains("diagnostic interface"))
        assertTrue(ConnectHints.prepare(res, ConnectHints.Target(LiveMode.Usb, null))!!.contains("USB cable"))
        assertTrue(ConnectHints.prepare(res, ConnectHints.Target(LiveMode.Bluetooth, "Deep OBD BMW"))!!.contains("Deep OBD BMW"))
        assertNull("nothing to plug in simulation", ConnectHints.prepare(res, ConnectHints.Target(LiveMode.Simulation, null)))
        assertEquals("", ConnectHints.check(res, ConnectHints.Target(LiveMode.Simulation, null)))
        // no instruction assumes one kind of hardware for every mode
        for (mode in listOf(null, LiveMode.Bluetooth, LiveMode.Simulation)) {
            val t = ConnectHints.Target(mode, "OBDII")
            assertFalse((ConnectHints.prepare(res, t) ?: "").contains("K+DCAN"))
            assertFalse(ConnectHints.check(res, t).contains("USB"))
        }
    }

    @Test
    @Config(qualifiers = "fr")
    fun `french instructions`() {
        assertTrue(ConnectHints.prepare(res, ConnectHints.Target(LiveMode.Bluetooth, null))!!.contains("adaptateur Bluetooth"))
        assertTrue(ConnectHints.check(res, ConnectHints.Target(LiveMode.Usb, null)).contains("câble USB"))
    }
}
