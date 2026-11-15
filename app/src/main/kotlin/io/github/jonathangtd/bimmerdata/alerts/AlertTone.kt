/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The alert tone: two short rising beeps (about 0.4 s), generated (no sound file). Played as
 * navigation guidance with a transient "may duck" audio focus: on the phone it lowers the music
 * for a moment, and with Android Auto the phone's guidance audio goes to the car's speakers.
 */
class AlertTone(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pcm: ShortArray by lazy { beeps() }

    /** Plays the tone once (any thread); a tone already playing is not interrupted. */
    @Synchronized
    fun play() {
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        runCatching { audio?.requestAudioFocus(focus) }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "no alert tone: ${e.message}")
            runCatching { audio?.abandonAudioFocusRequest(focus) }
            return
        }
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "alert tone failed: ${e.message}")
        }
        main.postDelayed({
            runCatching { track.stop() }
            track.release()
            runCatching { audio?.abandonAudioFocusRequest(focus) }
        }, pcm.size * 1000L / RATE + 250)
    }

    private companion object {
        const val TAG = "BimmerData.Alerts"
        const val RATE = 22_050

        /** 880 Hz 130 ms, 70 ms silence, 1320 Hz 170 ms, with 8 ms fades (no click). */
        fun beeps(): ShortArray {
            val parts = listOf(880.0 to 130, 0.0 to 70, 1320.0 to 170)
            val out = ShortArray(parts.sumOf { it.second } * RATE / 1000)
            var at = 0
            for ((hz, ms) in parts) {
                val n = ms * RATE / 1000
                val fade = 8 * RATE / 1000
                for (i in 0 until n) {
                    if (hz > 0) {
                        val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                        out[at + i] = (sin(2 * PI * hz * i / RATE) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                at += n
            }
            return out
        }
    }
}
