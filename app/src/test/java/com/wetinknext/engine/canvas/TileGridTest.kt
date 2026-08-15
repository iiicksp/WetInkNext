package com.wetinknext.engine.canvas

import com.wetinknext.engine.undo.TileCoord
import com.wetinknext.engine.undo.TileSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridTest {
    private fun largeCanvas() = TileGrid(4096, 2160)

    @Test fun largeCanvasUsesSixteenByNineTiles() {
        val grid = largeCanvas()
        assertEquals(TileSnapshot.TILE_SIZE, grid.tileSize)
        assertEquals(16, grid.tilesX)
        assertEquals(9, grid.tilesY)
        assertEquals(144, grid.tileCount)
    }

    @Test fun edgeTileBoundsNeverLeaveCanvas() {
        val grid = largeCanvas()
        assertEquals(listOf(3840, 2048, 4096, 2160), grid.tileBounds(TileCoord(15, 8)).toList())
        assertEquals(112, grid.tileHeight(8))
    }

    @Test fun everyTileCoversCanvasExactly() {
        val grid = largeCanvas()
        var area = 0L
        val bounds = IntArray(4)
        grid.allTiles().forEach { coord ->
            grid.tileBounds(coord, bounds)
            assertTrue(bounds[0] >= 0 && bounds[1] >= 0)
            assertTrue(bounds[2] <= grid.canvasWidth && bounds[3] <= grid.canvasHeight)
            area += (bounds[2] - bounds[0]).toLong() * (bounds[3] - bounds[1])
        }
        assertEquals(4096L * 2160L, area)
    }

    @Test fun intersectingBoundsUseHalfOpenEdges() {
        val grid = largeCanvas()
        assertEquals(listOf(TileCoord(0, 0)), grid.tilesIntersecting(0, 0, 256, 256))
        assertEquals(
            listOf(TileCoord(0, 0), TileCoord(1, 0), TileCoord(0, 1), TileCoord(1, 1)),
            grid.tilesIntersecting(250, 250, 260, 260),
        )
    }

    @Test fun boundsClampAndEmptyBoundsProduceNoTiles() {
        val grid = largeCanvas()
        assertEquals(grid.tileCount, grid.tilesIntersecting(-500, -500, 9000, 9000).size)
        assertTrue(grid.tilesIntersecting(100, 100, 100, 200).isEmpty())
        assertTrue(grid.tilesIntersecting(9000, 9000, 9500, 9500).isEmpty())
    }

    @Test fun coordAtMapsPixelsAndRejectsOffCanvas() {
        val grid = largeCanvas()
        assertEquals(TileCoord(1, 0), grid.coordAt(256, 0))
        assertEquals(TileCoord(15, 8), grid.coordAt(4095, 2159))
        assertNull(grid.coordAt(4096, 0))
    }
}
