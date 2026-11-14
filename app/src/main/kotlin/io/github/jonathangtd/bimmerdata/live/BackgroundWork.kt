/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import java.text.NumberFormat
import java.util.Locale

/** Something BimmerData keeps doing while it is not on screen. */
enum class BackgroundTask { Connection, Recording, TelegrafLive }

/** Everything the background notice and the ongoing notification are built from. */
data class BackgroundSnapshot(
    val connection: LiveConnection,
    val status: AcquisitionStatus = AcquisitionStatus(),
    val recording: RecordingState = RecordingState(),
    /** Telegraf export on, in "live" mode (points sent while driving). */
    val telegrafLive: Boolean = false,
    /** Active profile name, null when there is none. */
    val profileName: String? = null,
    /** Reading stopped by the user, the interface still connected ([LiveRepository.setReadingPaused]). */
    val readingPaused: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
)

/** Localized templates (string resources in the app, plain strings in the tests). */
data class BackgroundTexts(
    val title: String,
    /** USB mode, `%1$s` = interface name. */
    val modeUsb: String,
    /** Bluetooth mode, `%1$s` = adapter name. */
    val modeBluetooth: String,
    val modeSimulation: String,
    val connecting: String,
    /** `%1$s` = read cycles per second, already formatted. */
    val readsPerSecond: String,
    val noAnswer: String,
    val starting: String,
    /** `%1$s` = elapsed time (12:34), `%2$s` = rows, already formatted. */
    val recording: String,
    val telegrafLive: String,
    /** `%1$s` = profile name. */
    val profile: String,
    val locale: Locale,
    /** Reading stopped by the user, still connected. */
    val paused: String = "Reading paused",
)

/**
 * Content of the ongoing notification. [canPause]: connected, so the reading can be stopped or
 * resumed ([paused] = which of the two the action does: resume when true).
 */
data class BackgroundNotification(
    val title: String,
    val text: String,
    val canStopRecording: Boolean,
    val canPause: Boolean = false,
    val paused: Boolean = false,
)

/**
 * What BimmerData does in the background, and how it is shown (pure logic, no Android):
 * - the foreground service runs while a connection is open or being opened, in any mode
 *   (cable, Bluetooth, simulation). Recording and the live Telegraf export only exist while
 *   connected, so they never need the service on their own;
 * - the notification says what runs: interface, read rate, recording time and rows, live
 *   export, active profile.
 */
object BackgroundWork {

    fun tasks(s: BackgroundSnapshot): List<BackgroundTask> = buildList {
        val open = s.connection is LiveConnection.Connecting || s.connection is LiveConnection.Connected
        if (!open) return@buildList
        add(BackgroundTask.Connection)
        if (s.recording.recording) add(BackgroundTask.Recording)
        if (s.telegrafLive) add(BackgroundTask.TelegrafLive)
    }

    /** True while the foreground service must run. */
    fun needsService(s: BackgroundSnapshot): Boolean = tasks(s).isNotEmpty()

    fun notification(s: BackgroundSnapshot, t: BackgroundTexts): BackgroundNotification {
        val parts = mutableListOf<String>()
        when (val c = s.connection) {
            is LiveConnection.Connecting -> {
                parts += modeLabel(c.mode, null, t)
                parts += t.connecting
            }
            is LiveConnection.Connected -> {
                parts += modeLabel(c.mode, c.label, t)
                parts += if (s.readingPaused) t.paused else when (s.status.state) {
                    AcquisitionState.Running -> t.readsPerSecond.format(String.format(t.locale, "%.1f", s.status.cyclesPerSecond))
                    AcquisitionState.Error -> t.noAnswer
                    AcquisitionState.Paused -> t.paused
                    AcquisitionState.Connecting, AcquisitionState.Stopped -> t.starting
                }
            }
            else -> Unit
        }
        val rec = s.recording.recording && s.connection is LiveConnection.Connected
        if (rec) {
            val elapsed = elapsed(s.nowMs - (s.recording.startedAtMs ?: s.nowMs))
            parts += t.recording.format(elapsed, NumberFormat.getIntegerInstance(t.locale).format(s.recording.rows))
        }
        if (s.telegrafLive && s.connection is LiveConnection.Connected) parts += t.telegrafLive
        s.profileName?.takeIf { it.isNotBlank() }?.let { parts += t.profile.format(it) }
        val connected = s.connection is LiveConnection.Connected
        return BackgroundNotification(
            t.title, parts.joinToString(" · "), canStopRecording = rec,
            canPause = connected, paused = connected && s.readingPaused,
        )
    }

    private fun modeLabel(mode: LiveMode, label: String?, t: BackgroundTexts): String = when (mode) {
        LiveMode.Usb -> t.modeUsb.format(label.orEmpty()).trim()
        LiveMode.Bluetooth -> t.modeBluetooth.format(label.orEmpty()).trim()
        LiveMode.Simulation -> t.modeSimulation
    }

    /** 12:34, or 1:02:03 after an hour. */
    fun elapsed(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total / 60) % 60
        val sec = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }
}
