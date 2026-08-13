package com.wetinknext.engine.canvas

import android.opengl.GLES30
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib

/** Applies a fixed linear stroke colour once, using a union coverage mask. */
class NonBuildupStrokeRenderer {
    private var program: GlProgram? = null
    private var uCanvasToClip = -1
    private var uCanvasSize = -1
    private var uCoverageTex = -1
    private var uColorLinear = -1
    private var uOpacity = -1

    fun create() {
        release()
        program = GlProgram(ShaderLib.compositorVertex, ShaderLib.nonBuildupStrokeFragment)
        val p = checkNotNull(program)
        p.use()
        uCanvasToClip = GLES30.glGetUniformLocation(p.id, "uCanvasToClip")
        uCanvasSize = GLES30.glGetUniformLocation(p.id, "uCanvasSize")
        uCoverageTex = GLES30.glGetUniformLocation(p.id, "uCoverageTex")
        uColorLinear = GLES30.glGetUniformLocation(p.id, "uColorLinear")
        uOpacity = GLES30.glGetUniformLocation(p.id, "uOpacity")
        check(uCanvasToClip >= 0 && uCanvasSize >= 0 && uCoverageTex >= 0 && uColorLinear >= 0 && uOpacity >= 0) {
            "NonBuildupStrokeRenderer uniforms missing"
        }
    }

    fun blit(
        layer: RenderTarget,
        geometry: CanvasGeometry,
        coverageTextureId: Int,
        colorLinear: FloatArray,
        canvasToFbo: FloatArray,
        width: Int,
        height: Int,
        opacity: Float,
        erase: Boolean = false,
    ) {
        val p = program ?: return
        if (coverageTextureId == 0 || colorLinear.size < 3) return
        layer.bind()
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        if (erase) {
            GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        p.use()
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, canvasToFbo, 0)
        GLES30.glUniform2f(uCanvasSize, width.toFloat(), height.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coverageTextureId)
        GLES30.glUniform1i(uCoverageTex, 1)
        GLES30.glUniform3f(uColorLinear, colorLinear[0], colorLinear[1], colorLinear[2])
        GLES30.glUniform1f(uOpacity, opacity.coerceIn(0f, 1f))
        geometry.draw()
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun release() {
        program?.release()
        program = null
        uCanvasToClip = -1
        uCanvasSize = -1
        uCoverageTex = -1
        uColorLinear = -1
        uOpacity = -1
    }
}
