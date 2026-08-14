package com.wetinknext.engine.brush

import android.opengl.GLES30
import com.wetinknext.BuildConfig
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GPU-renderer для круглого штриха.
 *
 * Один instance:
 *   x0, y0, radius0,
 *   x1, y1, radius1
 *
 * Геометрия каждого сегмента является AABB, а настоящая форма
 * рассчитывается в capsuleFragment через signed distance.
 *
 * Важно:
 * - target должен быть очищен перед началом нового штриха;
 * - рисование использует premultiplied source-over blending;
 * - цвет должен быть постоянным для всего strokeTarget;
 * - выход shader: premultiplied linear RGBA.
 */
class CapsuleStrokeRenderer(
    private val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
) {
    private var program: GlProgram? = null

    private var vaoId = 0
    private var cornerVboId = 0
    private var instanceVboId = 0

    private var uCanvasToClip = -1
    private var uColorLinear = -1
    private val blendController = BlendController()
    private var uCanvasSize = -1
    private var uGrainTex = -1
    private var uGrainActive = -1
    private var uGrainScale = -1
    private var uGrainZoomScale = -1
    private var uGrainCanvasLocked = -1
    private var uTextureDepth = -1
    private var uTextureContrast = -1
    private var uFlow = -1
    private var uCoverageOnly = -1
    private var uEdgeDarkening = -1

    private var grainTextureId = 0
    private var grainScale = 1f
    private var grainZoomScale = 1f
    private var grainCanvasLocked = true
    private var textureDepth = 1f
    private var textureContrast = 1f
    var edgeDarkening = 0f
    // Reserved for a future oriented marker/chisel implementation.
    private var strokeRotation = 0f

    private val checkGlErrors: Boolean
        get() = BuildConfig.DEBUG

    /**
     * Формат:
     * x0, y0, r0, x1, y1, r1
     */
    private val instanceData =
        ByteBuffer
            .allocateDirect(
                maxSegments * FLOATS_PER_SEGMENT * Float.SIZE_BYTES,
            )
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    var segmentCount: Int = 0
        private set

    private var uploadedSegmentCount = 0

    /**
     * Не сбрасывается на каждый новый stroke.
     * Иначе диагностика потерь снова будет скрыта.
     */
    var overflowCount: Long = 0L
        private set

    val isEmpty: Boolean
        get() = segmentCount == 0

    val hasPendingSegments: Boolean
        get() = segmentCount > uploadedSegmentCount

    fun create() {
        GlCheck.checkOnGlThread()
        release()

        program = GlProgram(
            vertexSource = ShaderLib.capsuleVertex,
            fragmentSource = ShaderLib.capsuleFragment,
        )

        val currentProgram = checkNotNull(program)
        currentProgram.use()

        uCanvasToClip = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uCanvasToClip",
        )
        uColorLinear = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uColorLinear",
        )
        uCanvasSize = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uCanvasSize",
        )
        uGrainTex = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uGrainTex",
        )
        uGrainScale = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uGrainScale",
        )
        uGrainZoomScale = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uGrainZoomScale",
        )
        uGrainActive = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uGrainActive",
        )
        uGrainCanvasLocked = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uGrainCanvasLocked",
        )
        uTextureDepth = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uTextureDepth",
        )
        uTextureContrast = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uTextureContrast",
        )
        uFlow = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uFlow",
        )
        uCoverageOnly = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uCoverageOnly",
        )
        uEdgeDarkening = GLES30.glGetUniformLocation(
            currentProgram.id,
            "uEdgeDarkening",
        )

        check(uCanvasToClip >= 0) {
            "Capsule shader uniform uCanvasToClip is missing"
        }
        check(uColorLinear >= 0) {
            "Capsule shader uniform uColorLinear is missing"
        }
        check(uCanvasSize >= 0) {
            "Capsule shader uniform uCanvasSize is missing"
        }
        check(uFlow >= 0) {
            "Capsule shader uniform uFlow is missing"
        }
        check(uCoverageOnly >= 0) {
            "Capsule shader uniform uCoverageOnly is missing"
        }

        val corners = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f,
        )

        val cornerData =
            ByteBuffer
                .allocateDirect(corners.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

        cornerData.put(corners)
        cornerData.position(0)

        val ids = IntArray(1)

        GLES30.glGenVertexArrays(1, ids, 0)
        vaoId = ids[0]

        GLES30.glGenBuffers(1, ids, 0)
        cornerVboId = ids[0]

        GLES30.glGenBuffers(1, ids, 0)
        instanceVboId = ids[0]

        check(vaoId != 0) { "Failed to create capsule VAO" }
        check(cornerVboId != 0) { "Failed to create capsule corner VBO" }
        check(instanceVboId != 0) { "Failed to create capsule instance VBO" }

        GLES30.glBindVertexArray(vaoId)

        // Углы AABB-квада.
        GLES30.glBindBuffer(
            GLES30.GL_ARRAY_BUFFER,
            cornerVboId,
        )
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            corners.size * Float.SIZE_BYTES,
            cornerData,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(ATTR_CORNER)
        GLES30.glVertexAttribPointer(
            ATTR_CORNER,
            2,
            GLES30.GL_FLOAT,
            false,
            2 * Float.SIZE_BYTES,
            0,
        )

        // Данные сегментов.
        GLES30.glBindBuffer(
            GLES30.GL_ARRAY_BUFFER,
            instanceVboId,
        )
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            maxSegments * STRIDE_BYTES,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )

        // A: x0, y0, radius0
        GLES30.glEnableVertexAttribArray(ATTR_SEGMENT_A)
        GLES30.glVertexAttribPointer(
            ATTR_SEGMENT_A,
            4,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            0,
        )
        GLES30.glVertexAttribDivisor(
            ATTR_SEGMENT_A,
            1,
        )

        // B: x1, y1, radius1
        GLES30.glEnableVertexAttribArray(ATTR_SEGMENT_B)
        GLES30.glVertexAttribPointer(
            ATTR_SEGMENT_B,
            4,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            4 * Float.SIZE_BYTES,
        )
        GLES30.glVertexAttribDivisor(
            ATTR_SEGMENT_B,
            1,
        )

        GLES30.glBindBuffer(
            GLES30.GL_ARRAY_BUFFER,
            0,
        )
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)

        checkNoGlError("CapsuleStrokeRenderer.create")
    }

    /**
     * Начинает новый stroke.
     *
     * Старый target должен быть очищен отдельно:
     * strokeTarget.clear(0f, 0f, 0f, 0f)
     */
    fun beginStroke() {
        segmentCount = 0
        uploadedSegmentCount = 0
        strokeRotation = 0f

        instanceData.clear()
        instanceData.limit(
            maxSegments * FLOATS_PER_SEGMENT,
        )
    }

    /**
     * Добавляет один сегмент в CPU-buffer.
     *
     * Если capacity исчерпана, сегмент не теряется молча:
     * overflowCount увеличивается.
     */
    fun addSegment(
        x0: Float,
        y0: Float,
        radius0: Float,
        coverage0: Float,
        x1: Float,
        y1: Float,
        radius1: Float,
        coverage1: Float,
    ): Boolean {
        if (segmentCount >= maxSegments) {
            overflowCount++
            return false
        }

        val offset = segmentCount * FLOATS_PER_SEGMENT

        instanceData.put(offset, x0)
        instanceData.put(offset + 1, y0)
        instanceData.put(offset + 2, radius0.coerceAtLeast(0.01f))
        instanceData.put(offset + 3, coverage0.coerceIn(0f, 1f))

        instanceData.put(offset + 4, x1)
        instanceData.put(offset + 5, y1)
        instanceData.put(offset + 6, radius1.coerceAtLeast(0.01f))
        instanceData.put(offset + 7, coverage1.coerceIn(0f, 1f))

        segmentCount++
        return true
    }

    /**
     * Retains pen orientation for a future non-round marker/chisel path.
     * Round capsule geometry deliberately does not use the value yet.
     */
    fun setStrokeRotation(rotationRad: Float) {
        strokeRotation = rotationRad
    }

    /**
     * Applies geometric taper to all accumulated segments after the stroke is complete.
     * The callback receives mesh arc-distance and the complete mesh length, so the end
     * taper never depends on an estimate made during MOVE.
     */
    fun applyTaper(scaleAt: (distanceFromStart: Float, totalLength: Float) -> Float) {
        if (segmentCount == 0) return

        var totalLength = 0f
        for (index in 0 until segmentCount) {
            val offset = index * FLOATS_PER_SEGMENT
            totalLength += kotlin.math.hypot(
                instanceData.get(offset + 4) - instanceData.get(offset),
                instanceData.get(offset + 5) - instanceData.get(offset + 1),
            )
        }

        var distanceFromStart = 0f
        for (index in 0 until segmentCount) {
            val offset = index * FLOATS_PER_SEGMENT
            val segmentLength = kotlin.math.hypot(
                instanceData.get(offset + 4) - instanceData.get(offset),
                instanceData.get(offset + 5) - instanceData.get(offset + 1),
            )

            val startScale = scaleAt(distanceFromStart, totalLength)
            val endScale = scaleAt(distanceFromStart + segmentLength, totalLength)
            instanceData.put(offset + 2, (instanceData.get(offset + 2) * startScale).coerceAtLeast(0.01f))
            instanceData.put(offset + 6, (instanceData.get(offset + 6) * endScale).coerceAtLeast(0.01f))
            distanceFromStart += segmentLength
        }
    }

    /**
     * Загружает и рисует только новые сегменты.
     *
     * Используется для preview во время MOVE.
     * Перед первым вызовом target должен быть очищен.
     */
    fun drawPending(
        target: RenderTarget,
        width: Int,
        height: Int,
        canvasToClip: FloatArray,
        colorLinear: FloatArray,
        blendPolicy: BlendPolicy,
        flow: Float,
    ) {
        val first = uploadedSegmentCount
        val count = segmentCount - uploadedSegmentCount

        if (count <= 0) return

        val drawn = drawRange(
            target = target,
            width = width,
            height = height,
            documentWidth = width,
            documentHeight = height,
            canvasToClip = canvasToClip,
            colorLinear = colorLinear,
            firstSegment = first,
            count = count,
            blendPolicy = blendPolicy,
            flow = flow,
            coverageOnly = false,
        )

        if (drawn) {
            uploadedSegmentCount = segmentCount
        }
    }

    /**
     * Полностью рисует все накопленные сегменты.
     *
     * Используется при commit, если target был очищен.
     */
    fun drawAll(
        target: RenderTarget,
        width: Int,
        height: Int,
        canvasToClip: FloatArray,
        colorLinear: FloatArray,
        blendPolicy: BlendPolicy,
        flow: Float,
    ) {
        if (segmentCount <= 0) return

        drawRange(
            target = target,
            width = width,
            height = height,
            documentWidth = width,
            documentHeight = height,
            canvasToClip = canvasToClip,
            colorLinear = colorLinear,
            firstSegment = 0,
            count = segmentCount,
            blendPolicy = blendPolicy,
            flow = flow,
            coverageOnly = false,
        )
    }

    /** Draws only new segments into an RGBA8 screen-sized preview target. */
    fun drawPendingPreview(
        target: RenderTarget,
        previewWidth: Int,
        previewHeight: Int,
        documentWidth: Int,
        documentHeight: Int,
        canvasToClip: FloatArray,
        colorLinear: FloatArray,
        blendPolicy: BlendPolicy,
        flow: Float,
    ) {
        val first = uploadedSegmentCount
        val count = segmentCount - uploadedSegmentCount
        if (count <= 0) return

        val drawn = drawRange(
            target = target,
            width = previewWidth,
            height = previewHeight,
            documentWidth = documentWidth,
            documentHeight = documentHeight,
            canvasToClip = canvasToClip,
            colorLinear = colorLinear,
            firstSegment = first,
            count = count,
            blendPolicy = blendPolicy,
            flow = flow,
            coverageOnly = false,
        )
        if (drawn) uploadedSegmentCount = segmentCount
    }

    /** Adds only new capsule segments to an RGBA8 GL_MAX coverage mask. */
    fun drawPendingCoveragePreview(
        target: RenderTarget,
        previewWidth: Int,
        previewHeight: Int,
        documentWidth: Int,
        documentHeight: Int,
        canvasToClip: FloatArray,
        flow: Float,
    ) {
        val first = uploadedSegmentCount
        val count = segmentCount - first
        if (count <= 0) return
        val drawn = drawRange(
            target = target,
            width = previewWidth,
            height = previewHeight,
            documentWidth = documentWidth,
            documentHeight = documentHeight,
            canvasToClip = canvasToClip,
            colorLinear = ZERO_COLOR,
            firstSegment = first,
            count = count,
            blendPolicy = BlendPolicy.NON_BUILDUP,
            flow = flow,
            coverageOnly = true,
        )
        if (drawn) uploadedSegmentCount = segmentCount
    }

    /** Rebuilds every segment into the document-sized union coverage mask. */
    fun drawAllCoverage(
        target: RenderTarget,
        width: Int,
        height: Int,
        canvasToClip: FloatArray,
        flow: Float,
    ) {
        if (segmentCount <= 0) return
        drawRange(
            target = target,
            width = width,
            height = height,
            documentWidth = width,
            documentHeight = height,
            canvasToClip = canvasToClip,
            colorLinear = ZERO_COLOR,
            firstSegment = 0,
            count = segmentCount,
            blendPolicy = BlendPolicy.NON_BUILDUP,
            flow = flow,
            coverageOnly = true,
        )
    }

    /**
     * Полностью перерисовывает сегменты в диапазоне [firstSegment, firstSegment + count).
     *
     * Важный момент: glVertexAttribPointer вызывается здесь повторно,
     * потому что offset у instanced-атрибутов меняется для каждого диапазона.
     */
    private fun drawRange(
        target: RenderTarget,
        width: Int,
        height: Int,
        documentWidth: Int,
        documentHeight: Int,
        canvasToClip: FloatArray,
        colorLinear: FloatArray,
        firstSegment: Int,
        count: Int,
        blendPolicy: BlendPolicy,
        flow: Float,
        coverageOnly: Boolean,
    ): Boolean {
        val currentProgram = program ?: return false

        require(canvasToClip.size >= 16) {
            "canvasToClip must contain at least 16 floats"
        }
        require(colorLinear.size >= 3) {
            "colorLinear must contain at least 3 floats"
        }
        require(firstSegment >= 0)
        require(count >= 0)
        require(firstSegment + count <= segmentCount)

        if (count == 0) return true

        target.bind()

        GLES30.glViewport(
            0,
            0,
            width,
            height,
        )

        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)

        if (coverageOnly) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendEquation(GLES30.GL_MAX)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        } else {
            blendController.begin(blendPolicy)
        }

        currentProgram.use()

        GLES30.glUniformMatrix4fv(
            uCanvasToClip,
            1,
            false,
            canvasToClip,
            0,
        )

        GLES30.glUniform3f(
            uColorLinear,
            colorLinear[0].coerceIn(0f, 1f),
            colorLinear[1].coerceIn(0f, 1f),
            colorLinear[2].coerceIn(0f, 1f),
        )

        GLES30.glUniform2f(
            uCanvasSize,
            documentWidth.toFloat(),
            documentHeight.toFloat(),
        )

        GLES30.glUniform1f(
            uGrainScale,
            grainScale,
        )
        GLES30.glUniform1f(
            uGrainZoomScale,
            grainZoomScale,
        )

        GLES30.glUniform1i(
            uGrainActive,
            if (grainTextureId != 0) 1 else 0,
        )

        GLES30.glUniform1i(
            uGrainCanvasLocked,
            if (grainCanvasLocked) 1 else 0,
        )

        GLES30.glUniform1f(
            uTextureDepth,
            textureDepth,
        )

        GLES30.glUniform1f(
            uTextureContrast,
            textureContrast,
        )

        GLES30.glUniform1f(
            uFlow,
            flow.coerceIn(0f, 1f),
        )
        GLES30.glUniform1i(uCoverageOnly, if (coverageOnly) 1 else 0)
        if (uEdgeDarkening >= 0) {
            GLES30.glUniform1f(uEdgeDarkening, edgeDarkening)
        }

        if (grainTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(
                GLES30.GL_TEXTURE_2D,
                grainTextureId,
            )
            GLES30.glUniform1i(uGrainTex, 2)
        }

        /*
         * FloatBuffer должен содержать только передаваемый диапазон.
         * glBufferSubData прочитает remaining() элементов.
         */
        val sourceStart = firstSegment * FLOATS_PER_SEGMENT
        val sourceEnd =
            sourceStart + count * FLOATS_PER_SEGMENT

        instanceData.position(sourceStart)
        instanceData.limit(sourceEnd)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(
            GLES30.GL_ARRAY_BUFFER,
            instanceVboId,
        )

        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            count * STRIDE_BYTES,
            instanceData,
        )

        /*
         * Для ES 3.0 нет glDrawArraysInstancedBaseInstance.
         * Поэтому offset атрибутов сдвигается вручную.
         *
         * VAO должен быть привязан во время glVertexAttribPointer.
         */
        GLES30.glVertexAttribPointer(
            ATTR_SEGMENT_A,
            4,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            0,
        )

        GLES30.glVertexAttribPointer(
            ATTR_SEGMENT_B,
            4,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            4 * Float.SIZE_BYTES,
        )

        GLES30.glDrawArraysInstanced(
            GLES30.GL_TRIANGLE_STRIP,
            0,
            CORNER_VERTEX_COUNT,
            count,
        )

        if (grainTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(
                GLES30.GL_TEXTURE_2D,
                0,
            )
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        GLES30.glBindBuffer(
            GLES30.GL_ARRAY_BUFFER,
            0,
        )
        GLES30.glBindVertexArray(0)

        if (coverageOnly) {
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDisable(GLES30.GL_BLEND)
        } else {
            blendController.end()
        }

        GLES30.glBindFramebuffer(
            GLES30.GL_FRAMEBUFFER,
            0,
        )

        instanceData.clear()
        instanceData.limit(
            maxSegments * FLOATS_PER_SEGMENT,
        )

        if (checkGlErrors) {
            checkNoGlError("CapsuleStrokeRenderer.drawRange")
        }
        return true
    }

    /**
     * Сбрасывает только GPU/CPU состояние текущего renderer.
     * Счётчик overflow сохраняется.
     */
    fun clearStrokeData() {
        segmentCount = 0
        uploadedSegmentCount = 0
        instanceData.clear()
        instanceData.limit(
            maxSegments * FLOATS_PER_SEGMENT,
        )
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
        grainZoomScale = 1f
        grainCanvasLocked = true
        textureDepth = 1f
        textureContrast = 1f
    }
    
    fun setGrainZoomScale(scale: Float) {
        grainZoomScale = scale.coerceAtLeast(0.0001f)
    }

    /**
     * Все GL-объекты удаляются только на GL-потоке.
     */
    fun release() {
        GlCheck.checkOnGlThread()
        program?.release()
        program = null

        if (instanceVboId != 0) {
            GLES30.glDeleteBuffers(
                1,
                intArrayOf(instanceVboId),
                0,
            )
        }

        if (cornerVboId != 0) {
            GLES30.glDeleteBuffers(
                1,
                intArrayOf(cornerVboId),
                0,
            )
        }

        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(
                1,
                intArrayOf(vaoId),
                0,
            )
        }

        vaoId = 0
        cornerVboId = 0
        instanceVboId = 0

        uCanvasToClip = -1
        uColorLinear = -1
        uCanvasSize = -1
        uGrainTex = -1
        uGrainScale = -1
        uGrainZoomScale = -1
        uGrainActive = -1
        uGrainCanvasLocked = -1
        uTextureDepth = -1
        uTextureContrast = -1
        uFlow = -1
        uCoverageOnly = -1
        uEdgeDarkening = -1
        grainTextureId = 0

        segmentCount = 0
        uploadedSegmentCount = 0

        instanceData.clear()
        instanceData.limit(
            maxSegments * FLOATS_PER_SEGMENT,
        )
    }

    private fun checkNoGlError(label: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) {
            "$label: GL error 0x${error.toString(16)}"
        }
    }

    companion object {

        private val ZERO_COLOR = floatArrayOf(0f, 0f, 0f)

        private const val ATTR_CORNER = 0
        private const val ATTR_SEGMENT_A = 1
        private const val ATTR_SEGMENT_B = 2

        private const val CORNER_VERTEX_COUNT = 4

        const val FLOATS_PER_SEGMENT = 8
        const val STRIDE_BYTES =
            FLOATS_PER_SEGMENT * Float.SIZE_BYTES

        const val DEFAULT_MAX_SEGMENTS = 16_384
    }
}
