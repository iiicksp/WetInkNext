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
    private var uGrainCanvasLocked = -1
    private var uTextureDepth = -1
    private var uTextureContrast = -1
    private var uStrokeOpacity = -1
    private var uShapeTex = -1
    private var uShapeActive = -1
    private var uReverseShape = -1
    private var uRgbToAlpha = -1

    private var grainTextureId = 0
    private var grainScale = 1f
    private var grainCanvasLocked = true
    private var textureDepth = 1f
    private var textureContrast = 1f
    private var shapeTextureId = 0
    private var reverseShape = false
    private var rgbToAlpha = false

    private var uploadedDabCount = 0
    private val blendController = BlendController()

    /** Number of dabs already written into the current stroke target. */
    val uploadedCount: Int get() = uploadedDabCount

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
        uGrainCanvasLocked = GLES30.glGetUniformLocation(currentProgram.id, "uGrainCanvasLocked")
        uTextureDepth = GLES30.glGetUniformLocation(currentProgram.id, "uTextureDepth")
        uTextureContrast = GLES30.glGetUniformLocation(currentProgram.id, "uTextureContrast")
        uStrokeOpacity = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeOpacity")
        uShapeTex = GLES30.glGetUniformLocation(currentProgram.id, "uShapeTex")
        uShapeActive = GLES30.glGetUniformLocation(currentProgram.id, "uShapeActive")
        uReverseShape = GLES30.glGetUniformLocation(currentProgram.id, "uReverseShape")
        uRgbToAlpha = GLES30.glGetUniformLocation(currentProgram.id, "uRgbToAlpha")

        check(uCanvasToClip >= 0 && uColorLinear >= 0 && uStrokeOpacity >= 0) { "Dab shader uniforms missing" }

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
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES, 16)
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
        strokeOpacity: Float = 1f,
    ) {
        if (dabs.count == 0) return
        require(colorLinear.size >= 3)

        dabs.prepareForUpload(firstDab = 0, dabCount = dabs.count)
        try {
            drawRange(
                target = target,
                width = width,
                height = height,
                canvasToFbo = canvasToFbo,
                dabs = dabs,
                firstDab = 0,
                count = dabs.count,
                colorLinear = colorLinear,
                blendPolicy = blendPolicy,
                strokeOpacity = strokeOpacity,
            )
        } finally {
            dabs.finishUpload()
        }
    }

    fun release() {
        GlCheck.checkOnGlThread()
        program?.release()
        program = null
        uploadedDabCount = 0
        grainTextureId = 0
        shapeTextureId = 0
        reverseShape = false
        rgbToAlpha = false
        if (instanceBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(instanceBufferId), 0)
        if (quadBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadBufferId), 0)
        if (vaoId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
        vaoId = 0
        quadBufferId = 0
        instanceBufferId = 0
        uCanvasToClip = -1
        uColorLinear = -1
        uStrokeOpacity = -1
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
        canvasLocked: Boolean,
        depth: Float,
        contrast: Float,
    ) {
        grainTextureId = textureId
        grainScale = scale.coerceAtLeast(0.0001f)
        grainCanvasLocked = canvasLocked
        textureDepth = depth.coerceIn(0f, 1f)
        textureContrast = contrast.coerceIn(0f, 2f)
    }

    fun clearGrainTexture() {
        grainTextureId = 0
        grainScale = 1f
        grainCanvasLocked = true
        textureDepth = 1f
        textureContrast = 1f
    }

    /** Sets a tip mask sampled once per dab in local dab coordinates. */
    fun setShapeTexture(textureId: Int, reverse: Boolean, rgbToAlpha: Boolean) {
        shapeTextureId = textureId
        reverseShape = reverse
        this.rgbToAlpha = rgbToAlpha
    }

    fun clearShapeTexture() {
        shapeTextureId = 0
        reverseShape = false
        rgbToAlpha = false
    }

    fun drawPendingInto(
        target: RenderTarget,
        width: Int,
        height: Int,
        canvasToFbo: FloatArray,
        dabs: DabBuffer,
        colorLinear: FloatArray,
        blendPolicy: BlendPolicy,
        strokeOpacity: Float = 1f,
    ) {
        val first = uploadedDabCount
        val count = dabs.count - first
        if (count <= 0) return

        dabs.prepareForUpload(first, count)
        try {
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
                strokeOpacity = strokeOpacity,
            )
            uploadedDabCount = dabs.count
        } finally {
            dabs.finishUpload()
        }
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
        strokeOpacity: Float,
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
        GLES30.glUniform1i(uGrainCanvasLocked, if (grainCanvasLocked) 1 else 0)
        GLES30.glUniform1f(uTextureDepth, textureDepth)
        GLES30.glUniform1f(uTextureContrast, textureContrast)
        GLES30.glUniform1f(uStrokeOpacity, strokeOpacity.coerceIn(0f, 1f))
        GLES30.glUniform1i(uShapeActive, if (shapeTextureId != 0) 1 else 0)
        GLES30.glUniform1i(uReverseShape, if (reverseShape) 1 else 0)
        GLES30.glUniform1i(uRgbToAlpha, if (rgbToAlpha) 1 else 0)

        if (grainTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grainTextureId)
            GLES30.glUniform1i(uGrainTex, 2)
        }
        if (shapeTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shapeTextureId)
            GLES30.glUniform1i(uShapeTex, 3)
        }

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBufferId)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            firstDab * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES,
            count * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES,
            dabs.floats,
        )

        // Reset offsets for instancing
        GLES30.glVertexAttribPointer(
            1, 4, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES, 0
        )
        GLES30.glVertexAttribPointer(
            2, 2, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES, 16
        )

        // Manual attribute offset for ES 3.0 (since BaseInstance is 3.1+)
        GLES30.glVertexAttribPointer(
            1, 4, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES,
            (firstDab * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES)
        )
        GLES30.glVertexAttribPointer(
            2, 2, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES,
            (firstDab * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES + 16)
        )

        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        
        if (grainTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        if (shapeTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
        blendController.end()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
}
