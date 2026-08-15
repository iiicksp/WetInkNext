package com.wetinknext.engine.core

import com.wetinknext.engine.canvas.TileGrid
import com.wetinknext.engine.canvas.TileTransform
import com.wetinknext.engine.undo.TileCoord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeDirtyBoundsTest {
    private val grid = TileGrid(4096, 2160)

    @Test fun shortSegmentTouchesOneTile() {
        assertEquals(listOf(TileCoord(0, 0)), StrokeDirtyBounds.tilesForSegment())
    }

    @Test fun seamAndCornerSelectNeighbouringTiles() {
        assertEquals(
            listOf(TileCoord(0, 0), TileCoord(1, 0)),
            StrokeDirtyBounds.tilesFor(grid, StrokeDirtyBounds.segmentBounds(252f, 100f, 260f, 100f, 1f)),
        )
        assertEquals(
            listOf(TileCoord(0, 0), TileCoord(1, 0), TileCoord(0, 1), TileCoord(1, 1)),
            StrokeDirtyBounds.tilesFor(grid, StrokeDirtyBounds.segmentBounds(256f, 256f, 256f, 256f, 2f)),
        )
    }

    @Test fun offCanvasBoundsClipToValidTiles() {
        val bounds = StrokeDirtyBounds.segmentBounds(4090f, 5f, 4094f, 5f, 40f)
        assertTrue(bounds[2] > grid.canvasWidth)
        val tiles = StrokeDirtyBounds.tilesFor(grid, bounds)
        assertTrue(tiles.all(grid::contains))
        assertEquals(listOf(TileCoord(15, 0)), tiles)
    }

    @Test fun bottomPartialTileAndEmptyBoundsWork() {
        assertEquals(listOf(TileCoord(1, 8)), StrokeDirtyBounds.tilesFor(
            grid, StrokeDirtyBounds.segmentBounds(300f, 2150f, 320f, 2158f, 0f),
        ))
        assertTrue(StrokeDirtyBounds.tilesFor(grid, intArrayOf(0, 0, 0, 0)).isEmpty())
    }

    @Test fun featherAndTaperExpandBounds() {
        val hard = StrokeDirtyBounds.segmentBounds(300f, 300f, 300f, 300f, 10f)
        val soft = StrokeDirtyBounds.segmentBounds(300f, 300f, 300f, 300f, 10f, featherPx = 30f)
        assertTrue(soft[0] < hard[0] && soft[2] > hard[2])
        val taper = StrokeDirtyBounds.segmentBounds(500f, 500f, 520f, 500f, 2f, 60f)
        assertEquals(listOf(458, 438, 582, 562), taper.toList())
    }

    @Test fun tileMatrixMapsTileCornersToClipCorners() {
        val m = TileTransform.buildCanvasToTileClip(grid, TileCoord(15, 8), FloatArray(16))
        assertEquals(-1f, m[0] * 3840f + m[12], 1e-4f)
        assertEquals(1f, m[0] * 4096f + m[12], 1e-4f)
        assertEquals(-1f, m[5] * 2048f + m[13], 1e-4f)
        assertEquals(1f, m[5] * 2160f + m[13], 1e-4f)
    }

    private fun StrokeDirtyBounds.tilesForSegment(): List<TileCoord> =
        tilesFor(grid, segmentBounds(100f, 100f, 150f, 120f, 6f))
}
