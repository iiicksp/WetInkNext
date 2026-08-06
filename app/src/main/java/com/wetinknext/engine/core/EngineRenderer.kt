package com.wetinknext.engine.core

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.wetinknext.engine.brush.BrushSettings
import com.wetinknext.engine.brush.ColorSpaces
import com.wetinknext.engine.brush.DabBuffer
import com.wetinknext.engine.brush.DabRenderer
import com.wetinknext.engine.brush.StampEmitter
import com.wetinknext.engine.canvas.Compositor
import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.input.InputAction
import com.wetinknext.engine.input.InputBatch
import com.wetinknext.engine.input.InputBatchPool
import com.wetinknext.engine.input.StrokeInputCapturer
import com.wetinknext.engine.undo.TileSnapshotCapture
import com.wetinknext.engine.undo.TileSnapshotRestore
import com.wetinknext.engine.undo.UndoEntry
import com.wetinknext.engine.undo.UndoManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The P6 render-thread owner. Active dabs are accumulated only in strokeTarget;
 * UP snapshots and merges them into the selected PaintLayer atomically.
 */
class EngineRenderer(
    private val documentWidth: Int = DEFAULT_CANVAS_WIDTH,
    private val documentHeight: Int = DEFAULT_CANVAS_HEIGHT,
) : GLSurfaceView.Renderer {
    private var caps: GlCaps? = null
    private val layerStack = LayerStack()
    private val undoManager = UndoManager()

    private var geometry: CanvasGeometry? = null
    private var compositor: Compositor? = null
    private var dabRenderer: DabRenderer? = null
    private val strokeTarget = RenderTarget()
    private val canvasToFboMatrix = FloatArray(16)
    private val canvasToClipMatrix = FloatArray(16)

    private var screenWidth = 1
    private var screenHeight = 1

    private val inputPool = InputBatchPool(batchCount = 64, maxSamplesPerBatch = 256)
    private val inputQueue = ArrayBlockingQueue<InputBatch>(64)
    private val inputCapturer = StrokeInputCapturer(layerStack.camera, inputPool, inputQueue)

    private var brushSettings = BrushSettings(
        name = "Round Opaque",
        baseRadiusPx = 16f,
        spacing = 0.12f,
        colorArgb = 0xFF1B1F24L,
        smoothing = 0.25f,
        streamline = 0.05f,
        pressureToOpacity = false,
        pressureGamma = 1.1f,
        minSizeRatio = 0.12f,
    )
    private val stampEmitter = StampEmitter(brushSettings)
    private val dabBuffer = DabBuffer()
    private val strokeColorLinear = FloatArray(3)
    private val strokeDirtyRect = DirtyRect()
    private val dirtyBounds = IntArray(4)

    private var strokeActive = false
    private val cancelRequested = AtomicBoolean(false)

    /** Assigned by PaintSurfaceView; invoked on the GL thread. */
    var onStateChange: ((EditorUiState) -> Unit)? = null

    fun onTouchEvent(event: MotionEvent): Boolean = inputCapturer.onTouchEvent(event)

    /** These methods are called from GLSurfaceView.queueEvent by the UI layer. */
    fun undo() {
        resetStroke()
        val entry = undoManager.popUndo() ?: return
        val layer = layerStack.findLayerById(entry.layerId) ?: return
        TileSnapshotRestore.restore(layer.target, entry.beforeTiles)
        layer.version++
        publishState()
    }

    fun redo() {
        resetStroke()
        val entry = undoManager.popRedo() ?: return
        val layer = layerStack.findLayerById(entry.layerId) ?: return
        TileSnapshotRestore.restore(layer.target, entry.afterTiles)
        layer.version++
        publishState()
    }

    fun setActiveLayer(id: Long): Boolean {
        resetStroke()
        return layerStack.setActive(id).also { changed ->
            if (changed) publishState()
        }
    }

    fun addLayer(): Long = addLayer(nextLayerName())

    fun addLayer(name: String): Long {
        resetStroke()
        return layerStack.addLayer(name).id.also { publishState() }
    }

    fun removeLayer(id: Long): Boolean {
        resetStroke()
        return (layerStack.removeLayer(id) != null).also { removed ->
            if (removed) publishState()
        }
    }

    fun setLayerVisible(id: Long, visible: Boolean) {
        resetStroke()
        val layer = layerStack.findLayerById(id) ?: return
        layer.isVisible = visible
        publishState()
    }

    fun setLayerOpacity(id: Long, opacity: Float) {
        val layer = layerStack.findLayerById(id) ?: return
        layer.opacity = opacity.coerceIn(0f, 1f)
        publishState()
    }

    fun setBrushSize(px: Float) {
        resetStroke()
        brushSettings = brushSettings.copy(baseRadiusPx = px.coerceIn(1f, 200f))
        stampEmitter.updateSettings(brushSettings)
        publishState()
    }

    fun setBrushOpacity(opacity: Float) {
        resetStroke()
        brushSettings = brushSettings.copy(opacity = opacity.coerceIn(0f, 1f))
        stampEmitter.updateSettings(brushSettings)
        publishState()
    }

    fun requestState() {
        publishState()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseGlObjects()
        val nextCaps = GlCaps.query()
        caps = nextCaps
        layerStack.create(nextCaps, documentWidth, documentHeight)
        strokeTarget.create(documentWidth, documentHeight, nextCaps.supportsHalfFloatColorBuffer)
        strokeTarget.clear(0f, 0f, 0f, 0f)
        ViewTransform.buildCanvasToFbo(
            documentWidth.toFloat(),
            documentHeight.toFloat(),
            canvasToFboMatrix,
        )
        geometry = CanvasGeometry().also { it.create(documentWidth, documentHeight) }
        compositor = Compositor().also { it.create() }
        dabRenderer = DabRenderer(dabBuffer.capacity).also { it.create() }
        ColorSpaces.srgb8ToLinear(brushSettings.colorArgb, strokeColorLinear)
        publishState()
        GlCheck.noError("P6 surface creation")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        layerStack.camera.fitCanvas(layerStack.canvasWidth, layerStack.canvasHeight, screenWidth, screenHeight)
        GlCheck.noError("P6 surface changed")
    }

    override fun onDrawFrame(gl: GL10?) {
        if (cancelRequested.compareAndSet(true, false)) resetStroke()
        drainInput()

        if (strokeActive && dabBuffer.count > 0) {
            strokeTarget.clear(0f, 0f, 0f, 0f)
            dabRenderer?.drawInto(
                target = strokeTarget,
                width = layerStack.canvasWidth,
                height = layerStack.canvasHeight,
                canvasToFbo = canvasToFboMatrix,
                dabs = dabBuffer,
                colorLinear = strokeColorLinear,
            )
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0.08f, 0.09f, 0.12f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        layerStack.camera.snapshot().buildCanvasToClip(
            screenWidth.toFloat(),
            screenHeight.toFloat(),
            canvasToClipMatrix,
        )
        compositor?.render(
            geometry = geometry ?: return,
            layers = layerStack,
            activeLayerId = layerStack.activeLayerId,
            strokeTextureId = if (strokeActive && dabBuffer.count > 0) strokeTarget.textureId else 0,
            canvasToClip = canvasToClipMatrix,
        )
    }

    /** Safe to call from the UI thread; cancellation is executed on the next GL frame. */
    fun cancelActiveStroke() {
        cancelRequested.set(true)
    }

    /** Must run on the GL thread while the context is still current. */
    fun releaseGlObjects() {
        discardPendingInput()
        strokeActive = false
        stampEmitter.reset()
        dabBuffer.clear()
        undoManager.clear()
        dabRenderer?.release()
        dabRenderer = null
        compositor?.release()
        compositor = null
        geometry?.release()
        geometry = null
        strokeTarget.release()
        layerStack.release()
        caps = null
    }

    private fun drainInput() {
        while (true) {
            val batch = inputQueue.poll() ?: return
            try {
                when (batch.action) {
                    InputAction.DOWN -> {
                        if (!batch.isEmpty()) {
                            resetStroke()
                            strokeActive = true
                            ColorSpaces.srgb8ToLinear(brushSettings.colorArgb, strokeColorLinear)
                            stampEmitter.begin(batch, dabBuffer)
                        }
                    }

                    InputAction.MOVE -> if (strokeActive) {
                        stampEmitter.append(batch, dabBuffer)
                    }

                    InputAction.UP -> if (strokeActive) {
                        stampEmitter.finish(dabBuffer, cancel = false)
                        commitStroke()
                        resetStroke()
                    }

                    InputAction.CANCEL -> resetStroke()
                }
            } finally {
                inputPool.release(batch)
            }
        }
    }

    private fun commitStroke() {
        val layer = layerStack.activeLayer() ?: return
        if (layer.isLocked || dabBuffer.count == 0) return
        if (!computeDirtyPixelBounds(dirtyBounds)) return
        val renderer = dabRenderer ?: return

        // Both snapshots use the actual layer format (RGBA8 or RGBA16F).
        val beforeTiles = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        renderer.drawInto(
            target = layer.target,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            canvasToFbo = canvasToFboMatrix,
            dabs = dabBuffer,
            colorLinear = strokeColorLinear,
        )
        layer.version++
        val afterTiles = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        undoManager.push(UndoEntry(layer.id, beforeTiles, afterTiles))
        publishState()
    }

    /** Returns a clamped pixel region covering the exact emitted dabs and their AA fringe. */
    private fun computeDirtyPixelBounds(out: IntArray): Boolean {
        if (dabBuffer.count == 0) return false
        strokeDirtyRect.clear()
        for (index in 0 until dabBuffer.count) {
            val offset = index * DabBuffer.FLOATS_PER_DAB
            val x = dabBuffer.floats.get(offset)
            val y = dabBuffer.floats.get(offset + 1)
            val radius = dabBuffer.floats.get(offset + 2)
            strokeDirtyRect.include(x, y, radius)
        }
        strokeDirtyRect.expand(AA_MARGIN_PX)
        strokeDirtyRect.clamp(layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat())
        strokeDirtyRect.toPixelBounds(out)
        return out[2] > out[0] && out[3] > out[1]
    }

    private fun resetStroke() {
        strokeActive = false
        stampEmitter.reset()
        dabBuffer.clear()
        if (strokeTarget.framebufferId != 0) {
            strokeTarget.clear(0f, 0f, 0f, 0f)
        }
    }

    /** Releases pooled batches without interpreting them during shutdown/context recreation. */
    private fun discardPendingInput() {
        while (true) {
            val batch = inputQueue.poll() ?: return
            inputPool.release(batch)
        }
    }

    private fun publishState() {
        val listener = onStateChange ?: return
        val layers = layerStack.allLayers()
        val activeLayerId = layerStack.activeLayerId
        val canDeleteLayers = layers.size > 1
        listener(
            EditorUiState(
                layers = layers.map { layer ->
                    LayerUiModel(
                        id = layer.id,
                        name = layer.name,
                        isVisible = layer.isVisible,
                        isLocked = layer.isLocked,
                        opacity = layer.opacity,
                        active = layer.id == activeLayerId,
                        canDelete = !layer.isLocked && canDeleteLayers,
                    )
                },
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
                brushSizePx = brushSettings.baseRadiusPx,
                brushOpacity = brushSettings.opacity,
                activeLayerId = activeLayerId,
                ready = layerStack.canvasWidth > 0 && layerStack.canvasHeight > 0,
            ),
        )
    }

    private fun nextLayerName(): String = "Слой ${layerStack.count + 1}"

    companion object {
        const val DEFAULT_CANVAS_WIDTH = 1500
        const val DEFAULT_CANVAS_HEIGHT = 2000
        private const val AA_MARGIN_PX = 2f
    }
}
