package com.wetinknext.engine.core

import com.wetinknext.engine.canvas.TileGrid
import com.wetinknext.engine.undo.TileCoord
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** CPU-only bounds and tile selection for canvas-space stroke geometry. */
object StrokeDirtyBounds {
    const val ANTIALIAS_PADDING_PX = 2f

    fun segmentBounds(
        startX: Float, startY: Float, endX: Float, endY: Float,
        startRadius: Float, endRadius: Float = startRadius, featherPx: Float = 0f,
        out: IntArray = IntArray(4),
    ): IntArray {
        require(out.size >= 4) { "out must hold 4 values" }
        val r0 = max(startRadius, 0f)
        val r1 = max(endRadius, 0f)
        val pad = max(featherPx, 0f) + ANTIALIAS_PADDING_PX
        out[0] = floor(min(startX - r0, endX - r1) - pad).toInt()
        out[1] = floor(min(startY - r0, endY - r1) - pad).toInt()
        out[2] = ceil(max(startX + r0, endX + r1) + pad).toInt()
        out[3] = ceil(max(startY + r0, endY + r1) + pad).toInt()
        return out
    }

    fun isEmpty(bounds: IntArray): Boolean {
        require(bounds.size >= 4)
        return bounds[2] <= bounds[0] || bounds[3] <= bounds[1]
    }

    fun tilesFor(grid: TileGrid, bounds: IntArray): List<TileCoord> =
        if (isEmpty(bounds)) emptyList() else grid.tilesIntersecting(bounds)
}
