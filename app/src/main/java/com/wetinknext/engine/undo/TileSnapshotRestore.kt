package com.wetinknext.engine.undo

import android.opengl.GLES30
import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Restores snapshot tiles into an already-created layer texture on the GL thread. */
object TileSnapshotRestore {
    private var reusableBuffer: ByteBuffer? = null

    fun restore(target: RenderTarget, tiles: List<TileSnapshot>): Boolean {
        GlCheck.checkOnGlThread()
        if (target.textureId == 0) return false
        if (tiles.isEmpty()) return true

        // Discard an unrelated previous error, then verify every upload below.
        GLES30.glGetError()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target.textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        try {
            for (tile in tiles) {
                if (!isValidTile(target, tile)) return false
                val data = tile.decompress()
                if (data.size != tile.rawSize) return false

                val glType = when (tile.bytesPerPixel) {
                    TileSnapshot.BYTES_PER_PIXEL_RGBA16F -> GLES30.GL_HALF_FLOAT
                    TileSnapshot.BYTES_PER_PIXEL_RGBA8 -> GLES30.GL_UNSIGNED_BYTE
                    else -> return false
                }
                val buffer = obtainBuffer(data.size)
                buffer.clear()
                buffer.put(data)
                buffer.position(0)

                // buildCanvasToFbo maps canvas y=0 to the FBO's bottom row.
                val glBottom = tile.pixelTop
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "restore tex=${target.textureId} tile=(${tile.coord.tx},${tile.coord.ty}) " +
                        "canvas=[${tile.pixelLeft},${tile.pixelTop},${tile.pixelWidth},${tile.pixelHeight}] glBottom=$glBottom")
                }
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, tile.pixelLeft, glBottom,
                    tile.pixelWidth, tile.pixelHeight, GLES30.GL_RGBA, glType, buffer,
                )
                if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return false
            }
            return true
        } catch (error: Throwable) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Restore failed", error)
            return false
        } finally {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun isValidTile(target: RenderTarget, tile: TileSnapshot): Boolean {
        if (tile.pixelWidth <= 0 || tile.pixelHeight <= 0) return false
        if (tile.pixelLeft < 0 || tile.pixelTop < 0) return false
        if (tile.pixelLeft + tile.pixelWidth > target.width ||
            tile.pixelTop + tile.pixelHeight > target.height) return false
        val expectedBytesPerPixel = if (target.usesHalfFloat) {
            TileSnapshot.BYTES_PER_PIXEL_RGBA16F
        } else {
            TileSnapshot.BYTES_PER_PIXEL_RGBA8
        }
        return tile.bytesPerPixel == expectedBytesPerPixel
    }

    private fun obtainBuffer(size: Int): ByteBuffer {
        val current = reusableBuffer
        if (current != null && current.capacity() >= size) return current
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { reusableBuffer = it }
    }

    private const val TAG = "TileUndo"
}
