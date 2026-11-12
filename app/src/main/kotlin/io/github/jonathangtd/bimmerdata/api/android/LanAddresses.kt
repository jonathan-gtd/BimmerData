/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.jonathangtd.bimmerdata.api.LanPolicy
import java.net.Inet4Address
import java.net.NetworkInterface

/** A local-network address the API can listen on. */
data class LanAddress(val address: Inet4Address, val interfaceName: String, val vpn: Boolean = false) {
    val host: String get() = address.hostAddress.orEmpty()
}

/**
 * Finds the phone's private IPv4 addresses on Wi-Fi, Ethernet, USB tethering, its own hotspot and
 * VPNs (e.g. a WireGuard tunnel to the home router, so the API answers from the home network while
 * the phone is away), never on mobile data: carrier networks often use private addresses too,
 * which would expose the server to other subscribers.
 */
object LanAddresses {
    private val cellularNames = listOf("rmnet", "ccmni", "pdp", "v4-rmnet", "clat", "seth_", "rev_rmnet", "dummy")

    fun current(context: Context): List<LanAddress> {
        val networks = classify(context)
        val result = ArrayList<LanAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (ni in interfaces) {
            val up = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
            if (!up) continue
            val name = ni.name.orEmpty()
            val vpn = name in networks.vpn
            if (!vpn && (name in networks.cellular || cellularNames.any { name.startsWith(it) })) continue
            for (addr in ni.inetAddresses.toList()) {
                if (addr is Inet4Address && LanPolicy.isBindable(addr)) result += LanAddress(addr, name, vpn)
            }
        }
        // Wi-Fi first, then hotspot / tethering, VPN last, for a stable order in the UI.
        return result.sortedWith(
            compareBy({ if (it.vpn) 2 else if (it.interfaceName.startsWith("wlan")) 0 else 1 }, { it.interfaceName }, { it.host }),
        )
    }

    private class Networks(val cellular: Set<String>, val vpn: Set<String>)

    /**
     * Interfaces of mobile-data networks and of VPNs. A VPN network also reports the transports of
     * the network it runs over (cellular while away), so VPN is checked first.
     */
    private fun classify(context: Context): Networks {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Networks(emptySet(), emptySet())
        val cellular = HashSet<String>()
        val vpn = HashSet<String>()
        @Suppress("DEPRECATION") // allNetworks: the only way to see every network, not just the default one
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        for (n in networks) {
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: continue
            val name = runCatching { cm.getLinkProperties(n)?.interfaceName }.getOrNull() ?: continue
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> vpn += name
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellular += name
            }
        }
        return Networks(cellular, vpn - cellular)
    }
}
