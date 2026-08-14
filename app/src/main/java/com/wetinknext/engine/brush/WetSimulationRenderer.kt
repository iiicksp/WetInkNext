package com.wetinknext.engine.brush

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib
import com.wetinknext.engine.gl.GlCheck

/**
 * Fullscreen fluid step for WET brushes.
 *
 * The fluid buffer is one RGBA texture per canvas size: RGB = premultiplied
 * pigment, A = water amount (0..1). Each `step` advects the fluid by the
 * live brush-tip velocity, diffuses water + pigment at their own rates, and
 * coagulates pigment where water falls fastest (the darker wet edge).
 *
 * A `finalize` step turns the buffer into a pigment-coverage RGBA that is
 * then source-over merged into the layer, so a drying wash keeps its colour
 * and is not faded by leftover water.
 */
class WetSimulationRenderer {

    private var program: GlProgram? = null

    private var uPixelSize = -1
    private var uMotion = -1
    private var uSpread = -1
    private var uWetness = -1
    private var uBleed = -1
    private var uAdvection = -1
    private var uCoagulation = -1
    private var uEvaporation = -1
    private var uEdgeDarkening = -1
    private var uDeltaTime = -1
    private var uFinalize = -1

    private var vaoId = 0
    private var quadBufferId = 0

    fun create() {
        if (program != null) return

        val p = GlProgram(ShaderLib.fullscreenVertex, ShaderLib.wetSimFragment)
        program = p
        p.use()

        uPixelSize = GLES30.glGetUniformLocation(p.id, "uPixelSize")
        uMotion = GLES30.glGetUniformLocation(p.id, "uMotion")
        uSpread = GLES30.glGetUniformLocation(p.id, "uSpread")
        uWetness = GLES30.glGetUniformLocation(p.id, "uWetness")
        uBleed = GLES30.glGetUniformLocation(p.id, "uBleed")
        uAdvection = GLES30.glGetUniformLocation(p.id, "uAdvection")
        uCoagulation = GLES30.glGetUniformLocation(p.id, "uCoagulation")
        uEvaporation = GLES30.glGetUniformLocation(p.id, "uEvaporation")
        uEdgeDarkening = GLES30.glGetUniformLocation(p.id, "uEdgeDarkening")
        uDeltaTime = GLES30.glGetUniformLocation(p.id, "uDeltaTime")
        uFinalize = GLES30.glGetUniformLocation(p.id, "uFinalize")

        GLES30.glUniform1i(GLES30.glGetUniformLocation(p.id, "uPigmentTex"), 0)

        val vertices = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        quadBufferId = vbos[0]

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBufferId)

        val nioBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        nioBuffer.put(vertices).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, nioBuffer, GLES30.GL_STATIC_DRAW)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Advances the fluid buffer one step and unpacks [destination].
     *
     * @param deltaSeconds real seconds since the previous step (clamped);
     *   drives diffusion and evaporation so frame rate does not change the wash.
     * @param motionUvPerSecondX/Y the live brush-tip velocity in document UV/s;
     *   the brush pushes existing wet paint, scaled by [WetSettings.advection].
     * @param finalize when true, emits pigment coverage (alpha = max(rgb)) and
     *   ignores water; used once at commit to merge into the layer.
     */
    fun step(
        source: RenderTarget,
        destination: RenderTarget,
        wet: WetSettings,
        deltaSeconds: Float = DEFAULT_DELTA_SECONDS,
        motionUvPerSecondX: Float = 0f,
        motionUvPerSecondY: Float = 0f,
        finalize: Boolean = false,
    ) {
        val p = program ?: return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, destination.framebufferId)
        GLES30.glViewport(0, 0, destination.width, destination.height)

        // This shader fully overwrites destination (fluid step / finalize).
        GLES30.glDisable(GLES30.GL_BLEND)

        p.use()

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source.textureId)

        val dt = deltaSeconds.coerceIn(MIN_DELTA_SECONDS, MAX_DELTA_SECONDS)
        GLES30.glUniform2f(uPixelSize, 1f / destination.width.toFloat(), 1f / destination.height.toFloat())
        GLES30.glUniform2f(
            uMotion,
            motionUvPerSecondX.coerceIn(-MAX_MOTION_UV_PER_SECOND, MAX_MOTION_UV_PER_SECOND),
            motionUvPerSecondY.coerceIn(-MAX_MOTION_UV_PER_SECOND, MAX_MOTION_UV_PER_SECOND),
        )
        GLES30.glUniform1f(uDeltaTime, dt)
        GLES30.glUniform1f(uSpread, wet.spread)
        GLES30.glUniform1f(uWetness, wet.wetness)
        GLES30.glUniform1f(uBleed, wet.bleed)
        GLES30.glUniform1f(uAdvection, wet.advection)
        GLES30.glUniform1f(uCoagulation, wet.coagulation)
        GLES30.glUniform1f(uEvaporation, wet.evaporation)
        GLES30.glUniform1f(uEdgeDarkening, wet.edgeDarkening)
        GLES30.glUniform1i(uFinalize, if (finalize) 1 else 0)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        GlCheck.noError("WetSimulationRenderer.step")
    }

    fun release() {
        program?.release()
        program = null
        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (quadBufferId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(quadBufferId), 0)
            quadBufferId = 0
        }
    }

    companion object {
        const val DEFAULT_DELTA_SECONDS = 1f / 60f
        private const val MIN_DELTA_SECONDS = 1f / 240f
        private const val MAX_DELTA_SECONDS = 1f / 10f
        /** Cap the advection offset so a huge per-frame velocity can never teleport fluid. */
        private const val MAX_MOTION_UV_PER_SECOND = 8f
    }
}