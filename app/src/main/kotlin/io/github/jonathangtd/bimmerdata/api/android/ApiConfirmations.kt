/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jonathangtd.bimmerdata.MainActivity
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.api.CarWrite
import io.github.jonathangtd.bimmerdata.api.CarWriteDecision
import io.github.jonathangtd.bimmerdata.api.CarWriteGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Confirmations asked on the phone for the API calls that write to the car ([CarWriteGuard]).
 * Every request is shown at once as an in-app dialog ([pending], see ui/ApiConfirmHost) and as a
 * high-priority notification with Allow / Refuse; the API call waits for the first answer.
 * Nobody answering before the deadline means refused. Nothing is ever remembered: each call asks.
 */
object ApiConfirmations {
    data class Pending(val id: Int, val request: CarWrite, val deadlineMs: Long)

    private val ids = AtomicInteger()
    private val waiters = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())

    /** Requests waiting for the user's answer, oldest first. */
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    /** The user's answer (dialog or notification); ignored when the request already ended. */
    fun answer(id: Int, approved: Boolean) {
        waiters[id]?.complete(approved)
    }

    /** Blocks until the user answers or [timeoutMs] runs out. */
    fun ask(context: Context, request: CarWrite, timeoutMs: Long): CarWriteDecision {
        val app = context.applicationContext
        val id = ids.incrementAndGet()
        val future = CompletableFuture<Boolean>()
        waiters[id] = future
        _pending.update { it + Pending(id, request, System.currentTimeMillis() + timeoutMs) }
        notify(app, id, request)
        return try {
            if (future.get(timeoutMs, TimeUnit.MILLISECONDS)) CarWriteDecision.Approved else CarWriteDecision.Rejected
        } catch (_: TimeoutException) {
            CarWriteDecision.Timeout
        } catch (_: Exception) {
            CarWriteDecision.Unavailable
        } finally {
            waiters.remove(id)
            _pending.update { list -> list.filterNot { it.id == id } }
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_BASE + id)
        }
    }

    /** The request in the user's language, for the dialog and the notification. */
    fun text(res: Resources, request: CarWrite): String = when (request) {
        is CarWrite.ClearFaults ->
            if (request.ecu == null) res.getString(R.string.fb3_confirm_clear_all) else res.getString(R.string.fb3_confirm_clear_ecu, request.ecu)
        is CarWrite.RunJob ->
            if (request.args.isEmpty()) res.getString(R.string.fb3_confirm_job, request.job, request.sgbd)
            else res.getString(R.string.fb3_confirm_job_args, request.job, request.sgbd, request.args)
    }

    private fun notify(app: Context, id: Int, request: CarWrite) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.fb3_confirm_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        fun answerIntent(approved: Boolean) = PendingIntent.getBroadcast(
            app, id * 2 + if (approved) 1 else 0,
            Intent(app, ApiConfirmReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_APPROVED, approved),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = text(app.resources, request)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(app.getString(R.string.fb3_confirm_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + app.getString(R.string.fb3_confirm_note)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setOngoing(true) // no swipe-away: the request ends with an answer or its deadline
            .addAction(0, app.getString(R.string.fb3_confirm_refuse), answerIntent(false))
            .addAction(0, app.getString(R.string.fb3_confirm_allow), answerIntent(true))
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_BASE + id, n) }
    }

    private const val CHANNEL = "api_confirm"
    private const val NOTIFICATION_BASE = 7000
    internal const val EXTRA_ID = "id"
    internal const val EXTRA_APPROVED = "approved"
}

/** Allow / Refuse buttons of the confirmation notification. */
class ApiConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ApiConfirmations.EXTRA_ID, -1)
        if (id > 0) ApiConfirmations.answer(id, intent.getBooleanExtra(ApiConfirmations.EXTRA_APPROVED, false))
    }
}

/** [CarWriteGuard] that asks the user on the phone ([ApiConfirmations]). */
class PhoneConfirmGuard(context: Context) : CarWriteGuard {
    private val app = context.applicationContext

    override fun authorize(request: CarWrite, timeoutMs: Long): CarWriteDecision = ApiConfirmations.ask(app, request, timeoutMs)
}
