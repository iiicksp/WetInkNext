package com.wetinknext.engine.canvas

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.ShaderLib
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Blends one premultiplied-linear screen texture into the currently bound
 * framebuffer. Unlike [ScreenPresentRenderer], this deliberately performs no
 * colour-space conversion: it is used only while building a linear composite.
 */
class LinearTextureBlitter {
    private var program: GlProgram? = null
    private var vertexArrayId = 0
    private var vertexBufferId = 0
    private var textureUniform = -1

    fun create() {
        GlCheck.checkOnGlThread()
        release()

        program = GlProgram(ShaderLib.fullscreenVertex, ShaderLib.linearCopyFragment)
        val currentProgram = checkNotNull(program)
        currentProgram.use()
        textureUniform = GLES30.glGetUniformLocation(currentProgram.id, "uTexture")
        check(textureUniform >= 0) { "Linear copy uniform uTexture is missing" }

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val data = ByteBuffer
            .allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(vertices); position(0) }
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vertexArrayId = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        vertexBufferId = ids[0]
        GLES30.glBindVertexArray(vertexArrayId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBufferId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            data,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
    }

    /** The caller owns the framebuffer and viewport. */
    fun blit(sourceTextureId: Int) {
        val currentProgram = program ?: return
        if (sourceTextureId == 0 || vertexArrayId == 0) return

        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        currentProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(textureUniform, 0)
        GLES30.glBindVertexArray(vertexArrayId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        if (vertexBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
        if (vertexArrayId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
        vertexBufferId = 0
        vertexArrayId = 0
        program?.release()
        program = null
        textureUniform = -1
    }
}
