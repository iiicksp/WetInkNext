package com.wetinknext.engine.canvas

import android.opengl.GLES30
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib

/** Переносит готовый штрих из strokeTarget в слой: premultiplied source-over * opacity. */
class StrokeBlitter {
    private var program: GlProgram? = null
    private var uCanvasToClip = -1
    private var uCanvasSize = -1
    private var uStrokeTex = -1
    private var uOpacity = -1
    private var uStrokeMode = -1

    fun create() {
        release()
        program = GlProgram(ShaderLib.compositorVertex, ShaderLib.strokeBlitFragment)
        val p = checkNotNull(program)
        p.use()
        uCanvasToClip = GLES30.glGetUniformLocation(p.id, "uCanvasToClip")
        uCanvasSize = GLES30.glGetUniformLocation(p.id, "uCanvasSize")
        uStrokeTex = GLES30.glGetUniformLocation(p.id, "uStrokeTex")
        uOpacity = GLES30.glGetUniformLocation(p.id, "uOpacity")
        uStrokeMode = GLES30.glGetUniformLocation(p.id, "uStrokeMode")
        check(uCanvasToClip >= 0 && uCanvasSize >= 0 && uStrokeTex >= 0 && uOpacity >= 0 && uStrokeMode >= 0) {
            "StrokeBlitter uniforms missing"
        }
    }

    fun blit(
        layer: RenderTarget,
        geometry: CanvasGeometry,
        strokeTextureId: Int,
        canvasToFbo: FloatArray,
        width: Int,
        height: Int,
        opacity: Float,
        erase: Boolean = false,
        strokeMode: com.wetinknext.engine.brush.StrokeRenderMode = com.wetinknext.engine.brush.StrokeRenderMode.NORMAL_BUILDUP,
    ) = blitInto(
        target = layer, geometry = geometry, strokeTextureId = strokeTextureId,
        canvasToClip = canvasToFbo, viewportWidth = width, viewportHeight = height,
        canvasWidth = width, canvasHeight = height, opacity = opacity, erase = erase, strokeMode = strokeMode,
    )

    /** Blits into a full canvas target or a clipped tile target. */
    fun blitInto(
        target: RenderTarget,
        geometry: CanvasGeometry,
        strokeTextureId: Int,
        canvasToClip: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        opacity: Float,
        erase: Boolean = false,
        strokeMode: com.wetinknext.engine.brush.StrokeRenderMode = com.wetinknext.engine.brush.StrokeRenderMode.NORMAL_BUILDUP,
    ) {
        val p = program ?: return
        if (strokeTextureId == 0) return
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
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, strokeTextureId)
        GLES30.glUniform1i(uStrokeTex, 0)
        GLES30.glUniform1f(uOpacity, opacity.coerceIn(0f, 1f))
        
        val modeId = if (erase) 0 else when (strokeMode) {
            com.wetinknext.engine.brush.StrokeRenderMode.NORMAL_BUILDUP -> 0
            com.wetinknext.engine.brush.StrokeRenderMode.NON_BUILDUP -> 1
            com.wetinknext.engine.brush.StrokeRenderMode.MULTIPLY -> 2
        }
        GLES30.glUniform1i(uStrokeMode, modeId)

        geometry.draw()
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun release() {
        program?.release()
        program = null
        uCanvasToClip = -1; uCanvasSize = -1; uStrokeTex = -1; uOpacity = -1; uStrokeMode = -1
    }
}
