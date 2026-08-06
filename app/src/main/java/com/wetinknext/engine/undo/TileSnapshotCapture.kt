package com.wetinknext.engine.undo
import android.opengl.GLES30
import com.wetinknext.engine.gl.RenderTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
object TileSnapshotCapture {
    private var reusable: ByteBuffer? = null

    /** Captures every 256 px tile intersecting [bounds] = left, top, right, bottom. */
    fun capture(target: RenderTarget, bounds: IntArray): List<TileSnapshot> {
        require(bounds.size >= 4)
        val left = bounds[0].coerceIn(0, target.width)
        val top = bounds[1].coerceIn(0, target.height)
        val right = bounds[2].coerceIn(0, target.width)
        val bottom = bounds[3].coerceIn(0, target.height)
        if (right <= left || bottom <= top) return emptyList()

        val bytesPerPixel = if (target.usesHalfFloat) {
            TileSnapshot.BYTES_PER_PIXEL_RGBA16F
        } else {
            TileSnapshot.BYTES_PER_PIXEL_RGBA8
        }
        val glType = if (target.usesHalfFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        val result = ArrayList<TileSnapshot>()
        target.bind()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        for (tileY in top / TileSnapshot.TILE_SIZE..(bottom - 1) / TileSnapshot.TILE_SIZE) {
            for (tileX in left / TileSnapshot.TILE_SIZE..(right - 1) / TileSnapshot.TILE_SIZE) {
                val pixelLeft = tileX * TileSnapshot.TILE_SIZE
                val pixelTop = tileY * TileSnapshot.TILE_SIZE
                val pixelWidth = minOf(TileSnapshot.TILE_SIZE, target.width - pixelLeft)
                val pixelHeight = minOf(TileSnapshot.TILE_SIZE, target.height - pixelTop)
                val byteCount = pixelWidth * pixelHeight * bytesPerPixel
                val buffer = obtainBuffer(byteCount)
                buffer.clear()
                GLES30.glReadPixels(
                    pixelLeft, pixelTop, pixelWidth, pixelHeight,
                    GLES30.GL_RGBA, glType, buffer,
                )
                buffer.rewind()
                val rawBytes = ByteArray(byteCount)
                buffer.get(rawBytes)
                result += TileSnapshot(
                    coord = TileCoord(tileX, tileY),
                    pixelLeft = pixelLeft,
                    pixelTop = pixelTop,
                    pixelWidth = pixelWidth,
                    pixelHeight = pixelHeight,
                    bytesPerPixel = bytesPerPixel,
                    storedData = IdentityTileCompressor.compress(rawBytes),
                    compressor = IdentityTileCompressor,
                )
            }
        }
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return result
    }

    private fun obtainBuffer(size: Int): ByteBuffer {
        val current = reusable
        if (current != null && current.capacity() >= size) return current
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { reusable = it }
    }
}
