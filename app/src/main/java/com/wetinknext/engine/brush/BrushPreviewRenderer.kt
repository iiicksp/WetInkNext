package com.wetinknext.engine.brush

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.input.InputAction
import com.wetinknext.engine.input.InputBatch
import com.wetinknext.engine.input.PointerTool
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Renders a real brush sample for the brush library UI and the brush studio.
 *
 * The preview is produced by the same emitters and shaders the editor uses:
 * a synthesised stroke is pushed through [StampEmitter] / [CapsuleEmitter]
 * exactly like pen input, then drawn with the dab / capsule renderers into a
 * small offscreen target and read back as top-to-bottom RGBA8. What the panel
 * shows is therefore what the brush actually does.
 *
 * WET brushes run the real fluid pipeline on miniature targets: deposit,
 * several simulation steps, then the finalize pass.
 *
 * All methods must run on the GL thread. Paper-grain / stamp textures are not
 * applied in v1 — mask, falloff, spacing, pressure and wet behaviour are
 * honest, and the sample stroke is fully deterministic (no RNG), so previews
 * are stable between renders.
 */
class BrushPreviewRenderer(
    private val previewWidth: Int = 256,
    private val previewHeight: Int = 160,
) {
    private data class Sample(
        val x: Float,
        val y: Float,
        val pressure: Float,
        val timestampNanos: Long,
    )

    private var created = false

    private val target = RenderTarget()
    private val dabRenderer = DabRenderer(maxDabs = 96)
    private val capsuleRenderer = CapsuleStrokeRenderer(maxSegments = 640)
    private val stampEmitter = StampEmitter(BrushSettings())
    private val capsuleEmitter = CapsuleEmitter(BrushSettings())
    private val dabBuffer = DabBuffer(capacity = 96)

    private val wetSimulation = WetSimulationRenderer()
    private val wetTargetA = RenderTarget()
    private val wetTargetB = RenderTarget()
    private val wetComposite = RenderTarget()
    private var wetFrontIsA = true

    private var readBuffer: ByteBuffer? = null
    private val matrix = FloatArray(16)
    private val strokeColorLinear = FloatArray(3)

    private val wetFront: RenderTarget
        get() = if (wetFrontIsA) wetTargetA else wetTargetB

    private val wetBack: RenderTarget
        get() = if (wetFrontIsA) wetTargetB else wetTargetA

    /** A rendered sample: top-to-bottom RGBA8 at [width] x [height]. */
    data class PreviewResult(
        val rgba: ByteArray,
        val width: Int,
        val height: Int,
    )

    /** Renders one sample; returns null while GL objects are unavailable. */
    fun render(settings: BrushSettings): PreviewResult? {
        GlCheck.checkOnGlThread()
        if (!ensure()) return null
        val safe = settings.resolved()
        ColorSpaces.srgb8ToLinear(safe.colorArgb, strokeColorLinear)

        val stroke = synthesizeStroke()
        target.clear(0f, 0f, 0f, 0f)

        val pixels = when (safe.renderMode) {
            BrushRenderMode.STAMP -> renderStamp(safe, stroke)
            BrushRenderMode.RIBBON -> renderRibbon(safe, stroke)
            BrushRenderMode.WET -> renderWet(safe, stroke)
        }
        return pixels?.let { PreviewResult(it, previewWidth, previewHeight) }
    }

    fun release() {
        GlCheck.checkOnGlThread()
        if (created) {
            dabRenderer.release()
            capsuleRenderer.release()
            wetSimulation.release()
            target.release()
            wetTargetA.release()
            wetTargetB.release()
            wetComposite.release()
            created = false
        }
    }

    // ------------------------------------------------------------------ gl ---

    private fun ensure(): Boolean {
        if (created) return true
        dabRenderer.create()
        capsuleRenderer.create()
        wetSimulation.create()
        target.create(previewWidth, previewHeight, preferHalfFloat = false)
        created = true
        return true
    }

    private fun brushMatrix() {
        // Simple document-to-clip ortho for the preview target.
        matrix.fill(0f)
        matrix[0] = 2f / previewWidth
        matrix[5] = 2f / previewHeight
        matrix[10] = 1f
        matrix[12] = -1f
        matrix[13] = -1f
        matrix[15] = 1f
    }

    // ---------------------------------------------------------------- input --

    /** A deterministic cursive wave; pressure peaks mid-stroke. */
    private fun synthesizeStroke(): List<Sample> {
        val stroke = ArrayList<Sample>(STROKE_SAMPLE_COUNT + 1)
        for (index in 0..STROKE_SAMPLE_COUNT) {
            val t = index.toFloat() / STROKE_SAMPLE_COUNT
            val x = previewWidth * (0.10f + 0.80f * t)
            val y = previewHeight / 2f +
                (previewHeight * 0.30f) * sin(2f * PI.toFloat() * t)
            stroke += Sample(
                x = x,
                y = y,
                pressure = 0.30f + 0.65f * sin(PI.toFloat() * t),
                timestampNanos = index * SAMPLE_STEP_NANOS,
            )
        }
        return stroke
    }

    private fun toBatch(
        stroke: List<Sample>,
        action: InputAction,
        first: Int,
        count: Int,
    ): InputBatch {
        val end = (first + count).coerceAtMost(stroke.size)
        val batch = InputBatch(max(1, end - first))
        batch.begin(action)
        for (index in first until end) {
            val sample = stroke[index]
            batch.addSample(
                canvasX = sample.x,
                canvasY = sample.y,
                pressure = sample.pressure,
                tiltX = 0f,
                tiltY = 0f,
                orientationRad = 0f,
                timestampNanos = sample.timestampNanos,
                pointerId = POINTER_ID,
                tool = PointerTool.STYLUS,
                historical = false,
            )
        }
        return batch
    }

    // ---------------------------------------------------------------- stamp --

    private fun renderStamp(settings: BrushSettings, stroke: List<Sample>): ByteArray? {
        dabRenderer.beginStroke()
        dabRenderer.setWetMode(false)
        dabRenderer.setFalloff(settings.falloff)
        dabRenderer.squareStroke = settings.squareStroke
        dabRenderer.noAntialias = settings.noAntialias
        dabRenderer.clearGrainTexture()
        dabRenderer.clearShapeTexture()

        stampEmitter.updateSettings(settings)
        stampEmitter.begin(toBatch(stroke, InputAction.DOWN, first = 0, count = 1), dabBuffer, settings)
        if (stroke.size > 1) {
            stampEmitter.append(toBatch(stroke, InputAction.MOVE, first = 1, count = stroke.size - 1), dabBuffer)
        }
        stampEmitter.finish(dabBuffer, cancel = false)

        brushMatrix()
        dabRenderer.drawInto(
            target = target,
            width = previewWidth,
            height = previewHeight,
            canvasToFbo = matrix,
            dabs = dabBuffer,
            colorLinear = strokeColorLinear,
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
        )
        return readPixels(target)
    }

    // --------------------------------------------------------------- ribbon --

    private fun renderRibbon(settings: BrushSettings, stroke: List<Sample>): ByteArray? {
        capsuleRenderer.beginStroke()
        capsuleEmitter.updateSettings(settings)
        capsuleEmitter.begin(
            toBatch(stroke, InputAction.DOWN, first = 0, count = 1),
            capsuleRenderer,
            settings,
        )
        if (stroke.size > 1) {
            capsuleEmitter.append(
                toBatch(stroke, InputAction.MOVE, first = 1, count = stroke.size - 1),
                capsuleRenderer,
            )
        }
        capsuleEmitter.finish(capsuleRenderer, cancel = false)

        brushMatrix()
        capsuleRenderer.drawAll(
            target = target,
            width = previewWidth,
            height = previewHeight,
            canvasToClip = matrix,
            colorLinear = strokeColorLinear,
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            flow = settings.flow,
        )
        return readPixels(target)
    }

    // ----------------------------------------------------------------- wet ---

    private fun renderWet(settings: BrushSettings, stroke: List<Sample>): ByteArray? {
        wetTargetA.create(previewWidth, previewHeight, preferHalfFloat = false)
        wetTargetB.create(previewWidth, previewHeight, preferHalfFloat = false)
        wetComposite.create(previewWidth, previewHeight, preferHalfFloat = false)
        wetFrontIsA = true
        wetTargetA.clear(0f, 0f, 0f, 0f)
        wetTargetB.clear(0f, 0f, 0f, 0f)

        dabRenderer.beginStroke()
        dabRenderer.setWetMode(true)
        dabRenderer.setWetness(settings.wet.wetness)
        dabRenderer.setFalloff(settings.falloff)

        stampEmitter.updateSettings(settings)
        stampEmitter.begin(toBatch(stroke, InputAction.DOWN, first = 0, count = 1), dabBuffer, settings)
        brushMatrix()

        if (stroke.size > 1) {
            stampEmitter.append(
                toBatch(stroke, InputAction.MOVE, first = 1, count = stroke.size - 1),
                dabBuffer,
            )
            depositPending(settings)
        }

        // Let the wash bloom and dry a little even after the pen lifts.
        for (index in 0 until WET_SETTLE_STEPS) wetStep(settings, finalize = false)

        // Finalize into a separate composite target, then read it.
        wetSimulation.step(
            source = wetFront,
            destination = wetComposite,
            wet = settings.wet,
            deltaSeconds = WET_STEP_SECONDS,
            finalize = true,
            coverageColor = strokeColorLinear,
        )
        return readPixels(wetComposite)
    }

    private fun depositPending(settings: BrushSettings) {
        dabRenderer.drawPendingInto(
            target = wetFront,
            width = previewWidth,
            height = previewHeight,
            canvasToFbo = matrix,
            dabs = dabBuffer,
            colorLinear = strokeColorLinear,
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            strokeOpacity = 1f,
        )
        wetStep(settings, finalize = false)
    }

    private fun wetStep(settings: BrushSettings, finalize: Boolean) {
        wetSimulation.step(
            source = wetFront,
            destination = wetBack,
            wet = settings.wet,
            deltaSeconds = WET_STEP_SECONDS,
            finalize = finalize,
            coverageColor = strokeColorLinear,
        )
        wetFrontIsA = !wetFrontIsA
    }

    // ------------------------------------------------------------ readback --

    private fun readPixels(source: RenderTarget): ByteArray {
        source.bind()
        GLES30.glViewport(0, 0, source.width, source.height)
        try {
            return readTopDownRgba(source.width, source.height)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun readTopDownRgba(width: Int, height: Int): ByteArray {
        val rowBytes = width * 4
        val size = rowBytes * height
        val buffer = obtainReadBuffer(size)
        buffer.clear()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        try {
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            GlCheck.noError("BrushPreviewRenderer.glReadPixels")
            val pixels = ByteArray(size)
            for (row in 0 until height) {
                val sourceOffset = (height - 1 - row) * rowBytes
                buffer.position(sourceOffset)
                buffer.get(pixels, row * rowBytes, rowBytes)
            }
            return pixels
        } finally {
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        }
    }

    private fun obtainReadBuffer(size: Int): ByteBuffer {
        val current = readBuffer
        if (current != null && current.capacity() >= size) return current
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { readBuffer = it }
    }

    private companion object {
        const val POINTER_ID = 0
        const val SAMPLE_STEP_NANOS = 16_000_000L
        const val STROKE_SAMPLE_COUNT = 72
        const val WET_SETTLE_STEPS = 14
        const val WET_STEP_SECONDS = 1f / 60f
    }
}