/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

data class Slot(val col: Int, val row: Int, val colSpan: Int = 1, val rowSpan: Int = 1)

/** A ready-made arrangement of a page; [id] also keys its display name. */
data class LayoutPreset(val id: String, val target: Target, val columns: Int, val rows: Int, val slots: List<Slot>)

object LayoutPresets {
    /** Phone (portrait): the "A" mockup and a few classic arrangements. */
    val phone = listOf(
        LayoutPreset(
            "phone_mockup", Target.Phone, 2, 6,
            listOf(Slot(0, 0, 1, 2), Slot(1, 0, 1, 2), Slot(0, 2, 2, 2), Slot(0, 4), Slot(1, 4), Slot(0, 5), Slot(1, 5)),
        ),
        LayoutPreset("grid_2x2", Target.Phone, 2, 2, grid(2, 2)),
        LayoutPreset("big_left", Target.Phone, 2, 2, listOf(Slot(0, 0, 1, 2), Slot(1, 0), Slot(1, 1))),
        LayoutPreset("big_top", Target.Phone, 2, 3, listOf(Slot(0, 0, 2, 2), Slot(0, 2), Slot(1, 2))),
        LayoutPreset("grid_2x4", Target.Phone, 2, 4, grid(2, 4)),
        LayoutPreset("grid_3x4", Target.Phone, 3, 4, grid(3, 4)),
        LayoutPreset("stack_3", Target.Phone, 1, 3, grid(1, 3)),
        LayoutPreset("hero", Target.Phone, 1, 1, listOf(Slot(0, 0))),
    )

    /** Android Auto (landscape, ~1280×480): at most 4 big cards. */
    val car = listOf(
        LayoutPreset("car_four", Target.Car, 4, 1, grid(4, 1)),
        LayoutPreset("big_left", Target.Car, 3, 2, listOf(Slot(0, 0, 2, 2), Slot(2, 0), Slot(2, 1))),
        LayoutPreset("grid_2x2", Target.Car, 2, 2, grid(2, 2)),
        LayoutPreset("three_columns", Target.Car, 3, 1, grid(3, 1)),
        LayoutPreset("hero", Target.Car, 1, 1, listOf(Slot(0, 0))),
    )

    fun of(target: Target) = if (target == Target.Phone) phone else car

    private fun grid(cols: Int, rows: Int) = (0 until rows).flatMap { r -> (0 until cols).map { c -> Slot(c, r) } }
}
