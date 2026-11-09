/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat
import java.net.URI

/**
 * Local network protection (Android 17, apps targeting SDK 37): talking to devices of the local
 * network — in both directions — needs the runtime permission `ACCESS_LOCAL_NETWORK`
 * (protection level "dangerous", shown to the user as access to local network devices).
 * Without it:
 * - the REST API still listens, but no other device of the Wi-Fi network can connect to it
 *   (connections time out; only `127.0.0.1` / `adb forward` work);
 * - the Telegraf export cannot reach a server on the local network (a server on the internet
 *   or behind a VPN address outside the private ranges is not concerned);
 * - same for a translation source on the local network.
 *
 * Bluetooth (RFCOMM) and USB are not IP and are not concerned.
 */
object LocalNetwork {
    /** `android.permission.ACCESS_LOCAL_NETWORK` (API 37). */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** First Android version where the permission is enforced for this app (targetSdk 37). */
    const val SINCE_SDK = 37

    @ChecksSdkIntAtLeast(api = SINCE_SDK)
    fun required(): Boolean = Build.VERSION.SDK_INT >= SINCE_SDK

    fun granted(context: Context): Boolean =
        !required() || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when the UI should ask for the permission (Android 17+, not granted yet). */
    fun shouldRequest(context: Context): Boolean = !granted(context)

    /** "granted", "denied" or "notRequired" (before Android 17), as reported by the REST API. */
    fun state(context: Context): String = when {
        !required() -> "notRequired"
        granted(context) -> "granted"
        else -> "denied"
    }

    /** The app's system settings page (Permissions › Nearby devices / local network). */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Whether [url] points to the local network: private IPv4 (10/8, 172.16/12, 192.168/16),
     * link-local (169.254/16, fe80::/10), IPv6 unique local (fc00::/7), and host names without a
     * dot or ending in `.local`, `.lan`, `.home`, `.home.arpa`, `.internal`. Loopback is not "local
     * network" (always allowed). Unparsable URLs give false.
     */
    fun isLocalUrl(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.trim('[', ']')?.lowercase() ?: return false
        return isLocalHost(host)
    }

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase().trimEnd('.')
        if (host.isEmpty() || host == "localhost") return false
        ipv4(host)?.let { b ->
            val (a, c) = b[0] to b[1]
            return a == 10 || (a == 172 && c in 16..31) || (a == 192 && c == 168) || (a == 169 && c == 254)
        }
        if (':' in host) {
            if (host == "::1") return false
            val first = host.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
            return (first and 0xFE00) == 0xFC00 || (first and 0xFFC0) == 0xFE80
        }
        if ('.' !in host) return true
        return LocalSuffixes.any { host.endsWith(it) }
    }

    private val LocalSuffixes = listOf(".local", ".lan", ".home", ".home.arpa", ".internal")

    private fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p.isNotEmpty() && p.all(Char::isDigit) } ?: return null }
        return bytes.toIntArray()
    }
}
