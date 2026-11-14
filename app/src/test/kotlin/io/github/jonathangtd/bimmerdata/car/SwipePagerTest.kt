/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Android Auto: a horizontal swipe on the dashboard surface changes the page, once per gesture. */
class SwipePagerTest {
    private val width = 1280

    /** A drag of [total] px to the left (negative: to the right) in steps of 16 ms; the page changes summed. */
    private fun drag(p: SwipePager, total: Float, startMs: Long, steps: Int = 20, dy: Float = 0f): Int =
        (0 until steps).sumOf { p.scroll(total / steps, dy / steps, width, startMs + it * 16L) }

    @Test
    fun `a drag to the left past a quarter of the width is the next page, to the right the previous one`() {
        val p = SwipePager()
        assertEquals(1, drag(p, 400f, 0))
        assertEquals(-1, drag(p, -400f, 10_000))
        assertEquals(0, drag(p, 200f, 20_000), "too short")
    }

    @Test
    fun `one gesture changes the page once, even with the fling that ends it`() {
        val p = SwipePager()
        assertEquals(1, drag(p, 1200f, 0))
        assertEquals(0, p.fling(-3000f, 0f, 20 * 16L + 10), "same gesture")
        // a later gesture works again
        assertEquals(1, p.fling(-3000f, 0f, 5_000))
    }

    @Test
    fun `a short fast flick changes the page, a slow or vertical one does not`() {
        val p = SwipePager()
        assertEquals(0, drag(p, 100f, 0, steps = 3))
        assertEquals(1, p.fling(-2000f, 100f, 60), "flick to the left ends the short drag")
        assertEquals(-1, p.fling(2000f, 0f, 10_000))
        assertEquals(0, p.fling(300f, 0f, 20_000), "too slow")
        assertEquals(0, p.fling(1500f, 2000f, 30_000), "mostly vertical")
        assertEquals(0, drag(p, 500f, 40_000, dy = 900f), "vertical drag")
    }
}
