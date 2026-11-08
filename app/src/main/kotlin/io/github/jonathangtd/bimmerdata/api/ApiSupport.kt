/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.Sample
import java.net.InetAddress

/**
 * Recent samples per measurement, recorded by the API while the server runs (for `/history`,
 * statistics of timed reads and the sparklines of rendered pages). Bounded in time, points per
 * measurement and number of measurements. Thread safe.
 */
class SampleHistory(
    private val retentionMs: Long = 10 * 60_000L,
    private val maxPointsPerId: Int = 6000,
    private val maxIds: Int = 256,
) {
    class Point(val timestampMs: Long, val value: Double?, val text: String?)

    private class Series {
        val points = ArrayDeque<Point>()
        var lastTs = Long.MIN_VALUE
        var lastAccessMs = 0L
    }

    private val series = LinkedHashMap<String, Series>()

    /** Adds the samples of [snapshot] that are newer than what is stored. */
    @Synchronized
    fun record(snapshot: Map<String, Sample>, nowMs: Long = System.currentTimeMillis()) {
        for ((id, s) in snapshot) {
            val existing = series[id]
            if (existing != null && s.timestampMs <= existing.lastTs) continue
            if (s.value == null && s.text == null) continue
            val ser = existing ?: run {
                if (series.size >= maxIds) evictOne()
                Series().also { series[id] = it }
            }
            ser.lastTs = s.timestampMs
            ser.lastAccessMs = nowMs
            ser.points.addLast(Point(s.timestampMs, s.value?.takeUnless { it.isNaN() }, s.text))
            while (ser.points.size > maxPointsPerId) ser.points.removeFirst()
            val limit = nowMs - retentionMs
            while (ser.points.isNotEmpty() && ser.points.first().timestampMs < limit) ser.points.removeFirst()
        }
    }

    private fun evictOne() {
        val oldest = series.minByOrNull { it.value.lastAccessMs }?.key ?: return
        series.remove(oldest)
    }

    /** Points of [id] at or after [sinceMs], oldest first. */
    @Synchronized
    fun points(id: String, sinceMs: Long = Long.MIN_VALUE): List<Point> =
        series[id]?.points?.filter { it.timestampMs >= sinceMs }.orEmpty()

    @Synchronized
    fun ids(): List<String> = series.keys.toList()

    val retentionSeconds: Long get() = retentionMs / 1000
}

/**
 * Measurements the API asks the acquisition engine to read: timed reads, watches and event
 * streams each hold a lease; the union of active leases is passed to [apply] whenever it changes.
 */
class ApiReads(private val apply: (List<Measurement>) -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
    inner class Lease internal constructor(val measurements: List<Measurement>, @Volatile var expiresAtMs: Long) : AutoCloseable {
        override fun close() = release(this)
    }

    private val leases = ArrayList<Lease>()
    private var applied: List<String> = emptyList()

    /** Holds [measurements] until [Lease.close] or [durationMs] (Long.MAX_VALUE = until closed). */
    fun acquire(measurements: List<Measurement>, durationMs: Long = Long.MAX_VALUE): Lease {
        val now = clock()
        val lease = Lease(measurements, if (durationMs == Long.MAX_VALUE) Long.MAX_VALUE else now + durationMs)
        synchronized(this) { leases += lease }
        publish()
        return lease
    }

    private fun release(lease: Lease) {
        synchronized(this) { leases.remove(lease) }
        publish()
    }

    /** Drops expired leases (called periodically by the server). */
    fun prune() = publish()

    /** Drops every lease (server stopped). */
    fun clear() {
        synchronized(this) { leases.clear() }
        publish()
    }

    /** Ids currently requested by the API. */
    @Synchronized
    fun active(): List<String> = applied

    /** Latest expiry of the timed leases holding [id] (for `/values/watch` answers). */
    @Synchronized
    fun expiryOf(id: String): Long? = leases.filter { l -> l.measurements.any { it.id == id } }.maxOfOrNull { it.expiresAtMs }

    private fun publish() {
        val merged: List<Measurement>
        synchronized(this) {
            val now = clock()
            leases.removeAll { it.expiresAtMs <= now }
            val seen = HashSet<String>()
            merged = leases.flatMap { it.measurements }.filter { seen.add(it.id) }
            val ids = merged.map { it.id }
            if (ids == applied) return
            applied = ids
        }
        apply(merged)
    }
}

/**
 * Per-peer limits: a token bucket for every request and a lock-out after repeated bad tokens.
 * Thread safe; old entries are forgotten.
 */
class RateLimiter(
    private val ratePerSecond: Double = 20.0,
    private val burst: Double = 60.0,
    private val maxAuthFailures: Int = 10,
    private val failureWindowMs: Long = 5 * 60_000L,
    private val lockoutMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Peer(var tokens: Double, var lastMs: Long) {
        var failures = 0
        var firstFailureMs = 0L
        var lockedUntilMs = 0L
    }

    private val peers = HashMap<InetAddress, Peer>()

    /** Seconds to wait before retrying, or 0 when the request may go on. */
    @Synchronized
    fun check(peer: InetAddress): Long {
        val now = clock()
        if (peers.size > 1024) peers.entries.removeAll { now - it.value.lastMs > 600_000L }
        val p = peers.getOrPut(peer) { Peer(burst, now) }
        if (p.lockedUntilMs > now) return ((p.lockedUntilMs - now) / 1000).coerceAtLeast(1)
        p.tokens = (p.tokens + (now - p.lastMs) / 1000.0 * ratePerSecond).coerceAtMost(burst)
        p.lastMs = now
        if (p.tokens < 1.0) return 1
        p.tokens -= 1.0
        return 0
    }

    @Synchronized
    fun authFailed(peer: InetAddress) {
        val now = clock()
        val p = peers.getOrPut(peer) { Peer(burst, now) }
        if (now - p.firstFailureMs > failureWindowMs) {
            p.firstFailureMs = now
            p.failures = 0
        }
        p.failures++
        if (p.failures >= maxAuthFailures) {
            p.lockedUntilMs = now + lockoutMs
            p.failures = 0
        }
    }
}
