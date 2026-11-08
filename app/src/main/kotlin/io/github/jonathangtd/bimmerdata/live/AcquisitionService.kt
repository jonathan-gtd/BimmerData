/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.jonathangtd.bimmerdata.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground service (type connectedDevice) that keeps the USB acquisition alive with the screen
 * off: ongoing notification with the state, cycles/s and recording state, and Stop / Record
 * actions. Started by [LiveRepository.connect] in USB mode, stops itself when the repository
 * disconnects. Holds a partial wake lock while running so the read loop is not suspended.
 */
class AcquisitionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var observing = false

    private val repo: LiveRepository get() = LiveRepository.get(this)

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                Log.i(Tag, "USB device detached, disconnecting")
                repo.disconnect()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        ContextCompat.registerReceiver(this, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Follows the repository once in the foreground (never stops before startForeground). */
    private fun observe() {
        if (observing) return
        observing = true
        scope.launch {
            combine(repo.connection, repo.liveDataSource.status, repo.recorder.state) { c, s, r -> Triple(c, s, r) }
                .collectLatest { (connection, status, recording) ->
                    if (connection is LiveConnection.Disconnected || connection is LiveConnection.Failed) {
                        stopNow()
                        return@collectLatest
                    }
                    if (foreground) {
                        runCatching {
                            getSystemService(NotificationManager::class.java).notify(NotificationId, buildNotification(connection, status, recording))
                        }
                    }
                    delay(1000) // at most one notification update per second
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionStop -> {
                repo.disconnect()
                if (!foreground) stopSelf()
                return START_NOT_STICKY
            }
            ActionRecord -> {
                if (repo.isConnected) repo.toggleRecording()
                if (!foreground) stopSelf()
                return START_NOT_STICKY
            }
        }
        // every startForegroundService() must be followed by startForeground(), even when already running
        run {
            try {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                ServiceCompat.startForeground(
                    this, NotificationId,
                    buildNotification(repo.connection.value, repo.liveDataSource.status.value, repo.recorder.state.value),
                    type,
                )
                foreground = true
                acquireWakeLock()
                observe()
            } catch (ex: Exception) {
                // Android 14+: no USB permission any more, or started from the background
                Log.w(Tag, "startForeground failed, acquisition continues without the service", ex)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopNow() {
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(detachReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BimmerData:acquisition").apply {
            setReferenceCounted(false)
            acquire(TimeUnit.HOURS.toMillis(12))
        }
    }

    private fun buildNotification(connection: LiveConnection, status: AcquisitionStatus, recording: RecordingState): Notification {
        val title = when (connection) {
            is LiveConnection.Connected -> when (status.state) {
                AcquisitionState.Running -> "Live data · " + String.format(Locale.ROOT, "%.1f", status.cyclesPerSecond) + " reads/s"
                AcquisitionState.Connecting -> "Connecting to the car…"
                AcquisitionState.Error -> "No answer from the car"
                AcquisitionState.Stopped -> "Live data stopped"
            }
            is LiveConnection.Connecting -> "Connecting…"
            is LiveConnection.Failed -> "Connection failed"
            LiveConnection.Disconnected -> "Disconnected"
        }
        val text = buildString {
            when (connection) {
                is LiveConnection.Connected -> append(connection.label)
                is LiveConnection.Failed -> append(connection.message)
                else -> Unit
            }
            if (status.state == AcquisitionState.Error && status.message != null) append(" · ").append(status.message)
            if (recording.recording) append(" · REC ").append(recording.rows).append(" rows")
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, if (recording.recording) "Stop recording" else "Record", actionIntent(ActionRecord, 1))
            .addAction(0, "Stop", actionIntent(ActionStop, 2))
            .build()
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, AcquisitionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val Tag = "AcquisitionService"
        const val ChannelId = "live_acquisition"
        const val NotificationId = 42
        const val ActionStop = "io.github.jonathangtd.bimmerdata.live.STOP"
        const val ActionRecord = "io.github.jonathangtd.bimmerdata.live.TOGGLE_RECORD"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(ChannelId, "Live data acquisition", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while BimmerData reads live values from the car"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
