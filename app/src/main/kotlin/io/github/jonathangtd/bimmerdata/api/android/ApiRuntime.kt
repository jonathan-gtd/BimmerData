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
import io.github.jonathangtd.bimmerdata.api.DiagApi
import io.github.jonathangtd.bimmerdata.api.ApiAccess
import io.github.jonathangtd.bimmerdata.api.ApiDocs
import io.github.jonathangtd.bimmerdata.api.ApiJson
import io.github.jonathangtd.bimmerdata.api.ApiService
import io.github.jonathangtd.bimmerdata.api.OpenApi
import io.github.jonathangtd.bimmerdata.api.ApiServer
import io.github.jonathangtd.bimmerdata.api.tls.TlsIdentity
import io.github.jonathangtd.bimmerdata.api.tls.TlsIdentityStore
import io.github.jonathangtd.bimmerdata.api.tls.TlsInfo
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
import java.io.File
import java.math.BigInteger
import java.net.InetAddress

/** What the Settings screen shows about the server. */
data class ApiRuntimeState(
    val running: Boolean = false,
    val port: Int = 0,
    /** Local-network addresses the server listens on (loopback not included). */
    val addresses: List<LanAddress> = emptyList(),
    /** Why the server is not running although enabled (e.g. port in use). */
    val error: String? = null,
    /** HTTPS asked in the settings. */
    val https: Boolean = false,
    /** Fingerprints of the TLS identity (HTTPS on and the identity ready). */
    val tls: TlsInfo? = null,
    /** Why the TLS identity could not be created or read (the server then stays off: never plain HTTP instead). */
    val tlsError: String? = null,
)

/**
 * Runs the API server while it is enabled in the settings, on the phone's local-network
 * addresses (plus 127.0.0.1 for `adb forward` and on-phone scripts), and rebinds when Wi-Fi,
 * Ethernet or the hotspot change.
 *
 * HTTPS: with `api.https` the server speaks TLS only, with the identity of [TlsIdentityStore]
 * (`filesDir/api_tls.bin`, encrypted by a Keystore key). If that identity cannot be prepared the
 * server stays off rather than falling back to plain HTTP.
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
    private var bound: Bound? = null

    /** What the running server was started with; any change restarts it. */
    private data class Bound(val port: Int, val addresses: List<InetAddress>, val tlsSerial: BigInteger?)

    private val tlsStore by lazy {
        val sealer = KeystoreSealer("bimmerdata_api_tls")
        TlsIdentityStore(File(app.filesDir, "api_tls.bin"), sealer::seal, sealer::unseal, log = { msg, e -> Log.w(TAG, msg, e) })
    }
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        registerNetworkCallback()
        scope.launch {
            merge(
                settings.state.map { Triple(it.api.enabled, it.api.port, it.api.https) }.distinctUntilChanged().map { },
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
        val lan = LanAddresses.current(app)
        var identity: TlsIdentity? = null
        var tlsError: String? = null
        if (api.https) {
            try {
                identity = tlsStore.ensure(lan.map { it.address })
            } catch (e: Exception) {
                Log.w(TAG, "Cannot prepare the TLS identity", e)
                tlsError = e.message ?: e.javaClass.simpleName
            }
        }
        val tls = identity?.info
        if (!api.enabled) {
            stopServer()
            _state.value = ApiRuntimeState(https = api.https, tls = tls, tlsError = tlsError)
            return@withLock
        }
        if (api.https && identity == null) {
            stopServer()
            _state.value = ApiRuntimeState(port = api.port, addresses = lan, error = "HTTPS: $tlsError", https = true, tlsError = tlsError)
            return@withLock
        }
        val addresses = lan.map { it.address } + InetAddress.getByName("127.0.0.1")
        val wanted = Bound(api.port, addresses, identity?.serverCert?.serialNumber)
        if (server?.isRunning == true && bound == wanted) {
            _state.value = _state.value.copy(addresses = lan, https = api.https, tls = tls)
            return@withLock
        }
        stopServer()
        val diag = DiagApi(AndroidDiagBackend(app), PhoneConfirmGuard(app))
        val s = ApiServer(backend, ::currentAccess, listOf(diag), docsAssets = ::docsAsset, log = { msg, e -> Log.w(TAG, msg, e) }, guard = PhoneConfirmGuard(app))
        try {
            s.start(addresses, api.port, identity?.sslContext())
            server = s
            bound = wanted
            val listening = s.boundAddresses.toSet()
            _state.value = ApiRuntimeState(running = true, port = s.port, addresses = lan.filter { it.address in listening }, https = api.https, tls = tls)
            Log.i(TAG, "API listening on port ${s.port} (${listening.size} address(es), ${if (s.secure) "HTTPS" else "HTTP"})")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start the API on port ${api.port}", e)
            _state.value = ApiRuntimeState(running = false, port = api.port, addresses = lan, error = e.message ?: e.javaClass.simpleName, https = api.https, tls = tls)
        }
    }

    /** New CA and server key: every client must trust or pin again. Restarts the server. */
    fun regenerateCertificate() {
        scope.launch {
            mutex.withLock {
                runCatching { tlsStore.regenerate(LanAddresses.current(app).map { it.address }) }
                    .onFailure { Log.w(TAG, "Cannot regenerate the TLS identity", it) }
                bound = null
            }
            reconcile()
        }
    }

    /** PEM of the CA certificate clients trust, or null before HTTPS was first enabled. Blocking (file read). */
    fun caPem(): String? = runCatching { tlsStore.current()?.caPem() }.getOrNull()

    private fun stopServer() {
        server?.stop()
        server = null
        bound = null
    }

    private val docsCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Embedded Swagger UI files (assets/swagger-ui/), cached after the first read. */
    private fun docsAsset(name: String): ByteArray? {
        if (name !in ApiDocs.ASSETS) return null
        docsCache[name]?.let { return it }
        return runCatching { app.assets.open("swagger-ui/$name").use { it.readBytes() } }.getOrNull()?.also { docsCache[name] = it }
    }

    /** The OpenAPI document, for the in-app documentation (works with the server off). */
    fun openApiJson(): String {
        val service = server?.service ?: ApiService(backend, ::currentAccess)
        val scheme = settings.state.value.api.scheme
        return ApiJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), OpenApi.document(service.routes, null, backend.appVersion, scheme))
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
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN) // e.g. WireGuard to the home router
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
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
