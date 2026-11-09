/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jonathangtd.bimmerdata.dashboard.Msv70Example
import io.github.jonathangtd.bimmerdata.dashboard.SgbdChoices
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.SgbdInfo
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import io.github.jonathangtd.bimmerdata.profiles.Profiles
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

/** Profiles: list, first launch, editor, header and the card picker on the profile's ECUs (visual check). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilesScreenshotTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    private val set: ProfileSet = run {
        var s = ProfileSet()
        s = Profiles.create(s, "E90 330i · Lambda", ProfileVehicle("E90", "330i"), listOf("msv70"), 1, Msv70Example.create(), carId = "car-e90").first
        s = Profiles.create(s, "E90 330i · Knock", ProfileVehicle("E90", "330i"), listOf("msv70"), 2, carId = "car-e90").first
        s = Profiles.create(s, "E90 330i · Gearbox", ProfileVehicle("E90", "330i"), listOf("msv70", "egs"), 3, carId = "car-e90").first
        s = Profiles.create(s, "120d E87", ProfileVehicle("E87", "120d"), listOf("dde", "egs"), 4, carId = "car-e87").first
        Profiles.activate(s, s.profiles[0].id, 5)
    }

    private val choices = SgbdChoices(
        detected = listOf(SgbdInfo("msv70", "MSV70 für N52", 465), SgbdInfo("egs", "Getriebesteuerung", 80)),
        all = listOf(SgbdInfo("dde", "DDE 6.0", 300), SgbdInfo("egs", "Getriebesteuerung", 80), SgbdInfo("kombi87", "KOMBI E87", 40), SgbdInfo("msv70", "MSV70 für N52", 465)),
    )

    private fun capture(name: String, height: Int = 900, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            BimmerDataTheme {
                Box(Modifier.fillMaxSize().background(Cockpit.Background)) { content() }
            }
        }
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, minOf(view.height, height), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    @Composable
    private fun SheetLike(content: @Composable () -> Unit) {
        Surface(color = Cockpit.Card, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { content() }
    }

    private val noActions = ProfileActions({}, {}, {}, {})

    /** What the profile screens show: the selected car's profiles. */
    private val e90 = set.forCar("car-e90")

    private fun tab(name: String, profiles: ProfileSet = e90) = capture(name) {
        ProfilesTabContent(profiles, onActivate = {}, onNew = {}, onImport = {}, actions = noActions, car = "E90 330i")
    }

    private fun switchSheet(name: String) = capture(name, height = 620) {
        SheetLike { ProfileSwitchContent(e90, onActivate = {}, onNew = {}, onManage = {}, car = "E90 330i") }
    }

    private fun firstLaunch() = capture("profiles_first_launch.png") { NoProfile(onCreate = {}, onImport = {}) }

    private fun editor() = capture("profiles_editor.png") {
        SheetLike {
            Box(Modifier.height(860.dp)) {
                ProfileEditorContent(set.profiles[3], EditorCar("120d", listOf("dde", "egs")), { choices }, { _, _, _, _ -> })
            }
        }
    }

    private fun headerAndPicker() = capture("profiles_header_picker.png", height = 420) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DashboardHeader(
                title = set.activeDashboard().title("BimmerData"), onRename = {}, status = AcquisitionStatus(), rec = RecordingState(),
                onRecord = {}, onEdit = {}, connection = LiveConnection.Disconnected, lastMode = null, onConnect = {}, onDisconnect = {},
            )
            SheetLike {
                Column {
                    SheetHeader("DDE")
                    ProfileEcuBar(listOf("dde", "egs"), current = "dde", onPick = {}, onOther = {})
                }
            }
        }
    }

    @Test @Config(qualifiers = "w411dp-h900dp-mdpi") fun profilesTab() = tab("profiles_tab.png")
    @Test @Config(qualifiers = "fr-w411dp-h900dp-mdpi") fun profilesTabFrench() = tab("profiles_tab_fr.png")
    @Test @Config(qualifiers = "w411dp-h900dp-mdpi") fun profilesTabEmpty() = tab("profiles_tab_empty.png", ProfileSet())
    @Test @Config(qualifiers = "fr-w411dp-h900dp-mdpi") fun profilesTabEmptyFrench() = tab("profiles_tab_empty_fr.png", ProfileSet())
    @Test @Config(qualifiers = "w411dp-h900dp-mdpi") fun profileSwitch() = switchSheet("profiles_switch.png")
    @Test @Config(qualifiers = "fr-w411dp-h900dp-mdpi") fun profileSwitchFrench() = switchSheet("profiles_switch_fr.png")

    @Test @Config(qualifiers = "w411dp-h900dp-mdpi") fun profileFirstLaunch() = firstLaunch()
    @Test @Config(qualifiers = "fr-w411dp-h900dp-mdpi") fun profileFirstLaunchFrench() = capture("profiles_first_launch_fr.png") { NoProfile({}, {}) }

    @Test @Config(qualifiers = "w411dp-h900dp-mdpi") fun profileEditor() = editor()
    @Test @Config(qualifiers = "fr-w411dp-h900dp-mdpi") fun profileEditorFrench() = capture("profiles_editor_fr.png") {
        SheetLike {
            Box(Modifier.height(860.dp)) {
                ProfileEditorContent(set.profiles[3], EditorCar("120d", emptyList()), { choices }, { _, _, _, _ -> })
            }
        }
    }

    @Test @Config(qualifiers = "w411dp-h900dp-mdpi") fun profileHeaderAndPicker() = headerAndPicker()
    @Test @Config(qualifiers = "fr-w411dp-h900dp-mdpi") fun profileHeaderAndPickerFrench() = capture("profiles_header_picker_fr.png", height = 420) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DashboardHeader(
                title = set.activeDashboard().title("BimmerData"), onRename = {}, status = AcquisitionStatus(), rec = RecordingState(),
                onRecord = {}, onEdit = {}, connection = LiveConnection.Disconnected, lastMode = null, onConnect = {}, onDisconnect = {},
            )
            SheetLike {
                Column {
                    SheetHeader("DDE")
                    ProfileEcuBar(listOf("dde", "egs"), current = "dde", onPick = {}, onOther = {})
                }
            }
        }
    }
}
