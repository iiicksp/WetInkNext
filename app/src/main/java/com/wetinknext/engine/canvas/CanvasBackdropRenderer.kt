package com.wetinknext.engine.canvas

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.ShaderLib
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Draws the editor workspace pattern in one GPU pass. */
class CanvasBackdropRenderer {
    private var program: GlProgram? = null
    private var vertexArrayId = 0
    private var vertexBufferId = 0
    private var backgroundColorUniform = -1
    private var gridColorUniform = -1
    private var modeUniform = -1

    fun create() {
        GlCheck.checkOnGlThread()
        release()

        program = GlProgram(ShaderLib.fullscreenVertex, ShaderLib.canvasBackdropFragment)
        val currentProgram = checkNotNull(program)
        currentProgram.use()
        backgroundColorUniform = GLES30.glGetUniformLocation(currentProgram.id, "uBackgroundColor")
        gridColorUniform = GLES30.glGetUniformLocation(currentProgram.id, "uGridColor")
        modeUniform = GLES30.glGetUniformLocation(currentProgram.id, "uMode")
        check(backgroundColorUniform >= 0 && gridColorUniform >= 0 && modeUniform >= 0) {
            "Canvas backdrop uniforms missing"
        }

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val vertexData = ByteBuffer
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
            vertexData,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
        GlCheck.noError("CanvasBackdropRenderer create")
    }

    fun render(
        viewportWidth: Int,
        viewportHeight: Int,
        backgroundColor: FloatArray,
        gridColor: FloatArray,
        mode: Int,
    ) {
        val currentProgram = program ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0 || vertexArrayId == 0) return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        currentProgram.use()
        GLES30.glUniform3fv(backgroundColorUniform, 1, backgroundColor, 0)
        GLES30.glUniform3fv(gridColorUniform, 1, gridColor, 0)
        GLES30.glUniform1i(modeUniform, mode)
        GLES30.glBindVertexArray(vertexArrayId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (vertexBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
        if (vertexArrayId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
        vertexArrayId = 0
        vertexBufferId = 0
        program?.release()
        program = null
        backgroundColorUniform = -1
        gridColorUniform = -1
        modeUniform = -1
    }

    companion object {
        const val MODE_GRID = 1
        const val MODE_CHECKERBOARD = 2
    }
}
