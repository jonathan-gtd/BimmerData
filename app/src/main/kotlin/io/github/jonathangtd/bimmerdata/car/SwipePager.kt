/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.car

import kotlin.math.abs

/**
 * Turns the host's surface gestures into page changes (pure logic, main thread): a horizontal
 * swipe to the left = next page (+1), to the right = previous page (-1).
 *
 * Hosts send drags as [scroll] steps and may end them with a [fling]; one gesture changes the page
 * at most once: after a change, everything is ignored until no event came for [gestureGapMs].
 * A drag changes the page once it went [dragFraction] of the surface width; a fling (fast flick
 * too short for that) when its horizontal speed is at least [minFlingVelocity] px/s. Mostly
 * vertical moves are ignored.
 */
class SwipePager(
    private val dragFraction: Float = 0.25f,
    private val minFlingVelocity: Float = 800f,
    private val gestureGapMs: Long = 350,
) {
    private var lastEventAt = Long.MIN_VALUE / 2
    private var dragX = 0f
    private var dragY = 0f
    private var changed = false

    private fun begin(nowMs: Long) {
        if (nowMs - lastEventAt > gestureGapMs) {
            dragX = 0f
            dragY = 0f
            changed = false
        }
        lastEventAt = nowMs
    }

    /**
     * One drag step ([distanceX] > 0 when the finger moves to the left, like
     * [androidx.car.app.SurfaceCallback.onScroll]); returns the page change, 0 for none.
     */
    fun scroll(distanceX: Float, distanceY: Float, surfaceWidth: Int, nowMs: Long): Int {
        begin(nowMs)
        if (changed) return 0
        dragX += distanceX
        dragY += distanceY
        val needed = (surfaceWidth.coerceAtLeast(1) * dragFraction)
        if (abs(dragX) < needed || abs(dragX) < 1.5f * abs(dragY)) return 0
        changed = true
        return if (dragX > 0) 1 else -1
    }

    /**
     * End of a gesture ([velocityX] > 0 when the finger moves to the right, like
     * [androidx.car.app.SurfaceCallback.onFling]); returns the page change, 0 for none.
     */
    fun fling(velocityX: Float, velocityY: Float, nowMs: Long): Int {
        begin(nowMs)
        if (changed) return 0
        if (abs(velocityX) < minFlingVelocity || abs(velocityX) < 1.5f * abs(velocityY)) return 0
        changed = true
        return if (velocityX < 0) 1 else -1
    }
}
