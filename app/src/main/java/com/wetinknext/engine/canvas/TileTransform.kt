package com.wetinknext.engine.canvas

import com.wetinknext.engine.undo.TileCoord

/** Canvas-to-clip matrix for rendering the existing canvas quad into one tile. */
object TileTransform {
    fun buildCanvasToTileClip(grid: TileGrid, coord: TileCoord, out: FloatArray): FloatArray {
        require(out.size >= 16) { "out must hold 16 values" }
        require(grid.contains(coord)) { "tile $coord is outside $grid" }
        val left = grid.tileLeft(coord.tx).toFloat()
        val top = grid.tileTop(coord.ty).toFloat()
        val width = grid.tileWidth(coord.tx).toFloat()
        val height = grid.tileHeight(coord.ty).toFloat()
        out[0] = 2f / width; out[1] = 0f; out[2] = 0f; out[3] = 0f
        out[4] = 0f; out[5] = 2f / height; out[6] = 0f; out[7] = 0f
        out[8] = 0f; out[9] = 0f; out[10] = 1f; out[11] = 0f
        out[12] = -1f - 2f * left / width
        out[13] = -1f - 2f * top / height
        out[14] = 0f; out[15] = 1f
        return out
    }
}
