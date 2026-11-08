/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import kotlin.math.abs
import kotlin.math.exp

/**
 * Critically damped spring towards a target, solved in closed form so it is frame rate
 * independent (same motion at 60 or 120 Hz, or with dropped frames) and never overshoots.
 * [omega] (rad/s) sets the speed: ~10 settles in about half a second.
 */
class SmoothValue(private val omega: Double = 10.0) {
    var value = Double.NaN
        private set
    private var velocity = 0.0
    private var lastNanos = 0L

    /** Advances to [frameNanos] and returns the smoothed value; jumps straight to the first target. */
    fun update(target: Double, frameNanos: Long): Double {
        if (target.isNaN()) {
            value = Double.NaN
            velocity = 0.0
            lastNanos = frameNanos
            return value
        }
        if (value.isNaN() || lastNanos == 0L) {
            value = target
            velocity = 0.0
            lastNanos = frameNanos
            return value
        }
        val dt = ((frameNanos - lastNanos) / 1e9).coerceIn(0.0, 0.25)
        lastNanos = frameNanos
        if (dt == 0.0) return value
        val delta = value - target
        val decay = exp(-omega * dt)
        val temp = (velocity + omega * delta) * dt
        velocity = (velocity - omega * temp) * decay
        value = target + (delta + temp) * decay
        return value
    }

    fun isSettled(target: Double, epsilon: Double): Boolean =
        target.isNaN() || (abs(value - target) < epsilon && abs(velocity) < epsilon * omega)
}
