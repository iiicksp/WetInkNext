package com.wetinknext.engine.brush

import android.graphics.Bitmap
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BrushTexture {
    var textureId: Int = 0
        private set

    var width: Int = 0
        private set

    var height: Int = 0
        private set

    fun createFromBitmap(bitmap: Bitmap) {
        checkOnGlThread()

        release()

        val pixels = ByteBuffer
            .allocateDirect(bitmap.width * bitmap.height * 4)
            .order(ByteOrder.nativeOrder())

        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(
            argb,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height,
        )

        for (pixel in argb) {
            pixels.put(((pixel shr 16) and 0xFF).toByte())
            pixels.put(((pixel shr 8) and 0xFF).toByte())
            pixels.put((pixel and 0xFF).toByte())
            pixels.put(((pixel ushr 24) and 0xFF).toByte())
        }

        pixels.position(0)

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]

        check(textureId != 0) {
            "Failed to create brush texture"
        }

        width = bitmap.width
        height = bitmap.height

        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_2D,
            textureId,
        )

        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_REPEAT,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_REPEAT,
        )

        GLES30.glPixelStorei(
            GLES30.GL_UNPACK_ALIGNMENT,
            1,
        )

        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels,
        )

        GLES30.glPixelStorei(
            GLES30.GL_UNPACK_ALIGNMENT,
            4,
        )

        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_2D,
            0,
        )
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(
                1,
                intArrayOf(textureId),
                0,
            )
        }

        textureId = 0
        width = 0
        height = 0
    }

    private fun checkOnGlThread() {
        // Вызывается только из GLSurfaceView.Renderer.
    }
}
