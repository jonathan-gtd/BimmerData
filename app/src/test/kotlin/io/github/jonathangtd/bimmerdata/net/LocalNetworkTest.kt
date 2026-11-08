/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalNetworkTest {

    @Test
    fun `private, link-local and LAN host names are on the local network`() {
        listOf(
            "http://192.168.1.10:8186/api/v2/write",
            "https://10.0.0.5/write",
            "http://172.16.0.1:8186",
            "http://172.31.255.254",
            "http://169.254.10.1",
            "http://[fe80::1]:8186",
            "http://[fd12:3456::1]/",
            "http://nas:8186/write",
            "http://grafana.local:3000",
            "http://server.lan/x",
            "http://box.home.arpa",
        ).forEach { assertTrue(LocalNetwork.isLocalUrl(it), it) }
    }

    @Test
    fun `internet, loopback, CGNAT-VPN and junk are not`() {
        listOf(
            "https://telegraf.example.org/write",
            "https://raw.githubusercontent.com/jonathan-gtd/BimmerData/main/translations",
            "http://8.8.8.8",
            "http://172.32.0.1",
            "http://192.169.1.1",
            "http://127.0.0.1:8765",
            "http://localhost:8186",
            "http://[::1]:8186",
            "http://100.64.0.1", // Tailscale / CGNAT: reached through the VPN interface
            "not a url",
            "",
        ).forEach { assertFalse(LocalNetwork.isLocalUrl(it), it) }
    }
}
