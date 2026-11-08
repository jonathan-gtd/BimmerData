/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.export

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import io.github.jonathangtd.bimmerdata.live.LiveRepository
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.PhoneSensors
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import io.github.jonathangtd.bimmerdata.settings.SecretStore
import io.github.jonathangtd.bimmerdata.settings.TelegrafAuthMode
import io.github.jonathangtd.bimmerdata.settings.TelegrafSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Android side of the Telegraf export (phase 9): builds the [ExportConfig] from the settings and
 * the encrypted secrets, follows the default network (metered or not), queues the live samples in
 * `files/telegraf_queue/` and uploads them with [TelegrafExporter]. When points wait, a
 * [TelegrafUploadJob] is scheduled with the matching network constraint, so an "after the trip"
 * upload also happens once the phone reaches home Wi-Fi with the app closed.
 */
class TelegrafRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val settings = AppSettings.get(app)
    private val secrets = SecretStore.get(app)
    private val repo = LiveRepository.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val network = MutableStateFlow(NetworkKind.None)
    private val known = ConcurrentHashMap<String, Measurement>()

    /** Settings + secrets + active profile (its car and name tag the points). */
    val config: StateFlow<ExportConfig> = combine(settings.state.map { it.telegraf }, secrets.version, ProfileStore.state) { t, _, _ -> toConfig(t) }
        .stateIn(scope, SharingStarted.Eagerly, toConfig(settings.state.value.telegraf))

    private val exporter = TelegrafExporter(
        scope, repo.liveDataSource.samples, ::metadata,
        LineQueue(File(app.filesDir, QUEUE_DIR), reserveBytes = settings.state.value.telegraf.reserveMb * LineQueue.MB), config, network,
    )

    val state: StateFlow<ExportState> get() = exporter.state

    /** What the phone is connected to (for the Settings screen). */
    val networkKind: StateFlow<NetworkKind> get() = network

    private var started = false
    private var watcher: Job? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        registerNetworkCallback()
        exporter.start()
        // schedule a background upload whenever points wait (and when the mode changes)
        watcher = scope.launch {
            combine(exporter.state.map { it.queuedLines > 0 }.distinctUntilChanged(), config) { waiting, cfg -> waiting to cfg }
                .distinctUntilChanged()
                .collect { (waiting, cfg) -> if (waiting && cfg.usable) TelegrafUploadJob.schedule(app, cfg.mode) else TelegrafUploadJob.cancel(app) }
        }
        // a new reserve shows (and applies) at once, even with the export off
        scope.launch { config.map { it.reserveBytes }.distinctUntilChanged().collect { exporter.setReserveBytes(it) } }
        // tell the user when the queue grows past a step, and when the reserve is reached
        scope.launch {
            exporter.state.map { QueueEstimate.growthLevel(it.queuedBytes) }.distinctUntilChanged().collect { level ->
                if (level >= 0) QueueWarning.showGrowth(app, exporter.state.value) else QueueWarning.cancelGrowth(app)
            }
        }
        scope.launch {
            exporter.state.map { it.blocked }.distinctUntilChanged().collect { blocked ->
                if (blocked) QueueWarning.showBlocked(app, exporter.state.value) else QueueWarning.cancelBlocked(app)
            }
        }
    }

    /** Recomputes the queue figures (free space, rate, autonomy) for the Settings screen. */
    fun refresh() = exporter.refresh()

    /** Puts the batches refused by the server back into the queue and tries now. */
    fun retryRejected(): Int = exporter.retryRejected()

    /** Batches refused by the server (gzip line protocol), oldest first. */
    fun rejectedFiles(): List<File> = exporter.rejectedFiles()

    /** Deletes the refused batches (user's explicit request). */
    fun deleteRejected() = exporter.deleteRejected()

    suspend fun test(cfg: ExportConfig = configNow()): WriteResult = exporter.test(cfg)

    /** The configuration from the settings and secrets as they are right now (no flow delay). */
    fun configNow(): ExportConfig = toConfig(settings.state.value.telegraf)

    /** Retry now (also after an authentication error). */
    fun sendNow() = exporter.kick()

    fun clearQueue() = exporter.clearQueue()

    /** Background job: upload everything; true when nothing is left. */
    suspend fun drain(): Boolean = exporter.drain()

    private fun toConfig(t: TelegrafSettings): ExportConfig {
        val profile = (ProfileStore.state.value ?: runCatching { ProfileStore.get(app).load() }.getOrNull())?.active
        return toConfig(t, profile)
    }

    /** `car` tag: the active profile's car ("E90 330i"), else the car name of the settings. */
    private fun toConfig(t: TelegrafSettings, profile: Profile?) = ExportConfig(
        enabled = t.enabled,
        url = t.url.trim(),
        auth = when (t.authMode) {
            TelegrafAuthMode.None -> TelegrafAuth.None
            TelegrafAuthMode.Token -> TelegrafAuth.Token(secrets.get(SecretStore.TELEGRAF_TOKEN))
            TelegrafAuthMode.Basic -> TelegrafAuth.Basic(t.username, secrets.get(SecretStore.TELEGRAF_PASSWORD))
        },
        car = profile?.vehicle?.label?.takeIf { it.isNotBlank() } ?: t.car.trim(),
        profile = profile?.name.orEmpty(),
        pointsPerSecond = t.pointsPerSecond,
        mode = t.mode,
        reserveBytes = t.reserveMb * LineQueue.MB,
    )

    /** Label, unit and SGBD of a measurement id, from what the engine reads. */
    private fun metadata(id: String): Measurement? {
        known[id]?.let { return it }
        for (m in repo.requests.merged()) known[m.id] = m
        known[id]?.let { return it }
        // phone GPS: exported whenever it is on, even without a card showing it
        return PhoneSensors.measurement(id)?.also { known[id] = it }
    }

    private fun registerNetworkCallback() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                network.value = when {
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.None
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkKind.Unmetered
                    else -> NetworkKind.Metered
                }
            }

            override fun onLost(n: Network) {
                network.value = NetworkKind.None
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onFailure { Log.w(TAG, "No network callback", it) }
    }

    companion object {
        private const val TAG = "TelegrafExport"

        /** Queue folder in the app's private storage (`/data/data/<package>/files/telegraf_queue`). */
        const val QUEUE_DIR = "telegraf_queue"

        // holds only the application context
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: TelegrafRuntime? = null

        fun get(context: Context): TelegrafRuntime =
            instance ?: synchronized(this) { instance ?: TelegrafRuntime(context).also { instance = it } }
    }
}

/**
 * Uploads the waiting points when Android sees a suitable network (unmetered for "after the
 * trip", any for "live"), even if the app was closed in the meantime.
 */
class TelegrafUploadJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val runtime = TelegrafRuntime.get(this)
        job = scope.launch {
            val done = runCatching { runtime.drain() }.getOrDefault(false)
            jobFinished(params, !done)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 0x7E1E

        fun schedule(context: Context, mode: ExportMode) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, TelegrafUploadJob::class.java))
                .setRequiredNetworkType(if (mode == ExportMode.AfterTrip) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            runCatching { js.schedule(info) }.onFailure { Log.w("TelegrafExport", "Cannot schedule the upload job", it) }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
