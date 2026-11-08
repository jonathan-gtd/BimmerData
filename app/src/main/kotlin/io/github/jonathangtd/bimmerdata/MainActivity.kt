/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.jonathangtd.bimmerdata.dashboard.car.CarPreviewExport
import io.github.jonathangtd.bimmerdata.ui.AppRoot
import io.github.jonathangtd.bimmerdata.ui.BimmerDataTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            BimmerDataTheme {
                AppRoot()
                // REST API requests that write to the car wait for the user's answer here
                io.github.jonathangtd.bimmerdata.ui.ApiConfirmHost()
            }
        }
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Debug builds: `adb shell am start -n <pkg>/.MainActivity --ez renderCarPreview true`
     * renders the Android Auto pages to PNG files (files/car_preview/) for a visual check.
     */
    private fun handleDebugIntent(intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        // Screenshots over adb while the test phone is locked (debug builds, explicit extra only).
        if (intent?.getBooleanExtra(EXTRA_SHOW_WHEN_LOCKED, false) == true) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (intent?.getBooleanExtra(EXTRA_RENDER_CAR, false) == true) {
            val seconds = intent.getIntExtra(EXTRA_RENDER_SECONDS, 12)
            intent.removeExtra(EXTRA_RENDER_CAR)
            lifecycleScope.launch { CarPreviewExport.run(this@MainActivity, seconds) }
        }
    }

    private companion object {
        const val EXTRA_RENDER_CAR = "renderCarPreview"
        const val EXTRA_RENDER_SECONDS = "renderSeconds"
        const val EXTRA_SHOW_WHEN_LOCKED = "showWhenLocked"
    }
}
