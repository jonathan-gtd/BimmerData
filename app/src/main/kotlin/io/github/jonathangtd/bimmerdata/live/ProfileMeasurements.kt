/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live

/**
 * Every measurement of the active profile (all its phone and Android Auto pages), kept current by
 * the screens that know the layout and the catalog (phone dashboard, Android Auto). A CSV
 * recording starts with these as columns when they fit ([ReadScope]); between recordings only
 * what is on screen is read, which keeps the read rate high (each value of a dynamic block costs
 * the ECU about 1 ms per cycle).
 */
object ProfileMeasurements {
    @Volatile var current: List<Measurement> = emptyList()
}
