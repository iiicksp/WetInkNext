package com.wetinknext.engine.core

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.wetinknext.engine.brush.BrushSettings
import com.wetinknext.engine.brush.ColorSpaces
import com.wetinknext.engine.brush.DabBuffer
import com.wetinknext.engine.brush.DabRenderer
import com.wetinknext.engine.brush.StampEmitter
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.ShaderLib
import com.wetinknext.engine.gl.RenderTarget
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
    private val canvasToFboMatrix = FloatArray(16)
    private val inputPool = InputBatchPool(64, 256)
    private val inputQueue = ArrayBlockingQueue<InputBatch>(64)
    private val inputCapturer = StrokeInputCapturer(paintEngine.camera, inputPool, inputQueue)
    private val dabBuffer = DabBuffer()
    private val defaultBrush = BrushSettings(name="Round Opaque", baseRadiusPx=16f, spacing=.12f, colorArgb=0xFF1B1F24L, smoothing=.25f, streamline=.05f, pressureToOpacity=false, pressureGamma=1.1f, minSizeRatio=.12f)
    private val stampEmitter = StampEmitter(defaultBrush)
    private val strokeTarget = RenderTarget()
    private val strokeColor = FloatArray(3)
    private var dabRenderer: DabRenderer? = null
    private var strokeActive = false
    private var previewVisible = false
    private val cancelRequested = AtomicBoolean(false)

    fun onTouchEvent(event: MotionEvent): Boolean = inputCapturer.onTouchEvent(event)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseGlObjects()
        val nextCaps = GlCaps.query(); caps = nextCaps
        presentProgram = GlProgram(ShaderLib.canvasPresentVertex, ShaderLib.strokeCompositeFragment)
        paintEngine.create(nextCaps, documentWidth, documentHeight)
        paintEngine.clearCanvas()
        strokeTarget.create(documentWidth, documentHeight, nextCaps.supportsHalfFloatColorBuffer)
        strokeTarget.clear(0f,0f,0f,0f)
        ViewTransform.buildCanvasToFbo(documentWidth.toFloat(), documentHeight.toFloat(), canvasToFboMatrix)
        geometry = CanvasGeometry().also { it.create(documentWidth, documentHeight) }
        dabRenderer = DabRenderer(dabBuffer.capacity).also { it.create() }
        presentProgram!!.use()
        uTexture = GLES30.glGetUniformLocation(presentProgram!!.id, "uCanvasTex")
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
        if(strokeActive && dabBuffer.count>0){strokeTarget.clear(0f,0f,0f,0f);dabRenderer?.drawInto(strokeTarget,paintEngine.canvasWidth,paintEngine.canvasHeight,canvasToFboMatrix,dabBuffer,strokeColor)}
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
        GLES30.glUniform1i(uTexture, 0)
        val strokeTex=GLES30.glGetUniformLocation(program.id,"uStrokeTex");val strokeActiveUniform=GLES30.glGetUniformLocation(program.id,"uStrokeActive")
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,strokeTarget.textureId);GLES30.glUniform1i(strokeTex,1);GLES30.glUniform1i(strokeActiveUniform,if(strokeActive)1 else 0);geometry?.draw();GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun cancelActiveStroke() { cancelRequested.set(true) }
    fun releaseGlObjects() {
        drainInput(); resetStroke()
        dabRenderer?.release(); dabRenderer = null
        strokeTarget.release()
        geometry?.release(); geometry = null
        presentProgram?.release(); presentProgram = null
        paintEngine.release(); caps = null
        uTexture = -1; uCanvasToClip = -1; uCanvasSize = -1
    }

    private fun drainInput() {
        while (true) {
            val batch = inputQueue.poll() ?: return
            when (batch.action) {
                InputAction.DOWN -> if (!batch.isEmpty()) { resetStroke(); strokeActive = true; previewVisible = true; ColorSpaces.srgb8ToLinear(defaultBrush.colorArgb,strokeColor); stampEmitter.begin(batch, dabBuffer) }
                InputAction.MOVE -> if (strokeActive) stampEmitter.append(batch, dabBuffer)
                InputAction.UP -> if (strokeActive) { stampEmitter.finish(dabBuffer, false); dabRenderer?.drawInto(paintEngine.canvasTarget,paintEngine.canvasWidth,paintEngine.canvasHeight,canvasToFboMatrix,dabBuffer,strokeColor); dabBuffer.clear(); strokeActive = false }
                InputAction.CANCEL -> resetStroke()
            }
            inputPool.release(batch)
        }
    }

    private fun resetStroke() { strokeActive = false; previewVisible = false; stampEmitter.reset(); dabBuffer.clear() }

    companion object { const val DEFAULT_CANVAS_WIDTH = 1500; const val DEFAULT_CANVAS_HEIGHT = 2000 }
}
