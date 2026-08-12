package com.wetinknext.engine.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayerTileCodecTest {
    @Test
    fun roundTripPreservesRgba8LayerPayload() {
        val pixels = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val decoded = LayerTileCodec.decode(LayerTileCodec.encode(2, 1, 4, pixels))

        checkNotNull(decoded)
        assertEquals(2, decoded.width)
        assertEquals(1, decoded.height)
        assertEquals(4, decoded.bytesPerPixel)
        assertArrayEquals(pixels, decoded.pixels)
    }

    @Test
    fun emptyPayloadMeansNewTransparentLayer() {
        assertNull(LayerTileCodec.decode(ByteArray(0)))
    }
}
