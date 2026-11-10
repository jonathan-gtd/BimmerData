/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.backup

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.jonathangtd.bimmerdata.settings.SettingsPage
import io.github.jonathangtd.bimmerdata.settings.SettingsPageScaffold
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

/** Settings › Backup after a restore (what is left to do), EN and FR; the plain page is in SettingsScreenshotTest. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    private fun capture(name: String) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val summary = RestoreSummary(
            cars = 1, carsWithVin = 1, carsScanned = 1, profiles = 13, graphs = 20, activeProfile = "My car · Lambda", selectedCar = "My car",
            translationFiles = 5, apiSettingsRestored = true, todoCodes = listOf(Todo.EcuFiles.code, Todo.TelegrafSecret.code, Todo.RecordingFolder.code),
        )
        activity.setContent {
            BimmerDataTheme {
                Box(Modifier.fillMaxSize().background(Cockpit.Background)) {
                    SettingsPageScaffold(SettingsPage.Backup, onBack = {}) { BackupContentView(BackupStatus.Restored(summary), onExport = {}, onRestore = {}) }
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
    @Config(qualifiers = "w411dp-h1300dp-mdpi")
    fun restored() = capture("backup_restored.png")

    @Test
    @Config(qualifiers = "fr-w411dp-h1300dp-mdpi")
    fun restoredFrench() = capture("backup_restored_fr.png")
}
