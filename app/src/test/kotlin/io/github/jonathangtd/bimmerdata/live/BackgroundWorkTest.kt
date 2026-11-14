/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.NumberFormat
import java.util.Locale

class BackgroundWorkTest {

    private val en = BackgroundTexts(
        title = "BimmerData is running in the background", modeUsb = "USB cable %1\$s", modeBluetooth = "Bluetooth %1\$s",
        modeSimulation = "Simulation", connecting = "connecting…", readsPerSecond = "%1\$s reads/s", noAnswer = "no answer from the car",
        starting = "starting", recording = "Recording %1\$s (%2\$s rows)", telegrafLive = "Telegraf live", profile = "Profile %1\$s",
        locale = Locale.ENGLISH,
    )
    private val fr = en.copy(
        title = "BimmerData tourne en arrière-plan", modeUsb = "Câble USB %1\$s", readsPerSecond = "%1\$s lect./s",
        recording = "Enregistrement %1\$s (%2\$s lignes)", profile = "Profil %1\$s", locale = Locale.FRENCH,
    )
    private val running = AcquisitionStatus(AcquisitionState.Running, cyclesPerSecond = 14.04)
    private val bt = LiveConnection.Connected(LiveMode.Bluetooth, "JGOBD")

    @Test
    fun `notification - reading paused, still connected, the action resumes`() {
        val reading = BackgroundWork.notification(BackgroundSnapshot(bt, running), en)
        assertTrue(reading.canPause)
        assertFalse(reading.paused)
        val n = BackgroundWork.notification(BackgroundSnapshot(bt, AcquisitionStatus(AcquisitionState.Paused), readingPaused = true), en.copy(paused = "reading paused"))
        assertEquals("Bluetooth JGOBD · reading paused", n.text)
        assertTrue(n.canPause)
        assertTrue(n.paused)
        // not connected yet: nothing to pause
        assertFalse(BackgroundWork.notification(BackgroundSnapshot(LiveConnection.Connecting(LiveMode.Usb)), en).canPause)
    }

    @Test
    fun `the service runs while a connection is open or opening, in every mode`() {
        assertFalse(BackgroundWork.needsService(BackgroundSnapshot(LiveConnection.Disconnected)))
        assertFalse(BackgroundWork.needsService(BackgroundSnapshot(LiveConnection.Failed(LiveMode.Usb, "no cable"))))
        for (mode in LiveMode.entries) {
            assertTrue(BackgroundWork.needsService(BackgroundSnapshot(LiveConnection.Connecting(mode))), "$mode connecting")
            assertTrue(BackgroundWork.needsService(BackgroundSnapshot(LiveConnection.Connected(mode, "x"))), "$mode connected")
        }
    }

    @Test
    fun `tasks - connection, then recording and live export only while connected`() {
        val rec = RecordingState(recording = true, startedAtMs = 0, rows = 10)
        assertEquals(
            listOf(BackgroundTask.Connection, BackgroundTask.Recording, BackgroundTask.TelegrafLive),
            BackgroundWork.tasks(BackgroundSnapshot(bt, running, rec, telegrafLive = true)),
        )
        assertEquals(listOf(BackgroundTask.Connection), BackgroundWork.tasks(BackgroundSnapshot(bt, running)))
        assertEquals(emptyList<BackgroundTask>(), BackgroundWork.tasks(BackgroundSnapshot(LiveConnection.Disconnected, recording = rec, telegrafLive = true)))
    }

    @Test
    fun `notification - Bluetooth, rate, recording time and rows, profile`() {
        val s = BackgroundSnapshot(
            bt, running, RecordingState(recording = true, startedAtMs = 1_000, rows = 2345),
            profileName = "E90 330i · Lambda", nowMs = 1_000 + 754_000,
        )
        val n = BackgroundWork.notification(s, en)
        assertEquals("BimmerData is running in the background", n.title)
        assertEquals("Bluetooth JGOBD · 14.0 reads/s · Recording 12:34 (2,345 rows) · Profile E90 330i · Lambda", n.text)
        assertTrue(n.canStopRecording)
        val rowsFr = NumberFormat.getIntegerInstance(Locale.FRENCH).format(2345) // grouping separator of the JDK
        val f = BackgroundWork.notification(s, fr)
        assertEquals("Bluetooth JGOBD · 14,0 lect./s · Enregistrement 12:34 ($rowsFr lignes) · Profil E90 330i · Lambda", f.text)
    }

    @Test
    fun `notification - connecting, no answer, simulation, live export, no stop-recording action`() {
        assertEquals("USB cable · connecting…", BackgroundWork.notification(BackgroundSnapshot(LiveConnection.Connecting(LiveMode.Usb)), en).text)
        val err = BackgroundSnapshot(LiveConnection.Connected(LiveMode.Usb, "FT232R"), AcquisitionStatus(AcquisitionState.Error, message = "x"))
        assertEquals("USB cable FT232R · no answer from the car", BackgroundWork.notification(err, en).text)
        val sim = BackgroundSnapshot(LiveConnection.Connected(LiveMode.Simulation, "Simulation"), running, telegrafLive = true)
        val n = BackgroundWork.notification(sim, en)
        assertEquals("Simulation · 14.0 reads/s · Telegraf live", n.text)
        assertFalse(n.canStopRecording)
    }

    @Test
    fun `elapsed time`() {
        assertEquals("00:00", BackgroundWork.elapsed(-5))
        assertEquals("12:34", BackgroundWork.elapsed(754_000))
        assertEquals("1:02:03", BackgroundWork.elapsed(3_723_000))
    }
}
