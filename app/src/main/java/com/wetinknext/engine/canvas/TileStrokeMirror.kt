package com.wetinknext.engine.canvas

import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.brush.StrokeRenderMode
import com.wetinknext.engine.core.StrokeDirtyBounds
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.undo.TileCoord

/** Replays committed strokes into their affected tiles for stage 3C validation. */
class TileStrokeMirror {
    data class Report(val touchedTiles: Int, val skippedTiles: Int, val residentTiles: Int) {
        val budgetRefused: Boolean get() = skippedTiles > 0
    }

    private val matrix = FloatArray(16)

    fun mirrorStroke(
        layer: PaintLayer, geometry: CanvasGeometry, blitter: StrokeBlitter, strokeTextureId: Int,
        dirtyBounds: IntArray, canvasWidth: Int, canvasHeight: Int,
        opacity: Float, erase: Boolean, strokeMode: StrokeRenderMode,
    ): Report = forEachTile(layer, dirtyBounds) { grid, coord, tile ->
        blitter.blitInto(
            target = tile, geometry = geometry, strokeTextureId = strokeTextureId,
            canvasToClip = TileTransform.buildCanvasToTileClip(grid, coord, matrix),
            viewportWidth = tile.width, viewportHeight = tile.height,
            canvasWidth = canvasWidth, canvasHeight = canvasHeight,
            opacity = opacity, erase = erase, strokeMode = strokeMode,
        )
    }

    fun mirrorNonBuildup(
        layer: PaintLayer, geometry: CanvasGeometry, renderer: NonBuildupStrokeRenderer,
        coverageTextureId: Int, colorLinear: FloatArray, dirtyBounds: IntArray,
        canvasWidth: Int, canvasHeight: Int, opacity: Float, erase: Boolean,
        strokeMode: StrokeRenderMode, edgeDarkening: Float,
    ): Report = forEachTile(layer, dirtyBounds) { grid, coord, tile ->
        renderer.blitInto(
            target = tile, geometry = geometry, coverageTextureId = coverageTextureId, colorLinear = colorLinear,
            canvasToClip = TileTransform.buildCanvasToTileClip(grid, coord, matrix),
            viewportWidth = tile.width, viewportHeight = tile.height,
            canvasWidth = canvasWidth, canvasHeight = canvasHeight, opacity = opacity,
            erase = erase, strokeMode = strokeMode, edgeDarkening = edgeDarkening,
        )
    }

    private inline fun forEachTile(
        layer: PaintLayer,
        dirtyBounds: IntArray,
        draw: (TileGrid, TileCoord, RenderTarget) -> Unit,
    ): Report {
        GlCheck.checkOnGlThread()
        val resources = layer.tileResources ?: return Report(0, 0, 0)
        val grid = resources.grid
        var touched = 0
        var skipped = 0
        StrokeDirtyBounds.tilesFor(grid, dirtyBounds).forEach { coord ->
            val tile = resources.obtain(coord)
            if (tile == null) {
                skipped++
                if (BuildConfig.DEBUG) Log.w(TAG, "GPU budget refused tile (${coord.tx},${coord.ty}) layer=${layer.id}")
            } else {
                draw(grid, coord, tile)
                touched++
            }
        }
        return Report(touched, skipped, resources.loadedTileCount)
    }

    private companion object { const val TAG = "TileStroke" }
}
