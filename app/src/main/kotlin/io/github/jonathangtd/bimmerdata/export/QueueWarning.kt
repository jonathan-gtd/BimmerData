/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jonathangtd.bimmerdata.MainActivity
import io.github.jonathangtd.bimmerdata.R
import java.text.NumberFormat

/**
 * Notifications about the Telegraf queue on the phone: it grew past a step (100 MB, 500 MB...),
 * or the phone reached the free-space reserve and new points are no longer queued.
 */
internal object QueueWarning {
    private const val CHANNEL = "telegraf_queue"
    private const val ID_GROWTH = 0x7E10
    private const val ID_BLOCKED = 0x7E11

    fun showGrowth(context: Context, s: ExportState) {
        val size = Formatter.formatShortFileSize(context, s.queuedBytes)
        val points = NumberFormat.getIntegerInstance().format(s.queuedLines)
        val room = Formatter.formatShortFileSize(context, s.roomBytes)
        notify(
            context, ID_GROWTH, NotificationCompat.PRIORITY_DEFAULT,
            context.getString(R.string.s3_notif_growth_title, size),
            context.getString(R.string.s3_notif_growth_text, points, room),
        )
    }

    fun showBlocked(context: Context, s: ExportState) {
        notify(
            context, ID_BLOCKED, NotificationCompat.PRIORITY_HIGH,
            context.getString(R.string.s3_notif_blocked_title),
            context.getString(R.string.s3_notif_blocked_text, Formatter.formatShortFileSize(context, s.reserveBytes)),
        )
    }

    fun cancelGrowth(context: Context) = NotificationManagerCompat.from(context).cancel(ID_GROWTH)

    fun cancelBlocked(context: Context) = NotificationManagerCompat.from(context).cancel(ID_BLOCKED)

    private fun notify(context: Context, id: Int, priority: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.s3_notif_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(priority)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // permission revoked meanwhile
        }
    }
}
