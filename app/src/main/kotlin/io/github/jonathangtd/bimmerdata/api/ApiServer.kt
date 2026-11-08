/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The API server: [ApiService] behind an [HttpServer] that only talks to local-network peers, plus
 * a 100 ms ticker that feeds the sample history. Pure JVM; the Android side decides the addresses.
 */
class ApiServer(
    backend: ApiBackend,
    access: () -> ApiAccess,
    extensions: List<ApiExtension> = emptyList(),
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    acceptPeer: (InetAddress) -> Boolean = LanPolicy::isAllowedPeer,
) {
    val service = ApiService(backend, access, extensions, log = log)
    private val http = HttpServer(service::handle, acceptPeer = acceptPeer, log = log)
    private var ticker: ScheduledExecutorService? = null

    val port: Int get() = http.port
    val boundAddresses: List<InetAddress> get() = http.boundAddresses
    val isRunning: Boolean get() = http.isRunning

    @Synchronized
    fun start(addresses: List<InetAddress>, port: Int) {
        service.stopped = false
        http.start(addresses, port)
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
