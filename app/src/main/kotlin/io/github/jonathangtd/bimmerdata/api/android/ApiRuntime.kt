/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import io.github.jonathangtd.bimmerdata.api.ApiAccess
import io.github.jonathangtd.bimmerdata.api.ApiServer
import io.github.jonathangtd.bimmerdata.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress

/** What the Settings screen shows about the server. */
data class ApiRuntimeState(
    val running: Boolean = false,
    val port: Int = 0,
    /** Local-network addresses the server listens on (loopback not included). */
    val addresses: List<LanAddress> = emptyList(),
    /** Why the server is not running although enabled (e.g. port in use). */
    val error: String? = null,
)

/**
 * Runs the API server while it is enabled in the settings, on the phone's local-network
 * addresses (plus 127.0.0.1 for `adb forward` and on-phone scripts), and rebinds when Wi-Fi,
 * Ethernet or the hotspot change.
 *
 * Lifetime: the server lives in the app process. It keeps answering while the app is open, and
 * in the background while the acquisition foreground service runs (USB connection). Otherwise
 * Android may freeze or kill the cached process, and the server stops answering until the app
 * is opened again. Token and scope changes apply to the next request without a restart.
 */
@OptIn(FlowPreview::class)
class ApiRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val settings = AppSettings.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val backend by lazy { AndroidApiBackend(app) }
    private val networkChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _state = MutableStateFlow(ApiRuntimeState())
    val state: StateFlow<ApiRuntimeState> = _state.asStateFlow()

    private var server: ApiServer? = null
    private var bound: Pair<Int, List<InetAddress>>? = null
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        registerNetworkCallback()
        scope.launch {
            merge(
                settings.state.map { it.api.enabled to it.api.port }.distinctUntilChanged().map { },
                networkChanged.debounce(1500),
            ).collect { reconcile() }
        }
        // Hotspot and USB tethering do not always raise network callbacks: re-check now and then.
        scope.launch {
            while (true) {
                delay(30_000)
                if (settings.state.value.api.enabled) reconcile()
            }
        }
    }

    /** Re-reads the addresses and rebinds if needed (Settings "refresh"). */
    fun refresh() {
        scope.launch { reconcile() }
    }

    private suspend fun reconcile() = mutex.withLock {
        val api = settings.state.value.api
        if (!api.enabled) {
            stopServer()
            _state.value = ApiRuntimeState()
            return@withLock
        }
        val lan = LanAddresses.current(app)
        val addresses = lan.map { it.address } + InetAddress.getByName("127.0.0.1")
        val wanted = api.port to addresses
        if (server?.isRunning == true && bound == wanted) {
            _state.value = _state.value.copy(addresses = lan)
            return@withLock
        }
        stopServer()
        val s = ApiServer(backend, ::currentAccess, log = { msg, e -> Log.w(TAG, msg, e) })
        try {
            s.start(addresses, api.port)
            server = s
            bound = wanted
            val listening = s.boundAddresses.toSet()
            _state.value = ApiRuntimeState(running = true, port = s.port, addresses = lan.filter { it.address in listening })
            Log.i(TAG, "API listening on port ${s.port} (${listening.size} address(es))")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start the API on port ${api.port}", e)
            _state.value = ApiRuntimeState(running = false, port = api.port, addresses = lan, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        bound = null
    }

    private fun currentAccess(): ApiAccess {
        val api = settings.state.value.api
        return ApiAccess(api.token, api.scopes)
    }

    private fun registerNetworkCallback() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // a car hotspot without internet is fine
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkChanged.tryEmit(Unit) }
            override fun onLost(network: Network) { networkChanged.tryEmit(Unit) }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { networkChanged.tryEmit(Unit) }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }.onFailure { Log.w(TAG, "No network callback", it) }
    }

    companion object {
        private const val TAG = "BimmerDataApi"

        // holds only the application context
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ApiRuntime? = null

        fun get(context: Context): ApiRuntime =
            instance ?: synchronized(this) { instance ?: ApiRuntime(context).also { instance = it } }
    }
}
