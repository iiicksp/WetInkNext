package com.wetinknext.engine.undo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TileSnapshotTest {
    @Test
    fun rawIdentityTileKeepsItsExactBytes() {
        val bytes = byteArrayOf(0, 10, 20, 30, 40, 50, 60, 70)
        val tile = TileSnapshot(
            coord = TileCoord(2, 3),
            pixelLeft = 512,
            pixelTop = 768,
            pixelWidth = 1,
            pixelHeight = 2,
            bytesPerPixel = TileSnapshot.BYTES_PER_PIXEL_RGBA8,
            storedData = bytes,
        )

        assertEquals(bytes.size, tile.memorySize)
        assertEquals(bytes.size, tile.rawSize)
        assertArrayEquals(bytes, tile.decompress())
    }

    @Test
    fun tileSizeIsTheSpecified256Pixels() {
        assertEquals(256, TileSnapshot.TILE_SIZE)
    }
}
