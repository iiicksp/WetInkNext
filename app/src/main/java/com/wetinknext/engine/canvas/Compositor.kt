package com.wetinknext.engine.canvas

import android.opengl.GLES30
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib
import com.wetinknext.engine.brush.StrokeRenderMode

/** Presents visible layers bottom-to-top, inserting the active preview at its layer position. */
class Compositor {
    private var program: GlProgram? = null
    private var uCanvasToClip = -1
    private var uCanvasSize = -1
    private var uLayerTex = -1
    private var uStrokeTex = -1
    private var uScreenStrokeTex = -1
    private var uStrokeCoverageTex = -1
    private var uStrokeActive = -1
    private var uStrokeIsScreenSpace = -1
    private var uStrokeMode = -1
    private var uStrokeErase = -1
    private var uOpacity = -1
    private var uStrokeOpacity = -1
    private var uStrokeColorLinear = -1

    fun create() {
        GlCheck.checkOnGlThread()
        release()
        program = GlProgram(ShaderLib.compositorVertex, ShaderLib.compositorFragment)
        val currentProgram = checkNotNull(program)
        currentProgram.use()
        uCanvasToClip = GLES30.glGetUniformLocation(currentProgram.id, "uCanvasToClip")
        uCanvasSize = GLES30.glGetUniformLocation(currentProgram.id, "uCanvasSize")
        uLayerTex = GLES30.glGetUniformLocation(currentProgram.id, "uLayerTex")
        uStrokeTex = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeTex")
        uScreenStrokeTex = GLES30.glGetUniformLocation(currentProgram.id, "uScreenStrokeTex")
        uStrokeCoverageTex = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeCoverageTex")
        uStrokeActive = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeActive")
        uStrokeIsScreenSpace = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeIsScreenSpace")
        uStrokeMode = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeMode")
        uStrokeErase = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeErase")
        uOpacity = GLES30.glGetUniformLocation(currentProgram.id, "uOpacity")
        uStrokeOpacity = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeOpacity")
        uStrokeColorLinear = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeColorLinear")
        check(uCanvasToClip >= 0 && uCanvasSize >= 0 && uLayerTex >= 0 &&
            uStrokeTex >= 0 && uScreenStrokeTex >= 0 && uStrokeCoverageTex >= 0 &&
            uStrokeActive >= 0 && uStrokeIsScreenSpace >= 0 && uStrokeMode >= 0 &&
            uStrokeErase >= 0 && uOpacity >= 0 && uStrokeOpacity >= 0 && uStrokeColorLinear >= 0) {
            "Compositor uniforms missing: canvasToClip=$uCanvasToClip, " +
                "canvasSize=$uCanvasSize, layerTex=$uLayerTex, strokeTex=$uStrokeTex, " +
                "screenStrokeTex=$uScreenStrokeTex, strokeActive=$uStrokeActive, " +
                "strokeIsScreenSpace=$uStrokeIsScreenSpace, strokeErase=$uStrokeErase, " +
                "opacity=$uOpacity, strokeOpacity=$uStrokeOpacity"
        }
        GlCheck.noError("Compositor create")
    }

