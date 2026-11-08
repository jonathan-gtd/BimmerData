/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.github.jonathangtd.bimmerdata.R

/** Barlow (text) and Barlow Semi Condensed (numbers), bundled under res/font (SIL OFL 1.1). */
class GaugeFonts(
    val regular: Typeface,
    val medium: Typeface,
    val semiBold: Typeface,
    val numbers: Typeface,
) {
    companion object {
        @Volatile private var cached: GaugeFonts? = null

        fun get(context: Context): GaugeFonts = cached ?: synchronized(this) {
            cached ?: load(context.applicationContext).also { cached = it }
        }

        private fun load(context: Context): GaugeFonts {
            fun font(id: Int, fallback: Typeface) = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: fallback
            return GaugeFonts(
                regular = font(R.font.barlow_regular, Typeface.DEFAULT),
                medium = font(R.font.barlow_medium, Typeface.DEFAULT),
                semiBold = font(R.font.barlow_semibold, Typeface.DEFAULT_BOLD),
                numbers = font(R.font.barlow_semi_condensed_semibold, Typeface.DEFAULT_BOLD),
            )
        }
    }
}

/**
 * Colors and metrics shared by every card renderer. [unit] is the number of pixels per design
 * unit: the display density on the phone (1 unit = 1 dp), the surface scale in the car.
 */
class GaugeTheme(
    val car: Boolean,
    val unit: Float,
    val fonts: GaugeFonts,
    val background: Int,
    val card: Int,
    val track: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val warning: Int,
    val centerTick: Int,
    val record: Int,
    val recordText: Int,
    /** Card corner radius, in design units. */
    val cornerRadius: Float,
) {
    fun withUnit(newUnit: Float) = GaugeTheme(
        car, newUnit, fonts, background, card, track, text, muted, accent, warning, centerTick, record, recordText, cornerRadius,
    )

    companion object {
        fun phone(context: Context, density: Float) = GaugeTheme(
            car = false, unit = density, fonts = GaugeFonts.get(context),
            background = 0xFF0A0C0F.toInt(), card = 0xFF14171C.toInt(), track = 0xFF232830.toInt(),
            text = 0xFFE8EAED.toInt(), muted = 0xFF8E97A3.toInt(), accent = 0xFF5CC8FF.toInt(),
            warning = 0xFFFFB547.toInt(), centerTick = 0xFF4A525C.toInt(),
            record = 0xFFFF5A5F.toInt(), recordText = 0xFFFF8A8E.toInt(),
            cornerRadius = 20f,
        )

        /**
         * Android Auto: darker background, bigger corners; [unit] = surface pixels per mockup pixel.
         * [night] (the head unit's dark mode) dims the white text a little to avoid glare.
         */
        fun car(context: Context, unit: Float, night: Boolean = false) = GaugeTheme(
            car = true, unit = unit, fonts = GaugeFonts.get(context),
            background = 0xFF07090B.toInt(), card = 0xFF111418.toInt(), track = 0xFF22272E.toInt(),
            text = if (night) 0xFFCDD1D6.toInt() else 0xFFE8EAED.toInt(),
            muted = if (night) 0xFF7F8894.toInt() else 0xFF8E97A3.toInt(), accent = 0xFF5CC8FF.toInt(),
            warning = 0xFFFFB547.toInt(), centerTick = 0xFF4A525C.toInt(),
            record = 0xFFFF5A5F.toInt(), recordText = 0xFFFF8A8E.toInt(),
            cornerRadius = 26f,
        )
    }
}
