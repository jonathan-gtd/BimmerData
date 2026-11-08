/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import java.util.Locale

/**
 * Compose wrapper around [GaugeRenderer]: the card is drawn on the native canvas, reading the live
 * values from [feed] at draw time, so new samples and needle motion only trigger redraws (no
 * recomposition).
 */
@Composable
fun GaugeCard(
    spec: GaugeSpec,
    state: GaugeState,
    renderer: GaugeRenderer,
    feed: GaugeFeed,
    frameClock: State<Long>,
    modifier: Modifier = Modifier,
) {
    val rect = remember { RectF() }
    Canvas(modifier) {
        val frame = frameClock.value
        rect.set(0f, 0f, size.width, size.height)
        drawIntoCanvas { renderer.draw(it.nativeCanvas, rect, spec, state, feed, frame) }
    }
}

/** Frame time in nanoseconds, updated every display frame while in composition. */
@Composable
fun rememberFrameClock(): State<Long> {
    val time = remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { time.longValue = it }
    }
    return time
}

@Composable
fun currentLocale(): Locale = LocalLocale.current.platformLocale

/** Phone renderer (1 design unit = 1 dp), recreated when density or locale change. */
@Composable
fun rememberPhoneRenderer(): GaugeRenderer {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val locale = currentLocale()
    return remember(density, locale) { GaugeRenderer(GaugeTheme.phone(context, density), GaugeFormat(locale)) }
}
