/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.jonathangtd.bimmerdata.R

/** "Night cockpit" palette. */
object Cockpit {
    val Background = Color(0xFF0A0C0F)
    val Card = Color(0xFF14171C)
    val Border = Color(0xFF232830)
    val Text = Color(0xFFE8EAED)
    val Muted = Color(0xFF8E97A3)
    val Accent = Color(0xFF5CC8FF)
    val Warning = Color(0xFFFFB547)
    val Danger = Color(0xFFFF5A5F)
    val OnAccent = Color(0xFF04121A)
}

private val CockpitColors = darkColorScheme(
    primary = Cockpit.Accent,
    onPrimary = Cockpit.OnAccent,
    primaryContainer = Cockpit.Border,
    onPrimaryContainer = Cockpit.Text,
    secondary = Cockpit.Accent,
    onSecondary = Cockpit.OnAccent,
    secondaryContainer = Cockpit.Border,
    onSecondaryContainer = Cockpit.Text,
    tertiary = Cockpit.Warning,
    background = Cockpit.Background,
    onBackground = Cockpit.Text,
    surface = Cockpit.Card,
    onSurface = Cockpit.Text,
    surfaceVariant = Cockpit.Border,
    onSurfaceVariant = Cockpit.Muted,
    surfaceContainer = Cockpit.Card,
    surfaceContainerHigh = Cockpit.Card,
    surfaceContainerHighest = Cockpit.Border,
    outline = Cockpit.Border,
    outlineVariant = Cockpit.Border,
    error = Cockpit.Danger,
    onError = Cockpit.Background,
)

/** Barlow for text (SIL OFL 1.1, bundled in res/font). */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

/** Barlow Semi Condensed for numbers and titles. */
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
)

/** Barlow with tabular digits everywhere so values and timings do not jitter. */
private fun TextStyle.tnum() = copy(fontFamily = Barlow, fontFeatureSettings = "tnum")

private val CockpitTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.tnum(), displayMedium = t.displayMedium.tnum(), displaySmall = t.displaySmall.tnum(),
        headlineLarge = t.headlineLarge.tnum(), headlineMedium = t.headlineMedium.tnum(), headlineSmall = t.headlineSmall.tnum(),
        titleLarge = t.titleLarge.tnum(), titleMedium = t.titleMedium.tnum(), titleSmall = t.titleSmall.tnum(),
        bodyLarge = t.bodyLarge.tnum(), bodyMedium = t.bodyMedium.tnum(), bodySmall = t.bodySmall.tnum(),
        labelLarge = t.labelLarge.tnum(), labelMedium = t.labelMedium.tnum(), labelSmall = t.labelSmall.tnum(),
    )
}

@Composable
fun BimmerDataTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CockpitColors, typography = CockpitTypography, content = content)
}
