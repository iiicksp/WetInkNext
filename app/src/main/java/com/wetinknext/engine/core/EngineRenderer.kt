package com.wetinknext.engine.core

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.ui.graphics.toArgb
import com.wetinknext.engine.brush.BrushSettings
import com.wetinknext.engine.brush.BrushTexture
import com.wetinknext.engine.brush.LoadedBrushTexture
import com.wetinknext.engine.brush.BrushRenderMode
import com.wetinknext.engine.brush.CapsuleEmitter
import com.wetinknext.engine.brush.CapsuleStrokeRenderer
import com.wetinknext.engine.brush.ColorSpaces
import com.wetinknext.engine.brush.DabBuffer
import com.wetinknext.engine.brush.DabRenderer
import com.wetinknext.engine.brush.StampEmitter
import com.wetinknext.engine.canvas.Compositor
import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.canvas.StrokeBlitter
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.input.InputAction
import com.wetinknext.engine.input.InputBatch
import com.wetinknext.engine.input.InputBatchPool
import com.wetinknext.engine.input.StrokeInputCapturer
import com.wetinknext.engine.undo.DeflateTileCompressor
import com.wetinknext.engine.undo.TileSnapshotCapture
import com.wetinknext.engine.undo.TileSnapshotRestore
import com.wetinknext.engine.undo.UndoEntry
import com.wetinknext.engine.undo.UndoManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
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
    private var undoExecutor = Executors.newSingleThreadExecutor()
    private val tileCompressor = DeflateTileCompressor()
    private val pendingUndoEntries = ConcurrentLinkedQueue<UndoEntry>()
    private var glThread: Thread? = null

    private var geometry: CanvasGeometry? = null
    private var compositor: Compositor? = null
    private var dabRenderer: DabRenderer? = null
    private var capsuleRenderer: CapsuleStrokeRenderer? = null
    private var grainTexture: BrushTexture? = null
    private var grainPath: String? = null
    private val strokeTarget = RenderTarget()
    private val canvasToFboMatrix = FloatArray(16)
    private val canvasToClipMatrix = FloatArray(16)

    private var screenWidth = 1
    private var screenHeight = 1

    private val inputPool = InputBatchPool(batchCount = 64, maxSamplesPerBatch = 256)
    private val inputQueue = ArrayBlockingQueue<InputBatch>(64)
    private val inputCapturer = StrokeInputCapturer(layerStack.camera, inputPool, inputQueue)

    private var brushSettings = BrushSettings(
        name = "G-Pen",
        renderMode = BrushRenderMode.RIBBON,
        baseRadiusPx = 9f,
        spacing = 0.12f,
        colorArgb = 0xFF1B1F24L,
        smoothing = 0.35f,
        streamline = 0.22f,
        pressureToOpacity = false,
        pressureGamma = 1.7f,
        minSizeRatio = 0.06f,
        ribbon = com.wetinknext.engine.brush.RibbonSettings(
            cap = com.wetinknext.engine.brush.RibbonCap.ROUND,
            join = com.wetinknext.engine.brush.RibbonJoin.ROUND,
            miterLimit = 2.5f,
            minPointDistancePx = 1.25f,
            aaWidthPx = 1f,
            taperStartPx = 16f,
            taperEndPx = 64f,
        ),
    )
    private val stampEmitter = StampEmitter(brushSettings)
    private val capsuleEmitter = CapsuleEmitter(brushSettings)
    private val dabBuffer = DabBuffer()
    private val strokeColorLinear = FloatArray(3)
    private val strokeDirtyRect = DirtyRect()
    private val dirtyBounds = IntArray(4)

    private var strokeActive = false
    private var pendingBrush: BrushSettings? = null
    private var strokeBlitter: StrokeBlitter? = null
    private var capsulePreviewInitialized = false
    private var stampPreviewInitialized = false
    private val cancelRequested = AtomicBoolean(false)

    /** Assigned by PaintSurfaceView; invoked on the GL thread. */
    var onStateChange: ((EditorUiState) -> Unit)? = null

    fun setOnSecondaryPointerDown(listener: () -> Unit) {
        inputCapturer.onSecondaryPointerDown = listener
    }

    fun onTouchEvent(event: MotionEvent): Boolean = inputCapturer.onTouchEvent(event)

    fun requestCancelFromInput() {
        cancelRequested.set(true)
    }

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

    /** px = ДИАМЕТР кисти в canvas-пикселях, ровно то, что показывает UI. */
    fun setBrushSize(px: Float) =
        updateBrush(brushSettings.copy(baseRadiusPx = (px * .5f).coerceIn(.5f, 200f)))

    fun setBrushOpacity(opacity: Float) =
        updateBrush(brushSettings.copy(opacity = opacity.coerceIn(0f, 1f)))

    fun setBrushColor(color: androidx.compose.ui.graphics.Color) =
        updateBrush(brushSettings.copy(colorArgb = color.toArgb().toLong() and 0xFFFFFFFFL))

    fun applyBrush(settings: BrushSettings) {
        updateBrush(settings.resolved())
    }

    /** Правка кисти во время штриха больше не отменяет штрих: применяем на следующем DOWN. */
    private fun updateBrush(next: BrushSettings) {
        brushSettings = next
        if (strokeActive) pendingBrush = next else applyBrushToEmitters(next)
        publishState()
    }

    private fun applyBrushToEmitters(settings: BrushSettings) {
        stampEmitter.updateSettings(settings)
        capsuleEmitter.updateSettings(settings)
    }

    fun requestState() {
        publishState()
    }

    fun checkOnGlThread() {
        val current = Thread.currentThread()
        check(current === glThread) {
            "GL resource accessed outside GLSurfaceView render thread: current=$current, expected=$glThread"
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val currentThread = Thread.currentThread()
        glThread = currentThread
        GlCheck.setGlThread(currentThread)
        releaseGlObjects()
        if (undoExecutor.isShutdown) {
            undoExecutor = Executors.newSingleThreadExecutor()
        }
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
        capsuleRenderer = CapsuleStrokeRenderer().also {
            it.create()
        }
        strokeBlitter = StrokeBlitter().also { it.create() }
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
        processPendingUndo()

        if (strokeActive && brushSettings.renderMode == BrushRenderMode.RIBBON) {
            renderCapsulePreview()
        } else if (strokeActive && brushSettings.renderMode == BrushRenderMode.STAMP && dabBuffer.count > 0) {
            // No longer clearing and drawing everything here, handled incrementally in drainInput
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
            strokeTextureId = if (
                strokeActive &&
                (
                    dabBuffer.count > 0 ||
                        (
                            brushSettings.renderMode == BrushRenderMode.RIBBON &&
                                capsuleEmitter.hasStroke
                            )
                    )
            ) {
                strokeTarget.textureId
            } else {
                0
            },
            strokeOpacity = (brushSettings.opacity * brushSettings.flow).coerceIn(0f, 1f),
            canvasToClip = canvasToClipMatrix,
        )
    }

    private fun processPendingUndo() {
        while (true) {
            val entry = pendingUndoEntries.poll() ?: break
            undoManager.push(entry)
            publishState()
        }
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
        pendingUndoEntries.clear()
        dabRenderer?.release()
        dabRenderer = null
        grainTexture?.release()
        grainTexture = null
        grainPath = null
        capsuleRenderer?.release()
        capsuleRenderer = null
        strokeBlitter?.release()
        strokeBlitter = null
        pendingBrush = null
        compositor?.release()
        compositor = null
        geometry?.release()
        geometry = null
        undoExecutor.shutdownNow()
        pendingUndoEntries.clear()
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
                            pendingBrush?.let { applyBrushToEmitters(it); pendingBrush = null }
                            strokeActive = true

                            ColorSpaces.srgb8ToLinear(
                                brushSettings.colorArgb,
                                strokeColorLinear,
                            )

                            if (brushSettings.renderMode == BrushRenderMode.RIBBON) {
                                capsuleEmitter.begin(
                                    batch = batch,
                                    out = checkNotNull(capsuleRenderer),
                                )
                            } else {
                                dabRenderer?.beginStroke()
                                stampEmitter.begin(batch, dabBuffer)
                                strokeTarget.clear(0f, 0f, 0f, 0f)
                                stampPreviewInitialized = true

                                dabRenderer?.drawPendingInto(
                                    target = strokeTarget,
                                    width = layerStack.canvasWidth,
                                    height = layerStack.canvasHeight,
                                    canvasToFbo = canvasToFboMatrix,
                                    dabs = dabBuffer,
                                    colorLinear = strokeColorLinear,
                                    blendPolicy = brushSettings.blendPolicy,
                                )
                            }
                        }
                    }

                    InputAction.MOVE -> if (strokeActive) {
                        if (brushSettings.renderMode == BrushRenderMode.RIBBON) {
                            capsuleEmitter.append(
                                batch = batch,
                                out = checkNotNull(capsuleRenderer),
                            )
                        } else {
                            stampEmitter.append(batch, dabBuffer)
                            dabRenderer?.drawPendingInto(
                                target = strokeTarget,
                                width = layerStack.canvasWidth,
                                height = layerStack.canvasHeight,
                                canvasToFbo = canvasToFboMatrix,
                                dabs = dabBuffer,
                                colorLinear = strokeColorLinear,
                                blendPolicy = brushSettings.blendPolicy,
                            )
                        }
                    }

                    InputAction.UP -> if (strokeActive) {
                        if (brushSettings.renderMode == BrushRenderMode.RIBBON) {
                            val renderer = checkNotNull(capsuleRenderer)

                            // UP должен сначала обработать historical samples,
                            // затем текущую точку, которую уже положил capturer.
                            capsuleEmitter.append(
                                batch = batch,
                                out = renderer,
                            )
                            capsuleEmitter.finish(
                                out = renderer,
                                cancel = false,
                            )
                            commitCapsuleStroke()
                        } else {
                            stampEmitter.append(batch, dabBuffer)
                            stampEmitter.finish(dabBuffer, cancel = false)
                            commitStroke()
                        }

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
        val beforeTilesRaw = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        renderer.drawInto(
            target = layer.target,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            canvasToFbo = canvasToFboMatrix,
            dabs = dabBuffer,
            colorLinear = strokeColorLinear,
            blendPolicy = brushSettings.blendPolicy,
        )
        layer.version++
        val afterTilesRaw = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        
        val layerId = layer.id
        undoExecutor.execute {
            val beforeTiles = beforeTilesRaw.map { it.compress(tileCompressor) }
            val afterTiles = afterTilesRaw.map { it.compress(tileCompressor) }
            pendingUndoEntries.add(UndoEntry(layerId, beforeTiles, afterTiles))
        }
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
        capsuleEmitter.reset()
        capsuleRenderer?.clearStrokeData()
        capsulePreviewInitialized = false
        stampPreviewInitialized = false
        dabRenderer?.clearStrokeData()
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
                brushSizePx = brushSettings.baseRadiusPx * 2f,
                brushOpacity = brushSettings.opacity,
                activeLayerId = activeLayerId,
                ready = layerStack.canvasWidth > 0 && layerStack.canvasHeight > 0,
            ),
        )
    }

    /**
     * Вызывается только через GLSurfaceView.queueEvent,
     * то есть на GL-потоке с current EGL context.
     */
    fun applyLoadedGrain(
        loaded: LoadedBrushTexture,
        scale: Float,
        canvasLocked: Boolean,
        depth: Float,
        contrast: Float,
    ) {
        val oldTexture = grainTexture
        val newTexture = BrushTexture()

        try {
            newTexture.createFromRgba(
                width = loaded.width,
                height = loaded.height,
                rgba = loaded.rgba,
            )

            capsuleRenderer?.setGrainTexture(
                textureId = newTexture.textureId,
                scale = scale,
                canvasLocked = canvasLocked,
                depth = depth,
                contrast = contrast,
            )
            
            dabRenderer?.setGrainTexture(
                textureId = newTexture.textureId,
                scale = scale,
                depth = depth,
                contrast = contrast,
            )

            grainTexture = newTexture
            grainPath = loaded.path

            oldTexture?.release()
        } catch (error: Throwable) {
            newTexture.release()
            throw error
        }
    }

    fun clearGrainTexture() {
        capsuleRenderer?.clearGrainTexture()
        dabRenderer?.clearGrainTexture()

        grainTexture?.release()
        grainTexture = null
        grainPath = null
    }

    private fun nextLayerName(): String = "Слой ${layerStack.count + 1}"

    private fun renderCapsulePreview() {
        val renderer = capsuleRenderer ?: return

        if (!capsulePreviewInitialized) {
            strokeTarget.clear(
                red = 0f,
                green = 0f,
                blue = 0f,
                alpha = 0f,
            )
            capsulePreviewInitialized = true
        }

        renderer.drawPending(
            target = strokeTarget,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            canvasToClip = canvasToFboMatrix,
            colorLinear = strokeColorLinear,
            blendPolicy = brushSettings.blendPolicy,
        )
    }

    private fun commitCapsuleStroke() {
        val layer = layerStack.activeLayer() ?: return
        val renderer = capsuleRenderer ?: return

        if (layer.isLocked) return
        if (!capsuleEmitter.hasStroke) return
        if (!computeCapsuleDirtyBounds(dirtyBounds)) return

        /*
         * Сначала строим stroke в отдельном offscreen target.
         * Старый слой и новый renderer не используют один FBO для рисования.
         */
        strokeTarget.clear(
            red = 0f,
            green = 0f,
            blue = 0f,
            alpha = 0f,
        )

        renderer.drawAll(
            target = strokeTarget,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            canvasToClip = canvasToFboMatrix,
            colorLinear = strokeColorLinear,
            blendPolicy = brushSettings.blendPolicy,
        )

        val beforeRaw = TileSnapshotCapture.capture(
            layer.target,
            dirtyBounds,
        )

        /*
         * Здесь нужен StrokeBlitter:
         * strokeTarget -> layer.target
         *
         * Он должен использовать:
         * GL_FUNC_ADD
         * GL_ONE, GL_ONE_MINUS_SRC_ALPHA
         * premultiplied source-over
         * opacity = brushSettings.opacity * brushSettings.flow
         */
        strokeBlitter?.blit(
            layer = layer.target,
            geometry = checkNotNull(geometry),
            strokeTextureId = strokeTarget.textureId,
            canvasToFbo = canvasToFboMatrix,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            opacity = (
                brushSettings.opacity * brushSettings.flow
            ).coerceIn(0f, 1f),
        ) ?: error(
            "Capsule commit requires StrokeBlitter"
        )

        layer.version++

        val afterRaw = TileSnapshotCapture.capture(
            layer.target,
            dirtyBounds,
        )

        val layerId = layer.id
        undoExecutor.execute {
            val beforeTiles = beforeRaw.map { it.compress(tileCompressor) }
            val afterTiles = afterRaw.map { it.compress(tileCompressor) }
            pendingUndoEntries.add(UndoEntry(layerId, beforeTiles, afterTiles))
        }

        publishState()
    }

    private fun computeCapsuleDirtyBounds(
        out: IntArray,
    ): Boolean {
        if (!capsuleEmitter.hasBounds) return false

        strokeDirtyRect.clear()
        strokeDirtyRect.include(
            left = capsuleEmitter.minX,
            top = capsuleEmitter.minY,
            right = capsuleEmitter.maxX,
            bottom = capsuleEmitter.maxY,
        )
        strokeDirtyRect.expand(AA_MARGIN_PX)
        strokeDirtyRect.clamp(
            layerStack.canvasWidth.toFloat(),
            layerStack.canvasHeight.toFloat(),
        )
        strokeDirtyRect.toPixelBounds(out)

        return out[2] > out[0] && out[3] > out[1]
    }

    companion object {
        const val DEFAULT_CANVAS_WIDTH = 1500
        const val DEFAULT_CANVAS_HEIGHT = 2000
        private const val AA_MARGIN_PX = 2f
    }
}
