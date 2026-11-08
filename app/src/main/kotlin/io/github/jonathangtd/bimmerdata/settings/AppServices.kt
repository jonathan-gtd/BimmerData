/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import io.github.jonathangtd.bimmerdata.api.android.ApiRuntime
import io.github.jonathangtd.bimmerdata.bluetooth.BluetoothAccess
import io.github.jonathangtd.bimmerdata.export.TelegrafRuntime
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.usb.UsbCable

/**
 * Process-wide services driven by the settings: the REST API server, the Telegraf export and the
 * USB auto-connect.
 * Started once when the UI starts ([start] is idempotent).
 */
object AppServices {
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        ApiRuntime.get(app).start()
        TelegrafRuntime.get(app).start()
        AutoConnect.register(app)
    }
}

/**
 * "Auto-connect when the cable is plugged in": connects over USB when an FTDI K+DCAN cable is
 * attached while the app runs, and at app start if the cable is already there. When the cable's
 * attach intent opens the app, Android has already granted USB access; otherwise the usual USB
 * permission dialog appears once.
 */
internal object AutoConnect {
    fun register(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) maybeConnect(app)
            }
        }
        // System broadcast: delivered to a not-exported receiver too.
        ContextCompat.registerReceiver(app, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (!maybeConnect(app)) maybeConnectBluetooth(app)
    }

    /**
     * "Connect to the adapter when the app starts" (Settings › Connection › Bluetooth adapter):
     * at app start only, when nothing else connected and the permission is already granted.
     */
    private fun maybeConnectBluetooth(app: Context) {
        val bt = AppSettings.get(app).state.value.bluetooth
        if (!bt.connectOnStart || bt.address == null) return
        if (!BluetoothAccess.hasConnectPermission(app)) return
        val repo = LiveRepository.get(app)
        if (repo.connection.value != LiveConnection.Disconnected) return
        repo.connect(LiveMode.Bluetooth)
    }

    /** Returns true when it started a USB connection. */
    private fun maybeConnect(app: Context): Boolean {
        if (!AppSettings.get(app).state.value.autoConnectOnCable) return false
        val repo = LiveRepository.get(app)
        val c = repo.connection.value
        val usbBusy = (c is LiveConnection.Connected && c.mode == LiveMode.Usb) || (c is LiveConnection.Connecting && c.mode == LiveMode.Usb)
        if (usbBusy) return false
        if (UsbCable.findCable(UsbCable.usbManager(app)) == null) return false
        repo.connect(LiveMode.Usb)
        return true
    }
}
