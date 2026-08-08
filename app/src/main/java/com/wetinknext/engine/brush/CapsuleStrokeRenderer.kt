package com.wetinknext.engine.brush

import android.opengl.GLES30
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
 * - рисование выполняется с GL_MAX;
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
    private var uCanvasSize = -1
    private var uGrainTex = -1
    private var uGrainActive = -1
    private var uGrainScale = -1
    private var uGrainCanvasLocked = -1
    private var uTextureDepth = -1
    private var uTextureContrast = -1

    private var grainTextureId = 0
    private var grainScale = 1f
    private var grainCanvasLocked = true
    private var textureDepth = 1f
    private var textureContrast = 1f

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
        checkOnGlThreadOnly()

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

        check(uCanvasToClip >= 0) {
            "Capsule shader uniform uCanvasToClip is missing"
        }
        check(uColorLinear >= 0) {
            "Capsule shader uniform uColorLinear is missing"
        }
        check(uCanvasSize >= 0) {
            "Capsule shader uniform uCanvasSize is missing"
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
            3,
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
            3,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            3 * Float.SIZE_BYTES,
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
        x1: Float,
        y1: Float,
        radius1: Float,
    ): Boolean {
        if (segmentCount >= maxSegments) {
            overflowCount++
            return false
        }

        val offset = segmentCount * FLOATS_PER_SEGMENT

        instanceData.put(offset, x0)
        instanceData.put(offset + 1, y0)
        instanceData.put(offset + 2, radius0.coerceAtLeast(0.01f))

        instanceData.put(offset + 3, x1)
        instanceData.put(offset + 4, y1)
        instanceData.put(offset + 5, radius1.coerceAtLeast(0.01f))

        segmentCount++
        return true
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
    ) {
        val first = uploadedSegmentCount
        val count = segmentCount - uploadedSegmentCount

        if (count <= 0) return

        val drawn = drawRange(
            target = target,
            width = width,
            height = height,
            canvasToClip = canvasToClip,
            colorLinear = colorLinear,
            firstSegment = first,
            count = count,
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
    ) {
        if (segmentCount <= 0) return

        drawRange(
            target = target,
            width = width,
            height = height,
            canvasToClip = canvasToClip,
            colorLinear = colorLinear,
            firstSegment = 0,
            count = segmentCount,
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
        canvasToClip: FloatArray,
        colorLinear: FloatArray,
        firstSegment: Int,
        count: Int,
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

        /*
         * Shader output:
         *   rgb = linearColor * coverage
         *   a   = coverage
         *
         * GL_MAX объединяет покрытия без сложения.
         * Повторное попадание одного и того же пикселя не делает его темнее.
         */
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_MAX)
        GLES30.glBlendFunc(
            GLES30.GL_ONE,
            GLES30.GL_ONE,
        )

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
            width.toFloat(),
            height.toFloat(),
        )

        GLES30.glUniform1f(
            uGrainScale,
            grainScale,
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
            firstSegment * STRIDE_BYTES,
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
            3,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            firstSegment * STRIDE_BYTES,
        )

        GLES30.glVertexAttribPointer(
            ATTR_SEGMENT_B,
            3,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            firstSegment * STRIDE_BYTES + 3 * Float.SIZE_BYTES,
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

        /*
         * Обязательно вернуть состояние:
         * Compositor и обычные слои используют FUNC_ADD.
         */
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glBindFramebuffer(
            GLES30.GL_FRAMEBUFFER,
            0,
        )

        instanceData.clear()
        instanceData.limit(
            maxSegments * FLOATS_PER_SEGMENT,
        )

        checkNoGlError("CapsuleStrokeRenderer.drawRange")
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
        grainCanvasLocked = true
        textureDepth = 1f
        textureContrast = 1f
    }

    /**
     * Все GL-объекты удаляются только на GL-потоке.
     */
    fun release() {
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
        uGrainActive = -1
        uGrainCanvasLocked = -1
        uTextureDepth = -1
        uTextureContrast = -1
        grainTextureId = 0

        segmentCount = 0
        uploadedSegmentCount = 0

        instanceData.clear()
        instanceData.limit(
            maxSegments * FLOATS_PER_SEGMENT,
        )
    }

    private fun checkOnGlThreadOnly() {
        // Намеренно пустая проверка-документация.
        // create/release вызываются из onSurfaceCreated/onDrawFrame.
    }

    private fun checkNoGlError(label: String) {
        if (!CHECK_GL_ERRORS) return

        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) {
            "$label: GL error 0x${error.toString(16)}"
        }
    }

    companion object {
        private const val CHECK_GL_ERRORS = true

        private const val ATTR_CORNER = 0
        private const val ATTR_SEGMENT_A = 1
        private const val ATTR_SEGMENT_B = 2

        private const val CORNER_VERTEX_COUNT = 4

        const val FLOATS_PER_SEGMENT = 6
        const val STRIDE_BYTES =
            FLOATS_PER_SEGMENT * Float.SIZE_BYTES

        const val DEFAULT_MAX_SEGMENTS = 16_384
    }
}
