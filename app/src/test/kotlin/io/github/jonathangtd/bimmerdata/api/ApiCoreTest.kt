/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.Sample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress

class ApiCoreTest {
    @Test
    fun `tokens are random, long and compared exactly`() {
        val a = ApiTokens.generate()
        val b = ApiTokens.generate()
        assertNotEquals(a, b)
        assertEquals(46, a.length)
        assertTrue(a.startsWith("bd_"))
        assertTrue(ApiTokens.matches(a, a))
        assertFalse(ApiTokens.matches(a, b))
        assertFalse(ApiTokens.matches("", ""))
        assertFalse(ApiTokens.redact(a).contains(a.substring(6, 30)))
    }

    @Test
    fun `only local network peers are allowed`() {
        fun ip(s: String) = InetAddress.getByName(s)
        assertTrue(LanPolicy.isAllowedPeer(ip("127.0.0.1")))
        assertTrue(LanPolicy.isAllowedPeer(ip("192.168.1.20")))
        assertTrue(LanPolicy.isAllowedPeer(ip("10.0.0.3")))
        assertTrue(LanPolicy.isAllowedPeer(ip("172.20.1.1")))
        assertTrue(LanPolicy.isAllowedPeer(ip("fe80::1")))
        assertTrue(LanPolicy.isAllowedPeer(ip("fd12::1")))
        assertFalse(LanPolicy.isAllowedPeer(ip("8.8.8.8")))
        assertFalse(LanPolicy.isAllowedPeer(ip("2001:db8::1")))
        assertTrue(LanPolicy.isBindable(ip("192.168.1.20")))
        assertFalse(LanPolicy.isBindable(ip("8.8.8.8")))
    }

    @Test
    fun `percent decoding`() {
        assertEquals("/api/v1/ecus/msv70/measurements", HttpServer.percentDecode("/api/v1/ecus/msv70/measurements", false))
        assertEquals("Öl temp", HttpServer.percentDecode("%C3%96l+temp", true))
        assertEquals("a+b", HttpServer.percentDecode("a+b", false))
        assertThrows<IllegalArgumentException> { HttpServer.percentDecode("%G1", false) }
        assertThrows<IllegalArgumentException> { HttpServer.percentDecode("%4", false) }
    }

    @Test
    fun `rate limiter refills and locks out bad tokens`() {
        var now = 0L
        val rl = RateLimiter(ratePerSecond = 2.0, burst = 3.0, maxAuthFailures = 3, clock = { now })
        val peer = InetAddress.getByName("192.168.1.30")
        repeat(3) { assertEquals(0, rl.check(peer)) }
        assertTrue(rl.check(peer) > 0)
        now += 1000
        assertEquals(0, rl.check(peer))
        repeat(3) { rl.authFailed(peer) }
        assertTrue(rl.check(peer) > 0)
        now += 6 * 60_000
        assertEquals(0, rl.check(peer))
    }

    @Test
    fun `reads merge leases and expire them`() {
        var now = 0L
        var applied: List<String> = emptyList()
        val reads = ApiReads({ list -> applied = list.map { it.id } }, { now })
        fun m(id: String) = Measurement(id, "msv70", id, "", MeasurementSource.MeasurementTable(1, "X"))
        val a = reads.acquire(listOf(m("a"), m("b")), 1000)
        val b = reads.acquire(listOf(m("b"), m("c")))
        assertEquals(listOf("a", "b", "c"), applied)
        now = 1500
        reads.prune()
        assertEquals(listOf("b", "c"), applied)
        b.close()
        assertEquals(emptyList<String>(), applied)
        a.close()
    }

    @Test
    fun `history keeps new samples only, within retention`() {
        val h = SampleHistory(retentionMs = 1000, maxPointsPerId = 3)
        h.record(mapOf("x" to Sample(1.0, null, 100)), nowMs = 100)
        h.record(mapOf("x" to Sample(1.0, null, 100)), nowMs = 150) // same sample again
        h.record(mapOf("x" to Sample(2.0, null, 200)), nowMs = 200)
        assertEquals(2, h.points("x").size)
        repeat(5) { h.record(mapOf("x" to Sample(it.toDouble(), null, 300L + it)), nowMs = 300L + it) }
        assertEquals(3, h.points("x").size)
        h.record(mapOf("x" to Sample(9.0, null, 2000)), nowMs = 2000)
        assertEquals(listOf(2000L), h.points("x").map { it.timestampMs })
    }

    @Test
    fun `layout validation reports each problem with its path`() {
        val d = Dashboard(
            phonePages = listOf(Page(id = "p", name = "P", columns = 2, rows = 2, cards = listOf(
                Card(id = "a", type = GaugeType.Dial, measurementIds = listOf("msv70/1"), colSpan = 2),
                Card(id = "a", type = GaugeType.BipolarBar, measurementIds = emptyList(), col = 1, row = 1, colSpan = 2),
                Card(id = "c", type = GaugeType.Dial, measurementIds = listOf("nonsense"), row = 1, decimals = 7),
            ))),
            carPages = emptyList(),
        )
        val issues = LayoutOps.validate(d).map { it.path to it.code }
        assertTrue(("carPages" to "no_pages") in issues, issues.toString())
        assertTrue(("phonePages[0].cards[1].id" to "duplicate_id") in issues)
        assertTrue(("phonePages[0].cards[1].measurementIds" to "measurement_count") in issues)
        assertTrue(("phonePages[0].cards[1]" to "out_of_grid") in issues)
        assertTrue(("phonePages[0].cards[2].measurementIds[0]" to "invalid_measurement_id") in issues)
        assertTrue(("phonePages[0].cards[2].decimals" to "invalid_decimals") in issues)
        assertFalse(issues.any { it.second == "card_overlap" && it.first == "phonePages[0].cards[2]" })
    }

    @Test
    fun `grid resize that cannot keep every card is refused`() {
        val cards = (0 until 4).map { Card(id = "c$it", type = GaugeType.Dial, measurementIds = listOf("msv70/$it"), col = it % 2, row = it / 2) }
        val d = Dashboard(phonePages = listOf(Page(id = "p", name = "P", columns = 2, rows = 2, cards = cards)), carPages = listOf(Page(id = "c", name = "C", columns = 4, rows = 1)))
        val e = assertThrows<ApiException> { LayoutOps.updatePage(d, io.github.jonathangtd.bimmerdata.dashboard.Target.Phone, "p", null, 1, 2, null) }
        assertEquals("grid_too_small", e.code)
        val (_, page) = LayoutOps.updatePage(d, io.github.jonathangtd.bimmerdata.dashboard.Target.Phone, "0", "Q", 2, 4, null)
        assertEquals(4, page.rows)
        assertEquals("Q", page.name)
    }
}
