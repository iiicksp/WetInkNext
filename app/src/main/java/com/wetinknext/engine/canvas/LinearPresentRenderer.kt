package com.wetinknext.engine.canvas

import android.opengl.GLES30
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.ShaderLib

/** Final pass: presents a premultiplied-linear canvas texture to the sRGB screen. */
class LinearPresentRenderer {
    private var program: GlProgram? = null
    private var uCanvasToClip = -1
    private var uCanvasSize = -1
    private var uTexture = -1

    fun create() {
        GlCheck.checkOnGlThread()
        release()
        program = GlProgram(ShaderLib.compositorVertex, ShaderLib.linearPresentFragment)
        val currentProgram = checkNotNull(program)
        currentProgram.use()
        uCanvasToClip = GLES30.glGetUniformLocation(currentProgram.id, "uCanvasToClip")
        uCanvasSize = GLES30.glGetUniformLocation(currentProgram.id, "uCanvasSize")
        uTexture = GLES30.glGetUniformLocation(currentProgram.id, "uTexture")
        check(uCanvasToClip >= 0 && uCanvasSize >= 0 && uTexture >= 0) {
            "Linear present uniforms missing"
        }
        GlCheck.noError("LinearPresentRenderer create")
    }

    fun render(
        sourceTextureId: Int,
        geometry: CanvasGeometry,
        canvasToClipMatrix: FloatArray,
        canvasWidth: Int,
        canvasHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        if (sourceTextureId == 0) return
        val currentProgram = program ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        // A hidden background layer leaves transparent document pixels. Blend
        // them over the editor backdrop instead of replacing it with black.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        currentProgram.use()
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, canvasToClipMatrix, 0)
        GLES30.glUniform2f(uCanvasSize, canvasWidth.toFloat(), canvasHeight.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(uTexture, 0)
        geometry.draw()
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun release() {
        GlCheck.checkOnGlThread()
        program?.release()
        program = null
        uCanvasToClip = -1
        uCanvasSize = -1
        uTexture = -1
    }
}
