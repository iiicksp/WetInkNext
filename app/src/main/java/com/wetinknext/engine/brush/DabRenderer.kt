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
    private var uGrainZoomScale = -1
    private var uGrainCanvasLocked = -1
    private var uTextureDepth = -1
    private var uTextureContrast = -1
    private var uStrokeOpacity = -1
    private var uCoverageOnly = -1
    private var uShapeTex = -1
    private var uShapeActive = -1
    private var uReverseShape = -1
    private var uRgbToAlpha = -1
    private var uFalloffType = -1
    private var uGrainScreenSpace = -1
    private var uScreenSize = -1
    private var uSecondaryShapeActive = -1
    private var uSecondaryShapeTex = -1
    private var uSecondaryShapeScale = -1

    private var uSmudgeTex = -1
    private var uSmudgeStrength = -1
    private var uSmudgeLength = -1
    private var uEdgeDarkening = -1
    private var uSquareStroke = -1
    private var uNoAntialias = -1
    private var uIsWetMode = -1
    private var uWetness = -1

    private var grainTextureId = 0
    private var grainScale = 1f
    private var grainZoomScale = 1f
    private var grainCanvasLocked = true
    private var textureDepth = 1f
    private var textureContrast = 1f
    private var shapeTextureId = 0
    private var reverseShape = false
    private var rgbToAlpha = false
    private var falloffType = 1 // DabFalloff.SOFT ordinal
    var squareStroke = false
    var noAntialias = false
    private var grainScreenSpace = false
    private var screenWidth = 1080f
    private var screenHeight = 1920f
    private var secondaryShapeTextureId = 0
    private var secondaryShapeScale = 1f

    private var smudgeTextureId = 0
    private var smudgeStrength = 0f
    private var smudgeLength = 0f
    var edgeDarkening = 0f

    /** When true, dabs write the WET fluid buffer (RGB pigment, A = water). */
    private var isWetMode = false
    /** How wet a WET deposit is (0..1). Controls the fluid buffer's water channel. */
    var wetness = 0f

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
        uGrainZoomScale = GLES30.glGetUniformLocation(currentProgram.id, "uGrainZoomScale")
        uGrainCanvasLocked = GLES30.glGetUniformLocation(currentProgram.id, "uGrainCanvasLocked")
        uTextureDepth = GLES30.glGetUniformLocation(currentProgram.id, "uTextureDepth")
        uTextureContrast = GLES30.glGetUniformLocation(currentProgram.id, "uTextureContrast")
        uStrokeOpacity = GLES30.glGetUniformLocation(currentProgram.id, "uStrokeOpacity")
        uCoverageOnly = GLES30.glGetUniformLocation(currentProgram.id, "uCoverageOnly")
        uShapeTex = GLES30.glGetUniformLocation(currentProgram.id, "uShapeTex")
        uShapeActive = GLES30.glGetUniformLocation(currentProgram.id, "uShapeActive")
        uReverseShape = GLES30.glGetUniformLocation(currentProgram.id, "uReverseShape")
        uRgbToAlpha = GLES30.glGetUniformLocation(currentProgram.id, "uRgbToAlpha")
        uFalloffType = GLES30.glGetUniformLocation(currentProgram.id, "uFalloffType")
        uGrainScreenSpace = GLES30.glGetUniformLocation(currentProgram.id, "uGrainScreenSpace")
        uScreenSize = GLES30.glGetUniformLocation(currentProgram.id, "uScreenSize")
        uSecondaryShapeActive = GLES30.glGetUniformLocation(currentProgram.id, "uSecondaryShapeActive")
        uSecondaryShapeTex = GLES30.glGetUniformLocation(currentProgram.id, "uSecondaryShapeTex")
        uSecondaryShapeScale = GLES30.glGetUniformLocation(currentProgram.id, "uSecondaryShapeScale")
        uSmudgeTex = GLES30.glGetUniformLocation(currentProgram.id, "uSmudgeTex")
        uSmudgeStrength = GLES30.glGetUniformLocation(currentProgram.id, "uSmudgeStrength")
        uSmudgeLength = GLES30.glGetUniformLocation(currentProgram.id, "uSmudgeLength")
        uEdgeDarkening = GLES30.glGetUniformLocation(currentProgram.id, "uEdgeDarkening")
        uSquareStroke = GLES30.glGetUniformLocation(currentProgram.id, "uSquareStroke")
        uNoAntialias = GLES30.glGetUniformLocation(currentProgram.id, "uNoAntialias")
        uIsWetMode = GLES30.glGetUniformLocation(currentProgram.id, "uIsWetMode")
        uWetness = GLES30.glGetUniformLocation(currentProgram.id, "uWetness")

        check(
            uCanvasToClip >= 0 && uColorLinear >= 0 && uStrokeOpacity >= 0 &&
                uCoverageOnly >= 0,
        ) { "Dab shader uniforms missing" }

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
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES, 16)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES, 28)
        GLES30.glVertexAttribDivisor(3, 1)
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
                documentWidth = width,
                documentHeight = height,
                canvasToFbo = canvasToFbo,
                dabs = dabs,
                firstDab = 0,
                count = dabs.count,
                colorLinear = colorLinear,
                blendPolicy = blendPolicy,
                strokeOpacity = strokeOpacity,
                coverageOnly = false,
                isWetMode = isWetMode,
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
        isWetMode = false
        wetness = 0f
        grainTextureId = 0
        shapeTextureId = 0
        reverseShape = false
        rgbToAlpha = false
        falloffType = 1
        if (instanceBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(instanceBufferId), 0)
        if (quadBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadBufferId), 0)
        if (vaoId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
        vaoId = 0
        quadBufferId = 0
        instanceBufferId = 0
        uCanvasToClip = -1
        uColorLinear = -1
        uStrokeOpacity = -1
        uCoverageOnly = -1
    }

    fun beginStroke() {
        uploadedDabCount = 0
    }

    fun setWetMode(enabled: Boolean) {
        isWetMode = enabled
    }

    fun setWetness(value: Float) {
        wetness = value.coerceIn(0f, 1f)
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

    fun setScreenDimensions(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }

    fun setGrainScreenSpace(screenSpace: Boolean) {
        grainScreenSpace = screenSpace
    }

    fun setSecondaryShape(textureId: Int, scale: Float) {
        secondaryShapeTextureId = textureId
        secondaryShapeScale = scale.coerceAtLeast(0.0001f)
    }

    fun clearSecondaryShape() {
        secondaryShapeTextureId = 0
        secondaryShapeScale = 1f
    }

    fun setSmudge(textureId: Int, strength: Float, length: Float) {
        smudgeTextureId = textureId
        smudgeStrength = strength.coerceIn(0f, 1f)
        smudgeLength = length
    }

    fun clearSmudge() {
        smudgeTextureId = 0
        smudgeStrength = 0f
        smudgeLength = 0f
        edgeDarkening = 0f
    }

    fun clearGrainTexture() {
        grainTextureId = 0
        grainScale = 1f
        grainZoomScale = 1f
        grainCanvasLocked = true
        textureDepth = 1f
        textureContrast = 1f
    }
    
    fun setGrainZoomScale(scale: Float) {
        grainZoomScale = scale.coerceAtLeast(0.0001f)
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

    /** Sets the radial intensity profile for all subsequent dab draws. */
    fun setFalloff(falloff: DabFalloff) {
        falloffType = falloff.ordinal
    }

    /**
     * Draws all dabs into a coverage target. The fragment coverage-only path
     * is wired in the next NON_BUILDUP step; keeping upload ownership here
     * prevents the mask pass from disturbing incremental colour preview data.
     */
    fun drawCoverageInto(
        target: RenderTarget,
        width: Int,
        height: Int,
        canvasToFbo: FloatArray,
        dabs: DabBuffer,
    ) {
        if (dabs.count == 0) return

        dabs.prepareForUpload(firstDab = 0, dabCount = dabs.count)
        try {
            drawRange(
                target = target,
                width = width,
                height = height,
                documentWidth = width,
                documentHeight = height,
                canvasToFbo = canvasToFbo,
                dabs = dabs,
                firstDab = 0,
                count = dabs.count,
                colorLinear = ZERO_COLOR,
                blendPolicy = BlendPolicy.NON_BUILDUP,
                strokeOpacity = 1f,
                coverageOnly = true,
                isWetMode = false,
            )
        } finally {
            dabs.finishUpload()
        }
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
                documentWidth = width,
                documentHeight = height,
                canvasToFbo = canvasToFbo,
                dabs = dabs,
                firstDab = first,
                count = count,
                colorLinear = colorLinear,
                blendPolicy = blendPolicy,
                strokeOpacity = strokeOpacity,
                coverageOnly = false,
                isWetMode = isWetMode,
            )
            uploadedDabCount = dabs.count
        } finally {
            dabs.finishUpload()
        }
    }

    /**
     * Incremental preview into a screen-sized target. Coordinates and texture
     * grain remain document-space; only rasterisation uses screen dimensions.
     */
    fun drawPendingPreviewInto(
        target: RenderTarget,
        previewWidth: Int,
        previewHeight: Int,
        documentWidth: Int,
        documentHeight: Int,
        canvasToClip: FloatArray,
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
                width = previewWidth,
                height = previewHeight,
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                canvasToFbo = canvasToClip,
                dabs = dabs,
                firstDab = first,
                count = count,
                colorLinear = colorLinear,
                blendPolicy = blendPolicy,
                strokeOpacity = strokeOpacity,
                coverageOnly = false,
                isWetMode = false,
            )
            uploadedDabCount = dabs.count
        } finally {
            dabs.finishUpload()
        }
    }

    /** Adds only new dabs to a screen-sized NON_BUILDUP union coverage mask. */
    fun drawPendingCoveragePreviewInto(
        target: RenderTarget,
        previewWidth: Int,
        previewHeight: Int,
        documentWidth: Int,
        documentHeight: Int,
        canvasToClip: FloatArray,
        dabs: DabBuffer,
    ) {
        val first = uploadedDabCount
        val count = dabs.count - first
        if (count <= 0) return

        dabs.prepareForUpload(first, count)
        try {
            drawRange(
                target = target,
                width = previewWidth,
                height = previewHeight,
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                canvasToFbo = canvasToClip,
                dabs = dabs,
                firstDab = first,
                count = count,
                colorLinear = ZERO_COLOR,
                blendPolicy = BlendPolicy.NON_BUILDUP,
                strokeOpacity = 1f,
                coverageOnly = true,
                isWetMode = false,
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
        documentWidth: Int,
        documentHeight: Int,
        canvasToFbo: FloatArray,
        dabs: DabBuffer,
        firstDab: Int,
        count: Int,
        colorLinear: FloatArray,
        blendPolicy: BlendPolicy,
        strokeOpacity: Float,
        coverageOnly: Boolean,
        isWetMode: Boolean,
    ) {
        val currentProgram = program ?: return
        target.bind()
        GLES30.glViewport(0, 0, width, height)
        if (coverageOnly) {
            // A coverage mask is a union of dabs: overlaps retain their maximum
            // alpha instead of source-over accumulating darkness.
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendEquation(GLES30.GL_MAX)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        } else {
            blendController.begin(blendPolicy)
        }
        currentProgram.use()
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, canvasToFbo, 0)
        GLES30.glUniform3f(uColorLinear, colorLinear[0], colorLinear[1], colorLinear[2])

        // The GLSL coverage output is added in the following NON_BUILDUP pass.
        // Keep the explicit mode here so every draw call already has a stable API.
        @Suppress("UNUSED_VARIABLE")
        val coveragePass = coverageOnly

        GLES30.glUniform2f(
            uCanvasSize,
            documentWidth.toFloat(),
            documentHeight.toFloat(),
        )
        GLES30.glUniform1i(uGrainActive, if (grainTextureId != 0) 1 else 0)
        GLES30.glUniform1f(uGrainScale, grainScale)
        GLES30.glUniform1f(uGrainZoomScale, grainZoomScale)
        GLES30.glUniform1i(uGrainCanvasLocked, if (grainCanvasLocked) 1 else 0)
        GLES30.glUniform1f(uTextureDepth, textureDepth)
        GLES30.glUniform1f(uTextureContrast, textureContrast)
        GLES30.glUniform1f(uStrokeOpacity, strokeOpacity.coerceIn(0f, 1f))
        GLES30.glUniform1i(uCoverageOnly, if (coverageOnly) 1 else 0)
        GLES30.glUniform1i(uShapeActive, if (shapeTextureId != 0) 1 else 0)
        GLES30.glUniform1i(uReverseShape, if (reverseShape) 1 else 0)
        GLES30.glUniform1i(uRgbToAlpha, if (rgbToAlpha) 1 else 0)
        GLES30.glUniform1i(uFalloffType, falloffType)
        GLES30.glUniform1i(uGrainScreenSpace, if (grainScreenSpace) 1 else 0)
        GLES30.glUniform2f(uScreenSize, screenWidth, screenHeight)
        GLES30.glUniform1i(uSecondaryShapeActive, if (secondaryShapeTextureId != 0) 1 else 0)
        GLES30.glUniform1f(uSecondaryShapeScale, secondaryShapeScale)

        GLES30.glUniform1f(uSmudgeStrength, smudgeStrength)
        GLES30.glUniform1f(uSmudgeLength, smudgeLength)
        if (uEdgeDarkening >= 0) GLES30.glUniform1f(uEdgeDarkening, edgeDarkening)
        if (uSquareStroke >= 0) GLES30.glUniform1i(uSquareStroke, if (squareStroke) 1 else 0)
        if (uNoAntialias >= 0) GLES30.glUniform1i(uNoAntialias, if (noAntialias) 1 else 0)
        if (uIsWetMode >= 0) GLES30.glUniform1i(uIsWetMode, if (isWetMode) 1 else 0)
        if (uWetness >= 0) GLES30.glUniform1f(uWetness, wetness.coerceIn(0f, 1f))

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
        if (secondaryShapeTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, secondaryShapeTextureId)
            GLES30.glUniform1i(uSecondaryShapeTex, 4)
        }
        if (smudgeTextureId != 0 && smudgeStrength > 0f) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, smudgeTextureId)
            GLES30.glUniform1i(uSmudgeTex, 5)
        }

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBufferId)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            count * DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES,
            dabs.floats,
        )

        // DabBuffer has already positioned the source range. Upload it at the
        // VBO origin, otherwise firstDab is applied twice during a preview.
        GLES30.glVertexAttribPointer(
            1, 4, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES, 0
        )
        GLES30.glVertexAttribPointer(
            2, 3, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES, 16
        )
        GLES30.glVertexAttribPointer(
            3, 2, GLES30.GL_FLOAT, false, DabBuffer.FLOATS_PER_DAB * Float.SIZE_BYTES, 28
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
        if (secondaryShapeTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        if (smudgeTextureId != 0 && smudgeStrength > 0f) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
        if (coverageOnly) {
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDisable(GLES30.GL_BLEND)
        } else {
            blendController.end()
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private companion object {
        val ZERO_COLOR = floatArrayOf(0f, 0f, 0f)
    }
}
