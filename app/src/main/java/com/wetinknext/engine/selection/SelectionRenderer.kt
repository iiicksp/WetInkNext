package com.wetinknext.engine.selection

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GL half of the selection + transform pipeline:
 *
 *  - [renderMasked] samples a source texture through the selection mask with
 *    a 2x3 affine — the live transform preview and the "cut out" pass;
 *  - [renderAnts] draws the animated marching-ants edge on screen from the
 *    R8 mask texture.
 */
class SelectionRenderer {

    private var maskProgram: GlProgram? = null
    private var antsProgram: GlProgram? = null
    private var vaoId = 0
    private var quadBufferId = 0

    private var uSourceTex = -1
    private var uMaskTex = -1
    private var uCanvasSize = -1
    private var uAffine = -1
    private var uCutOut = -1

    private var antUMaskTex = -1
    private var antUCanvasSize = -1
    private var antUScreenSize = -1
    private var antUTime = -1
    private var antUStc0 = -1
    private var antUStc1 = -1
    private var antUStc2 = -1

    fun create() {
        GlCheck.checkOnGlThread()
        release()
        maskProgram = GlProgram(ShaderLib.fullscreenVertex, ShaderLib.maskedSampleFragment)
        antsProgram = GlProgram(ShaderLib.fullscreenVertex, ShaderLib.selectionOverlayFragment)
        val masked = checkNotNull(maskProgram)
        val ants = checkNotNull(antsProgram)

        masked.use()
        uSourceTex = GLES30.glGetUniformLocation(masked.id, "uSourceTex")
        uMaskTex = GLES30.glGetUniformLocation(masked.id, "uMaskTex")
        uCanvasSize = GLES30.glGetUniformLocation(masked.id, "uCanvasSize")
        uAffine = GLES30.glGetUniformLocation(masked.id, "uAffine")
        uCutOut = GLES30.glGetUniformLocation(masked.id, "uCutOut")

        ants.use()
        antUMaskTex = GLES30.glGetUniformLocation(ants.id, "uMaskTex")
        antUCanvasSize = GLES30.glGetUniformLocation(ants.id, "uCanvasSize")
        antUScreenSize = GLES30.glGetUniformLocation(ants.id, "uScreenSize")
        antUTime = GLES30.glGetUniformLocation(ants.id, "uTime")
        antUStc0 = GLES30.glGetUniformLocation(ants.id, "uScreenToCanvas0")
        antUStc1 = GLES30.glGetUniformLocation(ants.id, "uScreenToCanvas1")
        antUStc2 = GLES30.glGetUniformLocation(ants.id, "uScreenToCanvas2")

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        quadBufferId = ids[0]
        GLES30.glGenVertexArrays(1, ids, 0)
        vaoId = ids[0]

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBufferId)
        val data = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        data.put(vertices).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    /** Draws source masked by [maskTextureId] (or cut out when [cutOut]) into [target]. */
    fun renderMasked(
        target: RenderTarget,
        source: RenderTarget,
        maskTextureId: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        affine: FloatArray,
        cutOut: Boolean,
    ) {
        val program = maskProgram ?: return
        if (maskTextureId == 0) return
        target.bind()
        GLES30.glViewport(0, 0, canvasWidth, canvasHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source.textureId)
        GLES30.glUniform1i(uSourceTex, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(uMaskTex, 1)
        GLES30.glUniform2f(uCanvasSize, canvasWidth.toFloat(), canvasHeight.toFloat())
        GLES30.glUniform1fv(uAffine, 1, affine, 0)
        GLES30.glUniform1i(uCutOut, if (cutOut) 1 else 0)
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlCheck.noError("SelectionRenderer.renderMasked")
    }

    /** Draws the animated marching-ants edge into the current framebuffer. */
    fun renderAnts(
        maskTextureId: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        screenToCanvas: FloatArray,
        timeSeconds: Float,
    ) {
        val program = antsProgram ?: return
        if (maskTextureId == 0) return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(antUMaskTex, 0)
        GLES30.glUniform2f(antUCanvasSize, canvasWidth.toFloat(), canvasHeight.toFloat())
        GLES30.glUniform2f(antUScreenSize, screenWidth.toFloat(), screenHeight.toFloat())
        GLES30.glUniform1f(antUTime, timeSeconds)
        GLES30.glUniform2f(antUStc0, screenToCanvas[0], screenToCanvas[1])
        GLES30.glUniform2f(antUStc1, screenToCanvas[2], screenToCanvas[3])
        GLES30.glUniform2f(antUStc2, screenToCanvas[4], screenToCanvas[5])
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_BLEND)
        GlCheck.noError("SelectionRenderer.renderAnts")
    }

    fun release() {
        GlCheck.checkOnGlThread()
        maskProgram?.release()
        maskProgram = null
        antsProgram?.release()
        antsProgram = null
        if (vaoId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
        if (quadBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadBufferId), 0)
        vaoId = 0
        quadBufferId = 0
    }
}