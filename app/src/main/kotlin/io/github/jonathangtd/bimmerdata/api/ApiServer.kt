/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * The API server: [ApiService] behind an [HttpServer] that only talks to local-network peers, plus
 * a 100 ms ticker that feeds the sample history. Pure JVM; the Android side decides the addresses.
 */
class ApiServer(
    backend: ApiBackend,
    access: () -> ApiAccess,
    extensions: List<ApiExtension> = emptyList(),
    docsAssets: (String) -> ByteArray? = { null },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    acceptPeer: (InetAddress) -> Boolean = LanPolicy::isAllowedPeer,
    /** Asks the user on the phone before a write to the Bluetooth adapter. */
    guard: CarWriteGuard = CarWriteGuard.DenyAll,
) {
    val service = ApiService(backend, access, extensions, docsAssets = docsAssets, log = log, guard = guard)
    private val http = HttpServer(service::handle, acceptPeer = acceptPeer, log = log, streamedBodyLimit = service::streamedBodyLimit)
    private var ticker: ScheduledExecutorService? = null

    val port: Int get() = http.port
    val boundAddresses: List<InetAddress> get() = http.boundAddresses
    val isRunning: Boolean get() = http.isRunning

    /** True while serving HTTPS. */
    val secure: Boolean get() = http.secure

    /** Starts listening; with [tls] the server speaks HTTPS only. */
    @Synchronized
    fun start(addresses: List<InetAddress>, port: Int, tls: SSLContext? = null) {
        service.stopped = false
        http.start(addresses, port, tls)
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bd-api-tick").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { service.tick() }.onFailure { e -> log("tick failed", e) } }, 0, 100, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        service.stopped = true
        ticker?.shutdownNow()
        ticker = null
        http.stop()
        service.releaseAll()
        service.reads.clear()
    }
}
