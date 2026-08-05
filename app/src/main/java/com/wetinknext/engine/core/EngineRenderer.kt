package com.wetinknext.engine.core

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.wetinknext.engine.brush.BrushSettings
import com.wetinknext.engine.brush.DabBuffer
import com.wetinknext.engine.brush.DabRenderer
import com.wetinknext.engine.brush.StampEmitter
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.ShaderLib
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import com.wetinknext.engine.input.InputAction
import com.wetinknext.engine.input.InputBatch
import com.wetinknext.engine.input.InputBatchPool
import com.wetinknext.engine.input.StrokeInputCapturer

class EngineRenderer(
    private val documentWidth: Int = DEFAULT_CANVAS_WIDTH,
    private val documentHeight: Int = DEFAULT_CANVAS_HEIGHT,
) : GLSurfaceView.Renderer {
    private val paintEngine = PaintEngine()
    private var caps: GlCaps? = null
    private var presentProgram: GlProgram? = null
    private var geometry: CanvasGeometry? = null
    private var screenWidth = 1
    private var screenHeight = 1
    private var uTexture = -1
    private var uCanvasToClip = -1
    private var uCanvasSize = -1
    private val presentMatrix = FloatArray(16)
    private val inputPool = InputBatchPool(64, 256)
    private val inputQueue = ArrayBlockingQueue<InputBatch>(64)
    private val inputCapturer = StrokeInputCapturer(paintEngine.camera, inputPool, inputQueue)
    private val dabBuffer = DabBuffer()
    private val stampEmitter = StampEmitter(BrushSettings(smoothing = .25f, streamline = .05f))
    private var dabRenderer: DabRenderer? = null
    private var strokeActive = false
    private var previewVisible = false
    private val cancelRequested = AtomicBoolean(false)

    fun onTouchEvent(event: MotionEvent): Boolean = inputCapturer.onTouchEvent(event)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseGlObjects()
        val nextCaps = GlCaps.query(); caps = nextCaps
        presentProgram = GlProgram(ShaderLib.canvasPresentVertex, ShaderLib.presentFragment)
        paintEngine.create(nextCaps, documentWidth, documentHeight)
        paintEngine.clearCanvas()
        geometry = CanvasGeometry().also { it.create(documentWidth, documentHeight) }
        dabRenderer = DabRenderer(dabBuffer.capacity).also { it.create() }
        presentProgram!!.use()
        uTexture = GLES30.glGetUniformLocation(presentProgram!!.id, "uTexture")
        uCanvasToClip = GLES30.glGetUniformLocation(presentProgram!!.id, "uCanvasToClip")
        uCanvasSize = GLES30.glGetUniformLocation(presentProgram!!.id, "uCanvasSize")
        check(uTexture >= 0 && uCanvasToClip >= 0 && uCanvasSize >= 0) { "P3 present shader uniforms missing" }
        GlCheck.noError("P3 surface creation")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1); screenHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        paintEngine.camera.fitCanvas(paintEngine.canvasWidth, paintEngine.canvasHeight, screenWidth, screenHeight)
        GlCheck.noError("P3 surface changed")
    }

    override fun onDrawFrame(gl: GL10?) {
        if (cancelRequested.compareAndSet(true, false)) resetStroke()
        drainInput()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0.08f, 0.09f, 0.12f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val program = presentProgram ?: return
        program.use()
        paintEngine.camera.snapshot().buildCanvasToClip(screenWidth.toFloat(), screenHeight.toFloat(), presentMatrix)
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, presentMatrix, 0)
        GLES30.glUniform2f(uCanvasSize, paintEngine.canvasWidth.toFloat(), paintEngine.canvasHeight.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, paintEngine.canvasTarget.textureId)
        GLES30.glUniform1i(uTexture, 0); geometry?.draw(); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        if (previewVisible && dabBuffer.count > 0) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            dabRenderer?.draw(dabBuffer, presentMatrix)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    fun cancelActiveStroke() { cancelRequested.set(true) }
    fun releaseGlObjects() {
        drainInput(); resetStroke()
        dabRenderer?.release(); dabRenderer = null
        geometry?.release(); geometry = null
        presentProgram?.release(); presentProgram = null
        paintEngine.release(); caps = null
        uTexture = -1; uCanvasToClip = -1; uCanvasSize = -1
    }

    private fun drainInput() {
        while (true) {
            val batch = inputQueue.poll() ?: return
            when (batch.action) {
                InputAction.DOWN -> if (!batch.isEmpty()) { resetStroke(); strokeActive = true; previewVisible = true; stampEmitter.begin(batch, dabBuffer) }
                InputAction.MOVE -> if (strokeActive) stampEmitter.append(batch, dabBuffer)
                InputAction.UP -> if (strokeActive) { stampEmitter.finish(dabBuffer, false); strokeActive = false }
                InputAction.CANCEL -> resetStroke()
            }
            inputPool.release(batch)
        }
    }

    private fun resetStroke() { strokeActive = false; previewVisible = false; stampEmitter.reset(); dabBuffer.clear() }

    companion object { const val DEFAULT_CANVAS_WIDTH = 1500; const val DEFAULT_CANVAS_HEIGHT = 2000 }
}
