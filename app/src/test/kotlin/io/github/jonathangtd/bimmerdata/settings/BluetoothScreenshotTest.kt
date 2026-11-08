/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jonathangtd.bimmerdata.ediabas.deepobd.AdapterInfo
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

/** Settings › Connection with a chosen adapter, and the adapter state block (visual check). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BluetoothScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    private fun capture(name: String) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        AppSettings.get(activity).updateBluetooth { it.copy(address = "00:1D:A5:68:98:8A", name = "Deep OBD BMW") }
        val ok = AdapterInfo(0x0005, 0x000F, byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08), 12.4, 0xFF)
        val dcan = ok.copy(canModeCode = 0x01, voltage = 12.1)
        activity.setContent {
            BimmerDataTheme {
                Column(
                    Modifier.fillMaxSize().background(Cockpit.Background).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConnectionSettingsSection()
                    SettingsCard { AdapterInfoRows(ok) }
                    SettingsCard { AdapterInfoRows(dcan) }
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
    @Config(qualifiers = "w411dp-h1800dp-mdpi")
    fun connectionSettings() = capture("settings_bluetooth.png")

    @Test
    @Config(qualifiers = "fr-w411dp-h1800dp-mdpi")
    fun connectionSettingsFrench() = capture("settings_bluetooth_fr.png")
}
