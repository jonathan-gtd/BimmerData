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
import androidx.compose.ui.Modifier
import io.github.jonathangtd.bimmerdata.export.ExportMode
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

/** Settings home and every settings page in a tall window, for a visual check (EN and FR). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    private fun capture(name: String, page: SettingsPage?) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        AppSettings.get(activity).updateTelegraf {
            it.copy(enabled = true, url = "http://192.168.1.10:8186/api/v2/write", car = "My car", mode = ExportMode.AfterTrip)
        }
        AppSettings.get(activity).updateRecording { it.copy(rowIntervalMs = 200, filePrefix = "e90") }
        AppSettings.get(activity).updateGps { it.copy(enabled = page == SettingsPage.Gps) }
        activity.setContent { BimmerDataTheme { Box(Modifier.fillMaxSize().background(Cockpit.Background)) { SettingsScreen(initialPage = page) } } }
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    private fun all(suffix: String) {
        capture("settings_home$suffix.png", null)
        for (p in SettingsPage.entries) capture("settings_${p.name.lowercase()}$suffix.png", p)
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-mdpi")
    fun settings() = all("")

    @Test
    @Config(qualifiers = "fr-w411dp-h1900dp-mdpi")
    fun settingsFrench() = all("_fr")
}
