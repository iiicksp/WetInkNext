package com.wetinknext.engine.brush

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib
import com.wetinknext.engine.gl.GlCheck

class WetSimulationRenderer {
    private var program: GlProgram? = null

    private var uPixelSize = -1
    private var uMotion = -1
    private var uSpread = -1
    private var uWetness = -1
    private var uDeltaTime = -1

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
        uDeltaTime = GLES30.glGetUniformLocation(p.id, "uDeltaTime")

        GLES30.glUniform1i(GLES30.glGetUniformLocation(p.id, "uPigmentTex"), 0)

        val vertices = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
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

    fun step(source: RenderTarget, destination: RenderTarget, settings: WetSettings) {
        val p = program ?: return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, destination.framebufferId)
        GLES30.glViewport(0, 0, destination.width, destination.height)
        
        // We only do normal blend / no blend since we fully overwrite destination
        GLES30.glDisable(GLES30.GL_BLEND)

        p.use()

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source.textureId)

        GLES30.glUniform2f(uPixelSize, 1f / destination.width.toFloat(), 1f / destination.height.toFloat())
        // Motion and DeltaTime would be passed properly if we had access to dab velocities, 
        // but for now we set them to 0 or derive if needed. The user snippet implies uMotion/uDeltaTime are present.
        GLES30.glUniform2f(uMotion, 0f, 0f)
        GLES30.glUniform1f(uDeltaTime, 0.016f)

        GLES30.glUniform1f(uSpread, settings.spread)
        GLES30.glUniform1f(uWetness, settings.wetness)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
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
}
