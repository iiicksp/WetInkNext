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
    private var uEdgeDarkening = -1
    private var uStrokeMode = -1

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
        uEdgeDarkening = GLES30.glGetUniformLocation(p.id, "uEdgeDarkening")
        uStrokeMode = GLES30.glGetUniformLocation(p.id, "uStrokeMode")
        check(uCanvasToClip >= 0 && uCanvasSize >= 0 && uCoverageTex >= 0 && uColorLinear >= 0 && uOpacity >= 0 && uEdgeDarkening >= 0 && uStrokeMode >= 0) {
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
        strokeMode: com.wetinknext.engine.brush.StrokeRenderMode = com.wetinknext.engine.brush.StrokeRenderMode.NORMAL_BUILDUP,
        edgeDarkening: Float = 0f,
    ) = blitInto(
        target = layer, geometry = geometry, coverageTextureId = coverageTextureId, colorLinear = colorLinear,
        canvasToClip = canvasToFbo, viewportWidth = width, viewportHeight = height,
        canvasWidth = width, canvasHeight = height, opacity = opacity, erase = erase,
        strokeMode = strokeMode, edgeDarkening = edgeDarkening,
    )

    /** Blits into a full canvas target or a clipped tile target. */
    fun blitInto(
        target: RenderTarget,
        geometry: CanvasGeometry,
        coverageTextureId: Int,
        colorLinear: FloatArray,
        canvasToClip: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        opacity: Float,
        erase: Boolean = false,
        strokeMode: com.wetinknext.engine.brush.StrokeRenderMode = com.wetinknext.engine.brush.StrokeRenderMode.NORMAL_BUILDUP,
        edgeDarkening: Float = 0f,
    ) {
        val p = program ?: return
        if (coverageTextureId == 0 || colorLinear.size < 3) return
        target.bind()
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        if (erase) {
            // Erase premultiplied RGB and alpha by the rendered stroke coverage.
            GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        } else if (strokeMode == com.wetinknext.engine.brush.StrokeRenderMode.MULTIPLY) {
            if (com.wetinknext.engine.gl.GlCheck.hasFramebufferFetch) {
                GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ZERO)
            } else {
                GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            }
        } else {
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        p.use()
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, canvasToClip, 0)
        GLES30.glUniform2f(uCanvasSize, canvasWidth.toFloat(), canvasHeight.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coverageTextureId)
        GLES30.glUniform1i(uCoverageTex, 1)
        GLES30.glUniform3f(uColorLinear, colorLinear[0], colorLinear[1], colorLinear[2])
        GLES30.glUniform1f(uOpacity, opacity.coerceIn(0f, 1f))
        if (uEdgeDarkening >= 0) GLES30.glUniform1f(uEdgeDarkening, edgeDarkening)

        val modeId = if (erase) 0 else when (strokeMode) {
            com.wetinknext.engine.brush.StrokeRenderMode.NORMAL_BUILDUP -> 0
            com.wetinknext.engine.brush.StrokeRenderMode.NON_BUILDUP -> 1
            com.wetinknext.engine.brush.StrokeRenderMode.MULTIPLY -> 2
        }
        GLES30.glUniform1i(uStrokeMode, modeId)

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
