/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Finding the K+DCAN cable and getting the USB permission. */
object UsbCable {
    private const val ActionUsbPermission = "io.github.jonathangtd.bimmerdata.USB_PERMISSION"

    /** FTDI product ids of K+DCAN cables (FT232R/FT245R, FT-X). */
    private val FtdiProductIds = setOf(0x6001, 0x6015)

    fun usbManager(context: Context): UsbManager = context.getSystemService(UsbManager::class.java)

    /** First attached FTDI device (VID 0x0403), preferring the usual K+DCAN chips. */
    fun findCable(usbManager: UsbManager): UsbDevice? {
        val ftdi = usbManager.deviceList.values.filter { it.vendorId == UsbFtdiSerialPort.FtdiVendorId }
        return ftdi.firstOrNull { it.productId in FtdiProductIds } ?: ftdi.firstOrNull()
    }

    /** Asks the user for access to [device] (no dialog if already granted). */
    suspend fun requestPermission(context: Context, usbManager: UsbManager, device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val appContext = context.applicationContext
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ActionUsbPermission) return
                    runCatching { appContext.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(usbManager.hasPermission(device))
                }
            }
            ContextCompat.registerReceiver(
                appContext, receiver, IntentFilter(ActionUsbPermission), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }
            // Mutable: the system adds the device and the grant result. Explicit through setPackage.
            val intent = Intent(ActionUsbPermission).setPackage(appContext.packageName)
            val pending = PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usbManager.requestPermission(device, pending)
        }
    }
}
