package com.wetinknext.engine.undo

import android.opengl.GLES30
import com.wetinknext.engine.gl.RenderTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Restores snapshot tiles into an already-created layer texture on the GL thread. */
object TileSnapshotRestore {
    private var reusableBuffer: ByteBuffer? = null

    fun restore(target: RenderTarget, tiles: List<TileSnapshot>) {
        if (tiles.isEmpty()) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target.textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        for (tile in tiles) {
            val data = tile.decompress()
            val buffer = obtainBuffer(data.size)
            buffer.clear()
            buffer.put(data)
            buffer.position(0)
            val glType = if (tile.bytesPerPixel == TileSnapshot.BYTES_PER_PIXEL_RGBA16F) {
                GLES30.GL_HALF_FLOAT
            } else {
                GLES30.GL_UNSIGNED_BYTE
            }
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                tile.pixelLeft,
                tile.pixelTop,
                tile.pixelWidth,
                tile.pixelHeight,
                GLES30.GL_RGBA,
                glType,
                buffer,
            )
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun obtainBuffer(size: Int): ByteBuffer {
        val current = reusableBuffer
        if (current != null && current.capacity() >= size) return current
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { reusableBuffer = it }
    }
}