    fun render(
        destination: RenderTarget? = null,
        geometry: CanvasGeometry,
        layers: LayerStack,
        activeLayerId: Long,
        strokeTextureId: Int,
        strokeCoverageTextureId: Int = 0,
        strokeIsScreenSpace: Boolean = false,
        strokeMode: StrokeRenderMode = StrokeRenderMode.NORMAL_BUILDUP,
        strokeErase: Boolean,
        strokeColorLinear: FloatArray = DEFAULT_STROKE_COLOR,
        canvasToClip: FloatArray,
        strokeOpacity: Float,
        firstLayerIndex: Int = 0,
        lastLayerExclusive: Int = Int.MAX_VALUE,
    ) {
        val currentProgram = program ?: return
        destination?.bind()
        currentProgram.use()
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, canvasToClip, 0)
        GLES30.glUniform2f(uCanvasSize, layers.canvasWidth.toFloat(), layers.canvasHeight.toFloat())
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        for ((index, layer) in layers.allLayers().withIndex()) {
            if (index < firstLayerIndex || index >= lastLayerExclusive) continue
            if (!layer.created || !layer.isVisible) continue
            val hasStroke = layer.id == activeLayerId &&
                (strokeTextureId != 0 || strokeCoverageTextureId != 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layer.target.textureId)
            GLES30.glUniform1i(uLayerTex, 0)
            if (hasStroke) {
                val textureUnit = if (strokeIsScreenSpace) GLES30.GL_TEXTURE2 else GLES30.GL_TEXTURE1
                GLES30.glActiveTexture(textureUnit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, strokeTextureId)
                GLES30.glUniform1i(
                    if (strokeIsScreenSpace) uScreenStrokeTex else uStrokeTex,
                    if (strokeIsScreenSpace) 2 else 1,
                )
                if (strokeMode == StrokeRenderMode.NON_BUILDUP) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, strokeCoverageTextureId)
                    GLES30.glUniform1i(uStrokeCoverageTex, 3)
                }
            }
            GLES30.glUniform1i(uStrokeActive, if (hasStroke) 1 else 0)
            GLES30.glUniform1i(uStrokeIsScreenSpace, if (hasStroke && strokeIsScreenSpace) 1 else 0)
            val modeId = if (hasStroke) {
                when (strokeMode) {
                    StrokeRenderMode.NORMAL_BUILDUP -> 0
                    StrokeRenderMode.NON_BUILDUP -> 1
                    StrokeRenderMode.MULTIPLY -> 2
                }
            } else 0
            GLES30.glUniform1i(uStrokeMode, modeId)
            GLES30.glUniform1i(uStrokeErase, if (hasStroke && strokeErase) 1 else 0)
            GLES30.glUniform1f(uOpacity, layer.opacity.coerceIn(0f, 1f))
            GLES30.glUniform1f(uStrokeOpacity, strokeOpacity.coerceIn(0f, 1f))
            GLES30.glUniform3f(uStrokeColorLinear, strokeColorLinear[0], strokeColorLinear[1], strokeColorLinear[2])
            geometry.draw()
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * Draws one layer into the currently bound target.
     *
     * This is deliberately separate from [render]: a layer-preview needs the
     * layer bitmap even when the layer is currently hidden in the project. Its
     * opacity is still applied, while a blend mode has no second layer to blend
     * against in this one-layer path.
     */
    fun renderLayer(
        geometry: CanvasGeometry,
        layer: PaintLayer,
        canvasWidth: Int = layer.target.width,
        canvasHeight: Int = layer.target.height,
        canvasToClip: FloatArray,
    ) {
        if (!layer.created || canvasWidth <= 0 || canvasHeight <= 0) return
        val currentProgram = program ?: return
        currentProgram.use()
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, canvasToClip, 0)
        GLES30.glUniform2f(uCanvasSize, canvasWidth.toFloat(), canvasHeight.toFloat())
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layer.target.textureId)
        GLES30.glUniform1i(uLayerTex, 0)
        GLES30.glUniform1i(uStrokeActive, 0)
        GLES30.glUniform1i(uStrokeIsScreenSpace, 0)
        GLES30.glUniform1i(uStrokeMode, 0)
        GLES30.glUniform1i(uStrokeErase, 0)
        GLES30.glUniform1f(uOpacity, layer.opacity.coerceIn(0f, 1f))
        GLES30.glUniform1f(uStrokeOpacity, 1f)
        GLES30.glUniform3f(uStrokeColorLinear, 0f, 0f, 0f)
        geometry.draw()

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun release() {
        GlCheck.checkOnGlThread()
        program?.release()
        program = null
        uCanvasToClip = -1
        uCanvasSize = -1
        uLayerTex = -1
        uStrokeTex = -1
        uScreenStrokeTex = -1
        uStrokeCoverageTex = -1
        uStrokeActive = -1
        uStrokeIsScreenSpace = -1
        uStrokeMode = -1
        uStrokeErase = -1
        uOpacity = -1
        uStrokeOpacity = -1
        uStrokeColorLinear = -1
    }

    private companion object {
        val DEFAULT_STROKE_COLOR = floatArrayOf(0f, 0f, 0f)
    }
}
