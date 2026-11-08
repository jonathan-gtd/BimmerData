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
data class LanAddress(val address: Inet4Address, val interfaceName: String) {
    val host: String get() = address.hostAddress.orEmpty()
}

/**
 * Finds the phone's private IPv4 addresses on Wi-Fi, Ethernet, USB tethering and its own hotspot,
 * never on mobile data (carrier networks often use private addresses too, which would expose the
 * server to other subscribers) nor on VPNs.
 */
object LanAddresses {
    private val cellularNames = listOf("rmnet", "ccmni", "pdp", "v4-rmnet", "clat", "seth_", "rev_rmnet", "dummy")

    fun current(context: Context): List<LanAddress> {
        val excluded = excludedInterfaces(context)
        val result = ArrayList<LanAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (ni in interfaces) {
            val up = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
            if (!up) continue
            val name = ni.name.orEmpty()
            if (name in excluded || cellularNames.any { name.startsWith(it) } || name.startsWith("tun")) continue
            for (addr in ni.inetAddresses.toList()) {
                if (addr is Inet4Address && LanPolicy.isBindable(addr)) result += LanAddress(addr, name)
            }
        }
        // Wi-Fi first, then hotspot / tethering, for a stable order in the UI.
        return result.sortedWith(compareBy({ if (it.interfaceName.startsWith("wlan")) 0 else 1 }, { it.interfaceName }, { it.host }))
    }

    /** Interfaces of mobile-data and VPN networks. */
    private fun excludedInterfaces(context: Context): Set<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        val out = HashSet<String>()
        @Suppress("DEPRECATION") // allNetworks: the only way to see every network, not just the default one
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        for (n in networks) {
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                runCatching { cm.getLinkProperties(n)?.interfaceName }.getOrNull()?.let { out += it }
            }
        }
        return out
    }
}
