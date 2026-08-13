package com.wetinknext.engine.persistence

import com.wetinknext.engine.undo.TileCoord
import com.wetinknext.engine.undo.RawTileSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentLayerTilesTest {
    @Test
    fun ownedSnapshotRetainsCapturedBytesWithoutCopy() {
        val pixels = ByteArray(16)
        val raw = RawTileSnapshot(
            coord = TileCoord(0, 0),
            pixelLeft = 0,
            pixelTop = 0,
            pixelWidth = 2,
            pixelHeight = 2,
            bytesPerPixel = 4,
            rawBytes = pixels,
        )

        val retained = PersistentLayerTiles.fromRawOwned(raw)

        assertSame(pixels, retained.bytes)
    }

    @Test
    fun compressedPayloadRoundTripsTilePixels() {
        val pixels = ByteArray(256 * 256 * 4)
        val tile = PersistentLayerTiles.Tile(
            coord = TileCoord(1, 2),
            pixelLeft = 256,
            pixelTop = 512,
            pixelWidth = 256,
            pixelHeight = 256,
            bytesPerPixel = 4,
            bytes = pixels,
        )

        val payload = PersistentLayerTiles.encode(listOf(tile))
        val restored = PersistentLayerTiles.decode(payload).single()

        assertTrue("transparent tile should compress", payload.size < pixels.size / 10)
        assertEquals(tile.coord, restored.coord)
        assertArrayEquals(pixels, restored.bytes)
    }

    @Test
    fun versionOnePayloadRemainsReadableForMigration() {
        val pixels = ByteArray(16) { it.toByte() }
        val payload = ByteBuffer.allocate(12 + 32 + pixels.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0x57544C53)
            .putInt(1)
            .putInt(1)
            .putInt(0).putInt(0)
            .putInt(0).putInt(0)
            .putInt(2).putInt(2)
            .putInt(4).putInt(pixels.size)
            .put(pixels)
            .array()

        val restored = PersistentLayerTiles.decode(payload).single()

        assertArrayEquals(pixels, restored.bytes)
    }
}
