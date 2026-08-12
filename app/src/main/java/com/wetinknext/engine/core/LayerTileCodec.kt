package com.wetinknext.engine.core

import android.opengl.GLES30
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.persistence.PersistentLayerTiles
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stable payload for a complete layer texture. Empty data means a newly-created
 * layer; later persistence can emit this format without changing the loader.
 */
object LayerTileCodec {
    private const val MAGIC = 0x5754494C // WTIL
    private const val VERSION = 1
    private const val HEADER_BYTES = 20

    data class Decoded(val width: Int, val height: Int, val bytesPerPixel: Int, val pixels: ByteArray)

    fun encode(width: Int, height: Int, bytesPerPixel: Int, pixels: ByteArray): ByteArray {
        require(width > 0 && height > 0 && bytesPerPixel in setOf(4, 8)) { "Invalid layer tile header" }
        val expected = width.toLong() * height * bytesPerPixel
        require(expected <= Int.MAX_VALUE && pixels.size == expected.toInt()) { "Invalid layer tile size" }
        return ByteBuffer.allocate(HEADER_BYTES + pixels.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .putInt(VERSION)
            .putInt(width)
            .putInt(height)
            .putInt(bytesPerPixel)
            .put(pixels)
            .array()
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.isEmpty()) return null
        require(bytes.size >= HEADER_BYTES) { "Layer tile payload is truncated" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "Unknown layer tile payload" }
        require(buffer.int == VERSION) { "Unsupported layer tile version" }
        val width = buffer.int
        val height = buffer.int
        val bytesPerPixel = buffer.int
        require(width > 0 && height > 0 && bytesPerPixel in setOf(4, 8)) { "Invalid layer tile header" }
        val expected = width.toLong() * height * bytesPerPixel
        require(expected <= Int.MAX_VALUE && bytes.size - HEADER_BYTES == expected.toInt()) { "Invalid layer tile size" }
        return Decoded(width, height, bytesPerPixel, bytes.copyOfRange(HEADER_BYTES, bytes.size))
    }

    fun upload(target: RenderTarget, bytes: ByteArray) {
        val decoded = decode(bytes) ?: return
        require(decoded.width == target.width && decoded.height == target.height) { "Layer tile dimensions do not match document" }
        val expectedBpp = if (target.usesHalfFloat) 8 else 4
        require(decoded.bytesPerPixel == expectedBpp) { "Layer tile pixel format does not match GPU target" }

        val data = ByteBuffer.allocateDirect(decoded.pixels.size).order(ByteOrder.nativeOrder())
        data.put(decoded.pixels).flip()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target.textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        try {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, decoded.width, decoded.height,
                GLES30.GL_RGBA,
                if (decoded.bytesPerPixel == 8) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE,
                data,
            )
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Layer texture upload failed" }
        } finally {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
    }

    fun uploadPersistent(target: RenderTarget, payload: ByteArray) {
        if (payload.isEmpty()) return
        val expectedBpp = if (target.usesHalfFloat) 8 else 4
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target.textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        try {
            PersistentLayerTiles.decode(payload).forEach { tile ->
                require(tile.bytesPerPixel == expectedBpp && tile.pixelLeft + tile.pixelWidth <= target.width && tile.pixelTop + tile.pixelHeight <= target.height) { "Invalid persistent tile" }
                val data = ByteBuffer.allocateDirect(tile.bytes.size).order(ByteOrder.nativeOrder())
                data.put(tile.bytes).flip()
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, tile.pixelLeft, tile.pixelTop, tile.pixelWidth, tile.pixelHeight, GLES30.GL_RGBA, if (expectedBpp == 8) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE, data)
            }
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Persistent tile upload failed" }
        } finally {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
    }
}
