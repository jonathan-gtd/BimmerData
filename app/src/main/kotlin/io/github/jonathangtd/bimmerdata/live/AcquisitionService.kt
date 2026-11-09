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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.export.ExportMode
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps BimmerData working with the screen off, and says so: it runs while
 * a connection is open or being opened, in every mode (see [BackgroundWork]).
 *
 * Service type (Android 14+ needs one, and each type has its own prerequisites):
 * - cable and Bluetooth: `connectedDevice` (prerequisite: the USB permission of the cable, or
 *   BLUETOOTH_CONNECT);
 * - simulation: `specialUse` on Android 14+ (no device, no network: none of the standard types fits a simulated
 *   car; the app is not distributed on Play, the subtype is declared in the manifest);
 * - plus `location` while the GPS position is recorded ([io.github.jonathangtd.bimmerdata.gps.startAcquisitionForeground]).
 * The Telegraf export needs no type of its own: the live export only runs while connected, and the
 * "after the trip" upload is a JobScheduler job.
 *
 * Ongoing, silent notification on its own channel, refreshed at most once a second: interface,
 * read rate, recording time and rows, live export, active profile; actions Stop recording /
 * Disconnect; a tap opens the app. Holds a partial wake lock so the read loop is not suspended.
 */
class AcquisitionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var observing = false

    private val repo: LiveRepository get() = LiveRepository.get(this)

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && repo.connection.value.mode == LiveMode.Usb) {
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
            repo.connection.first { !BackgroundWork.needsService(BackgroundSnapshot(it)) }
            stopNow()
        }
        scope.launch {
            var shown: Any? = null
            while (isActive) {
                if (foreground) {
                    // re-post only when the text changes (it is checked at most once per second)
                    val content = BackgroundWork.notification(snapshot(this@AcquisitionService), texts(this@AcquisitionService))
                    if (content != shown) {
                        shown = content
                        runCatching { getSystemService(NotificationManager::class.java).notify(NotificationId, buildNotification(content)) }
                    }
                }
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionDisconnect -> {
                repo.disconnect()
                if (!foreground) stopSelf()
                return START_NOT_STICKY
            }
            ActionStopRecording -> {
                if (repo.recorder.state.value.recording) repo.recorder.stop()
                if (!foreground) stopSelf()
                return START_NOT_STICKY
            }
        }
        // every startForegroundService() must be followed by startForeground(), even when already running
        try {
            // specialUse exists from Android 14; before, connectedDevice has no prerequisite
            val base = if (repo.connection.value.mode == LiveMode.Simulation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            io.github.jonathangtd.bimmerdata.gps.startAcquisitionForeground(this, NotificationId, buildNotification(), base)
            foreground = true
            acquireWakeLock()
            observe()
        } catch (ex: Exception) {
            // Android 14+: prerequisite missing (USB permission gone…) or started from the background
            Log.w(Tag, "startForeground failed, the connection continues without the service", ex)
            stopSelf()
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

    private fun buildNotification(
        content: BackgroundNotification = BackgroundWork.notification(snapshot(this), texts(this)),
    ): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .apply {
                if (content.canStopRecording) addAction(0, getString(R.string.bg_action_stop_recording), actionIntent(ActionStopRecording, 1))
            }
            .addAction(0, getString(R.string.bg_action_disconnect), actionIntent(ActionDisconnect, 2))
            .build()
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, AcquisitionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val Tag = "AcquisitionService"
        /** Own channel: "Background activity", low importance (no sound, no heads-up). */
        const val ChannelId = "background_activity"
        private const val OldChannelId = "live_acquisition"
        const val NotificationId = 42
        const val ActionDisconnect = "io.github.jonathangtd.bimmerdata.live.DISCONNECT"
        const val ActionStopRecording = "io.github.jonathangtd.bimmerdata.live.STOP_RECORDING"

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(ChannelId, context.getString(R.string.bg_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.bg_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
            runCatching { nm.deleteNotificationChannel(OldChannelId) } // replaced by the channel above
        }

        /** Current state of what may run in the background. */
        fun snapshot(context: Context): BackgroundSnapshot {
            val repo = LiveRepository.get(context)
            val tg = AppSettings.get(context).state.value.telegraf
            return BackgroundSnapshot(
                connection = repo.connection.value,
                status = repo.liveDataSource.status.value,
                recording = repo.recorder.state.value,
                telegrafLive = tg.enabled && tg.mode == ExportMode.Live && tg.url.isNotBlank(),
                profileName = ProfileStore.state.value?.active?.name,
            )
        }

        fun texts(context: Context): BackgroundTexts = with(context) {
            BackgroundTexts(
                title = getString(R.string.bg_title),
                modeUsb = getString(R.string.bg_mode_usb),
                modeBluetooth = getString(R.string.bg_mode_bluetooth),
                modeSimulation = getString(R.string.bg_mode_simulation),
                connecting = getString(R.string.bg_connecting),
                readsPerSecond = getString(R.string.bg_reads_per_second),
                noAnswer = getString(R.string.bg_no_answer),
                starting = getString(R.string.bg_starting),
                recording = getString(R.string.bg_recording),
                telegrafLive = getString(R.string.bg_telegraf_live),
                profile = getString(R.string.bg_profile),
                locale = resources.configuration.locales[0],
            )
        }
    }
}
