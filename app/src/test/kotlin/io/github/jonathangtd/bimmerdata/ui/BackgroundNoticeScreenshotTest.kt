/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jonathangtd.bimmerdata.live.AcquisitionState
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.BackgroundTask
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The "keeps running in the background" line under the connection pill. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundNoticeScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    private fun capture(name: String) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            BimmerDataTheme {
                Column(
                    Modifier.fillMaxSize().background(Cockpit.Background).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    ConnectionPillContent(
                        LiveConnection.Connected(LiveMode.Bluetooth, "JGOBD"), AcquisitionStatus(AcquisitionState.Running, 14.2),
                        LiveMode.Bluetooth, onConnect = {}, onDisconnect = {}, modifier = Modifier.fillMaxWidth(), recording = true,
                    )
                    ConnectionPillContent(
                        LiveConnection.Connected(LiveMode.Simulation, "Simulation"), AcquisitionStatus(AcquisitionState.Running, 22.4),
                        LiveMode.Simulation, onConnect = {}, onDisconnect = {}, modifier = Modifier.fillMaxWidth(),
                    )
                    BackgroundNoticeContent(
                        listOf(BackgroundTask.Connection, BackgroundTask.Recording, BackgroundTask.TelegrafLive),
                        notificationsOff = true, onAllow = {},
                    )
                    ConnectionPillContent(
                        LiveConnection.Disconnected, AcquisitionStatus(), LiveMode.Usb, onConnect = {}, onDisconnect = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
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
    @Config(qualifiers = "w411dp-h560dp-xhdpi")
    fun notice() = capture("background_notice.png")

    @Test
    @Config(qualifiers = "fr-w411dp-h560dp-xhdpi")
    fun noticeFrench() = capture("background_notice_fr.png")
}
