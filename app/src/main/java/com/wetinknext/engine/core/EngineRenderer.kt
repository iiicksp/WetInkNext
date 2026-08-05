package com.wetinknext.engine.core

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.ShaderLib
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseGlObjects()
        val nextCaps = GlCaps.query(); caps = nextCaps
        presentProgram = GlProgram(ShaderLib.canvasPresentVertex, ShaderLib.presentFragment)
        paintEngine.create(nextCaps, documentWidth, documentHeight)
        paintEngine.clearCanvas()
        geometry = CanvasGeometry().also { it.create(documentWidth, documentHeight) }
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
    }

    fun cancelActiveStroke() = Unit
    fun releaseGlObjects() {
        geometry?.release(); geometry = null
        presentProgram?.release(); presentProgram = null
        paintEngine.release(); caps = null
        uTexture = -1; uCanvasToClip = -1; uCanvasSize = -1
    }

    companion object { const val DEFAULT_CANVAS_WIDTH = 1500; const val DEFAULT_CANVAS_HEIGHT = 2000 }
}
