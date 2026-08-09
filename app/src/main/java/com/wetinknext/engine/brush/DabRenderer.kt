package com.wetinknext.engine.brush

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Instanced circular dab renderer. All colour values are premultiplied linear output. */
class DabRenderer(private val maxDabs: Int) {
    private var program: GlProgram? = null
    private var vaoId = 0
    private var quadBufferId = 0
    private var instanceBufferId = 0
    private var uCanvasToClip = -1
    private var uColorLinear = -1
    private var uCanvasSize = -1
    private var uGrainTex = -1
    private var uGrainActive = -1
    private var uGrainScale = -1
    private var uTextureDepth = -1
    private var uTextureContrast = -1

    private var grainTextureId = 0
    private var grainScale = 1f
    private var textureDepth = 1f
    private var textureContrast = 1f

    private var uploadedDabCount = 0
    private val blendController = BlendController()

    fun create() {
        GlCheck.checkOnGlThread()
        release()
        uploadedDabCount = 0
        program = GlProgram(ShaderLib.dabVertex, ShaderLib.dabFragment)
        val currentProgram = checkNotNull(program)
        currentProgram.use()
        uCanvasToClip = GLES30.glGetUniformLocation(currentProgram.id, "uCanvasToClip")
        uColorLinear = GLES30.glGetUniformLocation(currentProgram.id, "uColorLinear")
        uCanvasSize = GLES30.glGetUniformLocation(currentProgram.id, "uCanvasSize")
        uGrainTex = GLES30.glGetUniformLocation(currentProgram.id, "uGrainTex")
        uGrainActive = GLES30.glGetUniformLocation(currentProgram.id, "uGrainActive")
        uGrainScale = GLES30.glGetUniformLocation(currentProgram.id, "uGrainScale")
        uTextureDepth = GLES30.glGetUniformLocation(currentProgram.id, "uTextureDepth")
        uTextureContrast = GLES30.glGetUniformLocation(currentProgram.id, "uTextureContrast")

        check(uCanvasToClip >= 0 && uColorLinear >= 0) { "Dab shader uniforms missing" }

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val quadData = ByteBuffer.allocateDirect(quad.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        quadData.put(quad).position(0)

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vaoId = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        quadBufferId = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        instanceBufferId = ids[0]

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBufferId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            quad.size * Float.SIZE_BYTES,
            quadData,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBufferId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            maxDabs * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, 20, 16)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
    }

    /** Draws all buffered dabs into a document-space RenderTarget. */
    fun drawInto(
        target: RenderTarget,
        width: Int,
        height: Int,
        canvasToFbo: FloatArray,
        dabs: DabBuffer,
        colorLinear: FloatArray,
        blendPolicy: BlendPolicy,
    ) {
        if (dabs.count == 0) return
        val currentProgram = program ?: return
        require(colorLinear.size >= 3)

        target.bind()
        GLES30.glViewport(0, 0, width, height)
        blendController.begin(blendPolicy)

        currentProgram.use()
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, canvasToFbo, 0)
        GLES30.glUniform3f(uColorLinear, colorLinear[0], colorLinear[1], colorLinear[2])
        
        GLES30.glUniform2f(uCanvasSize, width.toFloat(), height.toFloat())
        GLES30.glUniform1i(uGrainActive, if (grainTextureId != 0) 1 else 0)
        GLES30.glUniform1f(uGrainScale, grainScale)
        GLES30.glUniform1f(uTextureDepth, textureDepth)
        GLES30.glUniform1f(uTextureContrast, textureContrast)

        if (grainTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grainTextureId)
            GLES30.glUniform1i(uGrainTex, 2)
        }

        dabs.prepareForUpload()
        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBufferId)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            dabs.count * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES,
            dabs.floats,
        )

        GLES30.glVertexAttribPointer(
            1, 4, GLES30.GL_FLOAT, false, 20, 0
        )
        GLES30.glVertexAttribPointer(
            2, 1, GLES30.GL_FLOAT, false, 20, 16
        )

        dabs.finishUpload()
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, dabs.count)
        
        if (grainTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
        blendController.end()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun release() {
        GlCheck.checkOnGlThread()
        program?.release()
        program = null
        uploadedDabCount = 0
        grainTextureId = 0
        if (instanceBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(instanceBufferId), 0)
        if (quadBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadBufferId), 0)
        if (vaoId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
        vaoId = 0
        quadBufferId = 0
        instanceBufferId = 0
        uCanvasToClip = -1
        uColorLinear = -1
    }

    fun beginStroke() {
        uploadedDabCount = 0
    }

    fun clearStrokeData() {
        uploadedDabCount = 0
    }

    fun setGrainTexture(
        textureId: Int,
        scale: Float,
        depth: Float,
        contrast: Float,
    ) {
        grainTextureId = textureId
        grainScale = scale.coerceAtLeast(0.0001f)
        textureDepth = depth.coerceIn(0f, 1f)
        textureContrast = contrast.coerceIn(0f, 2f)
    }

    fun clearGrainTexture() {
        grainTextureId = 0
        grainScale = 1f
        textureDepth = 1f
        textureContrast = 1f
    }

    fun drawPendingInto(
        target: RenderTarget,
        width: Int,
        height: Int,
        canvasToFbo: FloatArray,
        dabs: DabBuffer,
        colorLinear: FloatArray,
        blendPolicy: BlendPolicy,
    ) {
        val first = uploadedDabCount
        val count = dabs.count - first
        if (count <= 0) return

        dabs.prepareForUpload(first, count)
        drawRange(
            target = target,
            width = width,
            height = height,
            canvasToFbo = canvasToFbo,
            dabs = dabs,
            firstDab = first,
            count = count,
            colorLinear = colorLinear,
            blendPolicy = blendPolicy,
        )
        uploadedDabCount = dabs.count
        dabs.finishUpload()
    }

    private fun drawRange(
        target: RenderTarget,
        width: Int,
        height: Int,
        canvasToFbo: FloatArray,
        dabs: DabBuffer,
        firstDab: Int,
        count: Int,
        colorLinear: FloatArray,
        blendPolicy: BlendPolicy,
    ) {
        val currentProgram = program ?: return
        target.bind()
        GLES30.glViewport(0, 0, width, height)
        blendController.begin(blendPolicy)
        currentProgram.use()
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, canvasToFbo, 0)
        GLES30.glUniform3f(uColorLinear, colorLinear[0], colorLinear[1], colorLinear[2])

        GLES30.glUniform2f(uCanvasSize, width.toFloat(), height.toFloat())
        GLES30.glUniform1i(uGrainActive, if (grainTextureId != 0) 1 else 0)
        GLES30.glUniform1f(uGrainScale, grainScale)
        GLES30.glUniform1f(uTextureDepth, textureDepth)
        GLES30.glUniform1f(uTextureContrast, textureContrast)

        if (grainTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grainTextureId)
            GLES30.glUniform1i(uGrainTex, 2)
        }

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBufferId)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            firstDab * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES,
            count * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES,
            dabs.floats,
        )

        // Manual attribute offset for ES 3.0 (since BaseInstance is 3.1+)
        GLES30.glVertexAttribPointer(
            1, 4, GLES30.GL_FLOAT, false, 20,
            (firstDab * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES)
        )
        GLES30.glVertexAttribPointer(
            2, 1, GLES30.GL_FLOAT, false, 20,
            (firstDab * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES + 16)
        )

        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        
        if (grainTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
        blendController.end()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
}
