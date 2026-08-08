package com.wetinknext.engine.brush

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlCheck
import java.nio.ByteBuffer

class BrushTexture {
    var textureId: Int = 0
        private set

    var width: Int = 0
        private set

    var height: Int = 0
        private set

    fun createFromRgba(
        width: Int,
        height: Int,
        rgba: ByteBuffer,
    ) {
        GlCheck.checkOnGlThread()
        release()

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]

        check(textureId != 0) { "Failed to create brush texture" }

        this.width = width
        this.height = height

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

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
            rgba,
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
        GlCheck.checkOnGlThread()
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

}
