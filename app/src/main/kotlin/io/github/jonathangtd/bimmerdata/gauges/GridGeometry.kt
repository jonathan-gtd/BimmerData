/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.gauges

import android.graphics.RectF

/** Cell math of a columns × rows grid with uniform gaps, shared by the phone and car layouts. */
object GridGeometry {
    fun cellWidth(areaWidth: Float, columns: Int, gap: Float): Float = (areaWidth - gap * (columns - 1)) / columns
    fun cellHeight(areaHeight: Float, rows: Int, gap: Float): Float = (areaHeight - gap * (rows - 1)) / rows

    fun spanRect(
        left: Float, top: Float, width: Float, height: Float,
        columns: Int, rows: Int, gap: Float,
        col: Int, row: Int, colSpan: Int, rowSpan: Int, out: RectF,
    ): RectF {
        val cw = cellWidth(width, columns, gap)
        val ch = cellHeight(height, rows, gap)
        val x = left + col * (cw + gap)
        val y = top + row * (ch + gap)
        out.set(x, y, x + colSpan * cw + (colSpan - 1) * gap, y + rowSpan * ch + (rowSpan - 1) * gap)
        return out
    }
}
