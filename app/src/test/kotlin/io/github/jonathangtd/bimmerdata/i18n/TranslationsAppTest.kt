/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.i18n

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.robolectric.RuntimeEnvironment
import io.github.jonathangtd.bimmerdata.live.i18n.EcuTranslations
import io.github.jonathangtd.bimmerdata.live.i18n.TranslationCsv
import io.github.jonathangtd.bimmerdata.live.i18n.Translations
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.Fault
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import io.github.jonathangtd.bimmerdata.faults.EcuDetailScreen
import io.github.jonathangtd.bimmerdata.ui.BimmerDataTheme
import io.github.jonathangtd.bimmerdata.ui.Cockpit
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import io.github.jonathangtd.bimmerdata.vehicle.LinkMode
import io.github.jonathangtd.bimmerdata.vehicle.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr-w411dp-h891dp-xxhdpi")
class TranslationsAppTest {
    private val out = File(System.getenv("BIMMERDATA_SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

    @Test
    fun `file url`() {
        assertEquals(
            "https://raw.githubusercontent.com/jonathan-gtd/BimmerData/main/translations/fr/msv70.csv",
            TranslationRepository.fileUrl(TranslationConfig.DEFAULT_URL + "/", "FR", "MSV70.prg"),
        )
        assertEquals("de", TranslationConfig(language = "de").effectiveLanguage)
        assertEquals(false, TranslationConfig(language = "de").enabled)
    }

    /** French translations of a few MSV70 texts, loaded without any download. */
    private fun loadFrench() {
        val app = RuntimeEnvironment.getApplication()
        TranslationSettings.get(app).update { it.copy(language = "fr", selection = EcuSelection.Chosen, chosen = setOf("msv70")) }
        TranslationRepository.get(app)
        val csv = """
            type;cle;de;traduction;statut
            defaut;0x2F08;Ansauglufttemperatursensor, Signal;Sonde de température d'air d'admission, signal;relu
            symptome;0x21;Signal unplausibel;Signal non plausible;auto
            table;FARTSTATUSTEXTE/0x01;Fehler momentan vorhanden;Défaut actuellement présent;auto
            ecu;;Motorelektronik;Électronique moteur;auto
        """.trimIndent()
        Translations.shared.reset("fr", listOf(EcuTranslations("msv70", "fr", TranslationCsv.parse(csv).entries)))
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BimmerDataTheme { Box(Modifier.fillMaxSize().background(Cockpit.Background)) { content() } } }
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(out, name).absolutePath}")
    }

    @Test
    fun settingsSection() {
        loadFrench()
        capture("translations_settings_fr.png") {
            Box(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { TranslationsSettingsSection() }
        }
    }

    @Test
    fun translatedFault() {
        loadFrench()
        val dme = VehicleEcu(0x12, "DME/DDE", "Motorelektronik", "d_motor", "msv70", sgbdAvailable = false)
        val vehicle = Vehicle("ABC1234", "330i Touring", ecus = listOf(dme))
        val fault = Fault(0x2F08, "Ansauglufttemperatursensor, Signal", symptom = "Signal unplausibel", presence = "Fehler momentan vorhanden")
        val state = VehicleState(garage = Garage(listOf(vehicle), vehicle.id, mapOf(vehicle.id to listOf(EcuFaults(0x12, EcuFaultStatus.Faults, listOf(fault)))), LinkMode.Usb))
        val shown = Translations.shared.fault("msv70", fault)
        assertEquals("Sonde de température d'air d'admission, signal", shown.text)
        assertEquals("Signal non plausible", shown.symptom)
        capture("translations_fault_fr.png") { EcuDetailScreen(vehicle, dme, state, {}, {}, {}, {}, {}, {}) }
    }
}
