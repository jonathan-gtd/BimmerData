/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jonathangtd.bimmerdata.api.android.ApiRuntimeState
import io.github.jonathangtd.bimmerdata.api.android.LanAddress
import io.github.jonathangtd.bimmerdata.api.tls.TlsIdentityStore
import io.github.jonathangtd.bimmerdata.ui.BimmerDataTheme
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.file.Files

/** The HTTPS block of Settings › Local API, with a real (JVM-generated) certificate. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApiTlsScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    private fun capture(name: String, https: Boolean) {
        val wifi = InetAddress.getByName("192.168.2.22") as Inet4Address
        val identity = TlsIdentityStore(File(Files.createTempDirectory("tls").toFile(), "api_tls.bin"), { it }, { it }).ensure(listOf(wifi))
        val config = ApiConfig(enabled = true, token = "x", https = https)
        val state = ApiRuntimeState(running = true, port = 8765, addresses = listOf(LanAddress(wifi, "wlan0")), https = https, tls = identity.info)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            BimmerDataTheme {
                Box(Modifier.fillMaxSize().background(Cockpit.Background).padding(16.dp)) {
                    SettingsCard { ApiTlsContent(config, state, onHttpsChange = {}, onExport = {}, onShare = {}, onRegenerate = {}) }
                }
            }
        }
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-mdpi")
    fun httpsOn() = capture("api_https.png", https = true)

    @Test
    @Config(qualifiers = "fr-w411dp-h1500dp-mdpi")
    fun httpsOnFrench() = capture("api_https_fr.png", https = true)

    @Test
    @Config(qualifiers = "fr-w411dp-h400dp-mdpi")
    fun httpsOffFrench() = capture("api_https_off_fr.png", https = false)
}
