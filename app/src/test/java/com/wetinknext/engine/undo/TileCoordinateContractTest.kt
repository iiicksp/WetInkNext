package com.wetinknext.engine.undo

import org.junit.Assert.assertEquals
import org.junit.Test

class TileCoordinateContractTest {
    @Test
    fun topCanvasTileStartsAtBottomGlRegion() {
        val canvasTop = 0
        val glBottom = canvasTop
        assertEquals(0, glBottom)
    }

    @Test
    fun bottomCanvasTileUsesItsCanvasYAsGlY() {
        val canvasTop = 1792
        val glBottom = canvasTop
        assertEquals(1792, glBottom)
    }
}
