package com.wetinknext.engine.core

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import androidx.compose.ui.graphics.toArgb
import com.wetinknext.BuildConfig
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
import com.wetinknext.engine.input.GestureRouter
import com.wetinknext.engine.input.StrokeInputCapturer
import com.wetinknext.engine.undo.DeflateTileCompressor
import com.wetinknext.engine.undo.RawTileSnapshot
import com.wetinknext.engine.undo.TileSnapshot
import com.wetinknext.engine.undo.TileSnapshotCapture
import com.wetinknext.engine.undo.TileSnapshotRestore
import com.wetinknext.engine.undo.UndoEntry
import com.wetinknext.engine.undo.UndoManager
import com.wetinknext.engine.undo.UndoOperationType
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.HashMap
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
    private val pendingUndoResults = ConcurrentLinkedQueue<UndoJobResult>()
    /** Sequence allocation and application happen only on the GL thread. */
    private var nextUndoSequence = 0L
    private var nextUndoSequenceToApply = 0L
    private val completedUndoResults = HashMap<Long, UndoJobResult>()
    /** Number of commits whose result has not yet been applied to the history. */
    private var pendingUndoCount = 0
    private var undoCompressionFailures = 0
    private var undoStaleResults = 0
    private var undoRestoreFailures = 0
    private var historyEpoch = 0L
    private var glThread: Thread? = null

    private var geometry: CanvasGeometry? = null
    private var compositor: Compositor? = null
    private var dabRenderer: DabRenderer? = null
    private var capsuleRenderer: CapsuleStrokeRenderer? = null
    private var grainTexture: BrushTexture? = null
    private var grainPath: String? = null
    private var shapeTexture: BrushTexture? = null
    private var shapePath: String? = null
    private val strokeTarget = RenderTarget()
    private val canvasToFboMatrix = FloatArray(16)
    private val canvasToClipMatrix = FloatArray(16)

    private var screenWidth = 1
    private var screenHeight = 1
    private val canvasBackdropColor = floatArrayOf(0.08f, 0.09f, 0.12f)
    private val canvasGridColor = floatArrayOf(0.16f, 0.18f, 0.22f)

    private val inputPool = InputBatchPool(batchCount = 64, maxSamplesPerBatch = 256)
    private val inputQueue = ArrayBlockingQueue<InputBatch>(64)
    private val inputCapturer = StrokeInputCapturer(layerStack.camera, inputPool, inputQueue)
    private val undoGestureRequested = AtomicBoolean(false)
    private val redoGestureRequested = AtomicBoolean(false)
    private val resetCameraGestureRequested = AtomicBoolean(false)
    private val gestureRouter = GestureRouter(
        camera = layerStack.camera,
        drawingInput = inputCapturer,
        callbacks = object : GestureRouter.Callbacks {
            override fun cancelDrawing() {
                requestCancelFromInput()
            }

            override fun undoGesture() {
                undoGestureRequested.set(true)
            }

            override fun redoGesture() {
                redoGestureRequested.set(true)
            }

            override fun resetCamera() {
                resetCameraGestureRequested.set(true)
            }

            override fun requestRender() {
                onInputRenderRequested?.invoke()
            }
        },
    )

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
    private val activeStrokeColorLinear = FloatArray(3)
    private val strokeDirtyRect = DirtyRect()
    private val dirtyBounds = IntArray(4)

    private var strokeActive = false
    private var activeStrokeBrush: BrushSettings? = null
    private var strokeBlitter: StrokeBlitter? = null
    private var capsulePreviewInitialized = false
    private var stampPreviewInitialized = false
    private val cancelRequested = AtomicBoolean(false)

    /** Assigned by PaintSurfaceView; invoked on the GL thread. */
    var onStateChange: ((EditorUiState) -> Unit)? = null

    /** Assigned by PaintSurfaceView; invoked from the UI touch thread. */
    var onInputRenderRequested: (() -> Unit)? = null

    fun setOnSecondaryPointerDown(listener: () -> Unit) {
        inputCapturer.onSecondaryPointerDown = listener
    }

    fun onTouchEvent(event: MotionEvent): Boolean = gestureRouter.onTouchEvent(event)

    fun requestCancelFromInput() {
        cancelRequested.set(true)
    }

    /** Fits the document into the current GL surface. Must run on the GL thread. */
    fun resetCamera() {
        layerStack.camera.fitCanvas(
            canvasWidth = layerStack.canvasWidth,
            canvasHeight = layerStack.canvasHeight,
            viewWidth = screenWidth,
            viewHeight = screenHeight,
        )
    }

    /** Applies theme colors to the area that surrounds the document. */
    fun setCanvasBackdrop(backdropArgb: Int, gridArgb: Int) {
        argbToRgb(backdropArgb, canvasBackdropColor)
        argbToRgb(gridArgb, canvasGridColor)
    }

    /** These methods are called from GLSurfaceView.queueEvent by the UI layer. */
    fun undo() {
        if (pendingUndoCount > 0) return
        resetStroke()
        while (true) {
            val entry = undoManager.peekUndo() ?: return
            val layer = layerStack.findLayerById(entry.layerId)
            if (layer == null) {
                undoManager.dropUndo(entry)
                continue
            }
            val restored = TileSnapshotRestore.restore(layer.target, entry.beforeTiles)
            if (!restored) {
                undoRestoreFailures++
                publishState()
                return
            }
            if (!undoManager.commitUndo(entry)) {
                publishState()
                return
            }
            layer.version++
            publishState()
            return
        }
    }

    fun redo() {
        if (pendingUndoCount > 0) return
        resetStroke()
        while (true) {
            val entry = undoManager.peekRedo() ?: return
            val layer = layerStack.findLayerById(entry.layerId)
            if (layer == null) {
                undoManager.dropRedo(entry)
                continue
            }
            val restored = TileSnapshotRestore.restore(layer.target, entry.afterTiles)
            if (!restored) {
                undoRestoreFailures++
                publishState()
                return
            }
            if (!undoManager.commitRedo(entry)) {
                publishState()
                return
            }
            layer.version++
            publishState()
            return
        }
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
        checkOnGlThread()
        resetStroke()
        invalidatePendingUndoHistory()
        val removed = layerStack.removeLayer(id) != null
        if (removed) {
            undoManager.removeEntriesForLayer(id)
            publishState()
        }
        return removed
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

    /** Clears only the editable active layer. The locked background is protected. */
    fun clearActiveLayer(): Boolean {
        checkOnGlThread()
        resetStroke()
        val layer = layerStack.activeLayer() ?: return false
        if (layer.isLocked) return false

        val bounds = fullCanvasBounds()
        val beforeRaw = TileSnapshotCapture.capture(layer.target, bounds)
        layer.clear()
        layer.version++
        val afterRaw = TileSnapshotCapture.capture(layer.target, bounds)
        enqueueUndoCompression(
            layerId = layer.id,
            beforeRaw = beforeRaw,
            afterRaw = afterRaw,
            operation = UndoOperationType.CLEAR_LAYER,
            tag = "clear_layer",
        )
        publishState()
        return true
    }

    /** Mirrors only the camera view; document pixels and undo history stay unchanged. */
    fun toggleCanvasMirror(): Boolean {
        resetStroke()
        val width = layerStack.canvasWidth
        val height = layerStack.canvasHeight
        if (width <= 0 || height <= 0) return false

        // Flip around the document centre in screen space. Toggling the matrix
        // sign alone flips around the camera origin and makes the canvas jump.
        val camera = layerStack.camera
        val current = camera.snapshot()
        val canvasCenterX = width * .5f
        val canvasCenterY = height * .5f
        val before = FloatArray(2)
        val after = FloatArray(2)
        current.canvasToScreen(canvasCenterX, canvasCenterY, before)
        val flipped = current.copy(flipX = !current.flipX)
        flipped.canvasToScreen(canvasCenterX, canvasCenterY, after)
        camera.set(
            flipped.copy(
                translateX = flipped.translateX + before[0] - after[0],
                translateY = flipped.translateY + before[1] - after[1],
            ),
        )
        return true
    }

    /** px = ДИАМЕТР кисти в canvas-пикселях, ровно то, что показывает UI. */
    fun setBrushSize(px: Float) =
        updateBrush(brushSettings.copy(baseRadiusPx = (px * .5f).coerceIn(.5f, 200f)))

    fun setBrushOpacity(opacity: Float) =
        updateBrush(brushSettings.copy(opacity = opacity.coerceIn(0f, 1f)))

    fun setBrushColor(color: androidx.compose.ui.graphics.Color) =
        updateBrush(brushSettings.copy(colorArgb = color.toArgb().toLong() and 0xFFFFFFFFL))

    fun applyBrush(settings: BrushSettings) {
        val nextSettings = settings
            .copy(colorArgb = brushSettings.colorArgb)
            .resolved()
        updateBrush(nextSettings)
    }

    fun setBrushSettings(settings: BrushSettings) {
        updateBrush(settings)
    }

    /** Правка кисти во время штриха больше не отменяет штрих: применяем на следующем DOWN. */
    private fun updateBrush(next: BrushSettings) {
        brushSettings = next.resolved()
        if (!strokeActive) {
            applyBrushToEmitters(brushSettings)
            ColorSpaces.srgb8ToLinear(brushSettings.colorArgb, strokeColorLinear)
        }
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
        if (undoGestureRequested.compareAndSet(true, false)) undo()
        if (redoGestureRequested.compareAndSet(true, false)) redo()
        if (resetCameraGestureRequested.compareAndSet(true, false)) resetCamera()
        drainInput()
        processPendingUndo()

        val frameStrokeBrush = activeStrokeBrush
        if (strokeActive && frameStrokeBrush?.renderMode == BrushRenderMode.RIBBON) {
            renderCapsulePreview(frameStrokeBrush)
        } else if (strokeActive && frameStrokeBrush?.renderMode == BrushRenderMode.STAMP && dabBuffer.count > 0) {
            // No longer clearing and drawing everything here, handled incrementally in drainInput
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(canvasBackdropColor[0], canvasBackdropColor[1], canvasBackdropColor[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        drawBackdropGrid()

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
                            frameStrokeBrush?.renderMode == BrushRenderMode.RIBBON &&
                                capsuleEmitter.hasStroke
                            )
                    )
            ) {
                strokeTarget.textureId
            } else {
                0
            },
            strokeOpacity = if (frameStrokeBrush?.renderMode == BrushRenderMode.STAMP) {
                frameStrokeBrush.opacity.coerceIn(0f, 1f)
            } else {
                ((frameStrokeBrush ?: brushSettings).opacity * (frameStrokeBrush ?: brushSettings).flow)
                    .coerceIn(0f, 1f)
            },
            canvasToClip = canvasToClipMatrix,
        )
    }

    private fun drawBackdropGrid() {
        val cellPx = 28
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(canvasGridColor[0], canvasGridColor[1], canvasGridColor[2], 1f)
        var x = 0
        while (x < screenWidth) {
            GLES30.glScissor(x, 0, 1, screenHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            x += cellPx
        }
        var y = 0
        while (y < screenHeight) {
            GLES30.glScissor(0, y, screenWidth, 1)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            y += cellPx
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    private fun argbToRgb(argb: Int, out: FloatArray) {
        out[0] = ((argb ushr 16) and 0xFF) / 255f
        out[1] = ((argb ushr 8) and 0xFF) / 255f
        out[2] = (argb and 0xFF) / 255f
    }

    private fun processPendingUndo() {
        checkOnGlThread()
        var changed = false
        while (true) {
            val result = pendingUndoResults.poll() ?: break
            if (result.sequence < nextUndoSequenceToApply) {
                discardUndoResult(result)
                pendingUndoCount = (pendingUndoCount - 1).coerceAtLeast(0)
                changed = true
            } else {
                completedUndoResults.put(result.sequence, result)?.let(::disposeReadyResult)
            }
        }

        while (true) {
            val result = completedUndoResults.remove(nextUndoSequenceToApply) ?: break
            applyUndoResult(result)
            pendingUndoCount = (pendingUndoCount - 1).coerceAtLeast(0)
            nextUndoSequenceToApply++
            changed = true
        }
        if (changed) publishState()
    }

    private fun applyUndoResult(result: UndoJobResult) {
        when (result) {
            is UndoJobResult.Ready -> {
                if (!isUndoResultCurrent(result.epoch, historyEpoch)) {
                    result.entry.dispose()
                    undoStaleResults++
                } else {
                    undoManager.push(result.entry)
                }
            }
            is UndoJobResult.Failed -> {
                undoCompressionFailures++
                if (BuildConfig.DEBUG) Log.e("TileUndo", "Undo compression failed", result.error)
            }
        }
    }

    private fun discardUndoResult(result: UndoJobResult) {
        if (result is UndoJobResult.Ready) result.entry.dispose()
        undoStaleResults++
    }

    private fun disposeReadyResult(result: UndoJobResult) {
        if (result is UndoJobResult.Ready) result.entry.dispose()
    }

    /**
     * Schedules compression after a successful GL commit. Each invocation publishes one
     * Ready or Failed result, including when the executor rejects the task.
     */
    private fun enqueueUndoCompression(
        layerId: Long,
        beforeRaw: List<RawTileSnapshot>,
        afterRaw: List<RawTileSnapshot>,
        operation: UndoOperationType = UndoOperationType.TILE_EDIT,
        tag: String = "stroke",
    ) {
        checkOnGlThread()
        val epoch = historyEpoch
        val sequence = allocateUndoSequence()
        pendingUndoCount++
        publishState()
        if (pendingUndoCount >= MAX_PENDING_UNDO_JOBS) {
            compressUndoSynchronously(
                epoch = epoch,
                sequence = sequence,
                layerId = layerId,
                beforeRaw = beforeRaw,
                afterRaw = afterRaw,
                operation = operation,
                tag = tag,
            )
            processPendingUndo()
            return
        }

        submitUndoCompression(
            epoch = epoch,
            sequence = sequence,
            layerId = layerId,
            beforeRaw = beforeRaw,
            afterRaw = afterRaw,
            operation = operation,
            tag = tag,
        )
    }

    private fun allocateUndoSequence(): Long {
        checkOnGlThread()
        val sequence = nextUndoSequence
        nextUndoSequence++
        return sequence
    }

    private fun submitUndoCompression(
        epoch: Long,
        sequence: Long,
        layerId: Long,
        beforeRaw: List<RawTileSnapshot>,
        afterRaw: List<RawTileSnapshot>,
        operation: UndoOperationType,
        tag: String,
    ) {
        try {
            undoExecutor.execute {
                val result = runCatching {
            val beforeTiles = beforeRaw.map { it.compress(tileCompressor) }
            val afterTiles = afterRaw.map { it.compress(tileCompressor) }
            UndoJobResult.Ready(
                epoch = epoch,
                sequence = sequence,
                entry = UndoEntry(
                    layerId = layerId,
                    beforeTiles = beforeTiles,
                    afterTiles = afterTiles,
                    operation = operation,
                    tag = tag,
                ),
            )
                }.getOrElse { error ->
                    UndoJobResult.Failed(epoch = epoch, sequence = sequence, error = error)
                }
                pendingUndoResults.add(result)
                onInputRenderRequested?.invoke()
            }
        } catch (error: RejectedExecutionException) {
            pendingUndoResults.add(
                UndoJobResult.Failed(epoch = epoch, sequence = sequence, error = error),
            )
            onInputRenderRequested?.invoke()
        }
    }

    /** Emergency backpressure only: prevents raw tile snapshots from growing without limit. */
    private fun compressUndoSynchronously(
        epoch: Long,
        sequence: Long,
        layerId: Long,
        beforeRaw: List<RawTileSnapshot>,
        afterRaw: List<RawTileSnapshot>,
        operation: UndoOperationType,
        tag: String,
    ) {
        val result = try {
            UndoJobResult.Ready(
            epoch = epoch,
            sequence = sequence,
            entry = UndoEntry(
                    layerId = layerId,
                    beforeTiles = beforeRaw.map { it.compress(tileCompressor) },
                    afterTiles = afterRaw.map { it.compress(tileCompressor) },
                    operation = operation,
                    tag = tag,
                ),
            )
        } catch (error: Throwable) {
            UndoJobResult.Failed(epoch = epoch, sequence = sequence, error = error)
        }
        pendingUndoResults.add(result)
    }

    private fun fullCanvasBounds(): IntArray = intArrayOf(
        0,
        0,
        layerStack.canvasWidth,
        layerStack.canvasHeight,
    )

    /** Invalidates worker results created before a destructive document change. */
    private fun invalidatePendingUndoHistory() {
        checkOnGlThread()
        historyEpoch++
        nextUndoSequenceToApply = nextUndoSequence
        clearCompletedUndoResults()
    }

    private fun clearCompletedUndoResults() {
        completedUndoResults.values.forEach(::disposeReadyResult)
        completedUndoResults.clear()
    }

    private fun shutdownUndoExecutor() {
        undoExecutor.shutdownNow()
        try {
            undoExecutor.awaitTermination(100, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        while (true) {
            val result = pendingUndoResults.poll() ?: break
            disposeReadyResult(result)
        }
        clearCompletedUndoResults()
        pendingUndoCount = 0
        // Results from the disposed executor must never block the next GL context.
        nextUndoSequenceToApply = nextUndoSequence
    }

    /** Safe to call from the UI thread; cancellation is executed on the next GL frame. */
    fun cancelActiveStroke() {
        cancelRequested.set(true)
    }

    /** Must run on the GL thread while the context is still current. */
    fun releaseGlObjects() {
        checkOnGlThread()
        invalidatePendingUndoHistory()
        discardPendingInput()
        resetStroke()
        undoManager.clear()
        shutdownUndoExecutor()
        dabRenderer?.release()
        dabRenderer = null
        grainTexture?.release()
        grainTexture = null
        grainPath = null
        shapeTexture?.release()
        shapeTexture = null
        shapePath = null
        capsuleRenderer?.release()
        capsuleRenderer = null
        strokeBlitter?.release()
        strokeBlitter = null
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
                            val strokeBrush = brushSettings.resolved()
                            activeStrokeBrush = strokeBrush
                            ColorSpaces.srgb8ToLinear(strokeBrush.colorArgb, activeStrokeColorLinear)
                            activeStrokeColorLinear.copyInto(strokeColorLinear)
                            strokeActive = true

                            if (strokeBrush.renderMode == BrushRenderMode.RIBBON) {
                                capsuleEmitter.begin(
                                    batch = batch,
                                    out = checkNotNull(capsuleRenderer),
                                    strokeSettings = strokeBrush,
                                )
                            } else {
                                dabRenderer?.beginStroke()
                                stampEmitter.begin(batch, dabBuffer, strokeBrush)
                                strokeTarget.clear(0f, 0f, 0f, 0f)
                                stampPreviewInitialized = true

                                drawPendingStampPreview("DOWN")
                            }
                        }
                    }

                    InputAction.MOVE -> if (strokeActive) {
                        val strokeBrush = activeStrokeBrush
                            ?: error("Missing active stroke snapshot")
                        if (strokeBrush.renderMode == BrushRenderMode.RIBBON) {
                            capsuleEmitter.append(
                                batch = batch,
                                out = checkNotNull(capsuleRenderer),
                            )
                        } else {
                            stampEmitter.append(batch, dabBuffer)
                            drawPendingStampPreview("MOVE")
                        }
                    }

                    InputAction.UP -> if (strokeActive) {
                        val strokeBrush = activeStrokeBrush
                            ?: error("Missing active stroke snapshot")
                        if (strokeBrush.renderMode == BrushRenderMode.RIBBON) {
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
                            commitCapsuleStroke(strokeBrush)
                        } else {
                            stampEmitter.append(batch, dabBuffer)
                            stampEmitter.finish(dabBuffer, cancel = false)
                            commitStroke(strokeBrush)
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

    private fun commitStroke(strokeBrush: BrushSettings) {
        val layer = layerStack.activeLayer() ?: return
        if (layer.isLocked || dabBuffer.count == 0) return
        if (!computeDirtyPixelBounds(dirtyBounds)) return
        expandBoundsToTiles(dirtyBounds, layerStack.canvasWidth, layerStack.canvasHeight)
        val renderer = dabRenderer ?: return
        val blitter = strokeBlitter ?: return

        // Complete the isolated local-coverage mask before it is composited.
        // Global brush opacity is deliberately applied only by the blit.
        drawPendingStampPreview("UP")

        // Both snapshots use the actual layer format (RGBA8 or RGBA16F).
        val beforeTilesRaw = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        blitter.blit(
            layer = layer.target,
            geometry = checkNotNull(geometry),
            strokeTextureId = strokeTarget.textureId,
            canvasToFbo = canvasToFboMatrix,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            opacity = strokeBrush.opacity.coerceIn(0f, 1f),
        )
        GLES30.glFlush()
        layer.version++
        val afterTilesRaw = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        
        enqueueUndoCompression(
            layerId = layer.id,
            beforeRaw = beforeTilesRaw,
            afterRaw = afterTilesRaw,
        )
    }

    /** Returns a clamped pixel region covering the exact emitted dabs and their AA fringe. */
    private fun computeDirtyPixelBounds(out: IntArray): Boolean {
        if (dabBuffer.count == 0) return false
        strokeDirtyRect.clear()
        dabBuffer.includeDirtyRect(
            rect = strokeDirtyRect,
            extraMargin = STAMP_DIRTY_MARGIN_PX,
        )
        strokeDirtyRect.clamp(layerStack.canvasWidth.toFloat(), layerStack.canvasHeight.toFloat())
        strokeDirtyRect.toPixelBounds(out)
        return out[2] > out[0] && out[3] > out[1]
    }

    /** Uses a stable whole-tile set for the before/after snapshot transaction. */
    private fun expandBoundsToTiles(
        bounds: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val tileSize = TileSnapshot.TILE_SIZE
        bounds[0] = (bounds[0] / tileSize) * tileSize
        bounds[1] = (bounds[1] / tileSize) * tileSize
        bounds[2] = ((bounds[2] + tileSize - 1) / tileSize) * tileSize
        bounds[3] = ((bounds[3] + tileSize - 1) / tileSize) * tileSize
        bounds[0] = bounds[0].coerceIn(0, canvasWidth)
        bounds[1] = bounds[1].coerceIn(0, canvasHeight)
        bounds[2] = bounds[2].coerceIn(0, canvasWidth)
        bounds[3] = bounds[3].coerceIn(0, canvasHeight)
    }

    private fun drawPendingStampPreview(phase: String) {
        val renderer = dabRenderer ?: return
        val strokeBrush = activeStrokeBrush
            ?: error("Missing active stroke snapshot")
        check(renderer.uploadedCount <= dabBuffer.count) {
            "Stamp preview rewound: uploaded=${renderer.uploadedCount}, dabs=${dabBuffer.count}"
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                BRUSH_DIAG_TAG,
                "phase=$phase dabs=${dabBuffer.count} pending=${dabBuffer.count - renderer.uploadedCount}",
            )
        }
        renderer.drawPendingInto(
            target = strokeTarget,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            canvasToFbo = canvasToFboMatrix,
            dabs = dabBuffer,
            colorLinear = activeStrokeColorLinear,
            blendPolicy = strokeBrush.blendPolicy,
            strokeOpacity = 1f,
        )
        check(renderer.uploadedCount == dabBuffer.count) {
            "Stamp preview did not consume all dabs: uploaded=${renderer.uploadedCount}, dabs=${dabBuffer.count}"
        }
    }

    private fun resetStroke() {
        strokeActive = false
        activeStrokeBrush = null
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
                canUndo = undoManager.canUndo && pendingUndoCount == 0,
                canRedo = undoManager.canRedo && pendingUndoCount == 0,
                brushSizePx = brushSettings.baseRadiusPx * 2f,
                brushOpacity = brushSettings.opacity,
                activeLayerId = activeLayerId,
                ready = layerStack.canvasWidth > 0 && layerStack.canvasHeight > 0,
                undoDiagnostics = UndoDiagnostics(
                    pendingJobs = pendingUndoCount,
                    staleResults = undoStaleResults,
                    compressionFailures = undoCompressionFailures,
                    restoreFailures = undoRestoreFailures,
                    memoryBytes = undoManager.memoryBytes,
                ),
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
                canvasLocked = canvasLocked,
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

    /** Must run on the GL thread. Shape masks affect STAMP dabs only. */
    fun applyLoadedShape(
        loaded: LoadedBrushTexture,
        reverse: Boolean,
        rgbToAlpha: Boolean,
    ) {
        val oldTexture = shapeTexture
        val newTexture = BrushTexture()

        try {
            newTexture.createFromRgba(loaded.width, loaded.height, loaded.rgba)
            dabRenderer?.setShapeTexture(newTexture.textureId, reverse, rgbToAlpha)
            shapeTexture = newTexture
            shapePath = loaded.path
            oldTexture?.release()
        } catch (error: Throwable) {
            newTexture.release()
            throw error
        }
    }

    fun clearShapeTexture() {
        dabRenderer?.clearShapeTexture()
        shapeTexture?.release()
        shapeTexture = null
        shapePath = null
    }

    private fun nextLayerName(): String = "Слой ${layerStack.count + 1}"

    private fun renderCapsulePreview(strokeBrush: BrushSettings) {
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
            colorLinear = activeStrokeColorLinear,
            blendPolicy = strokeBrush.blendPolicy,
        )
    }

    private fun commitCapsuleStroke(strokeBrush: BrushSettings) {
        val layer = layerStack.activeLayer() ?: return
        val renderer = capsuleRenderer ?: return

        if (layer.isLocked) return
        if (!capsuleEmitter.hasStroke) return
        if (!computeCapsuleDirtyBounds(dirtyBounds)) return
        expandBoundsToTiles(dirtyBounds, layerStack.canvasWidth, layerStack.canvasHeight)

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
            colorLinear = activeStrokeColorLinear,
            blendPolicy = strokeBrush.blendPolicy,
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
         * opacity = the active stroke's snapshotted brush opacity
         */
        strokeBlitter?.blit(
            layer = layer.target,
            geometry = checkNotNull(geometry),
            strokeTextureId = strokeTarget.textureId,
            canvasToFbo = canvasToFboMatrix,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            opacity = strokeBrush.opacity.coerceIn(0f, 1f),
        ) ?: error(
            "Capsule commit requires StrokeBlitter"
        )
        GLES30.glFlush()

        layer.version++

        val afterRaw = TileSnapshotCapture.capture(
            layer.target,
            dirtyBounds,
        )

        enqueueUndoCompression(
            layerId = layer.id,
            beforeRaw = beforeRaw,
            afterRaw = afterRaw,
        )
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
        private const val BRUSH_DIAG_TAG = "BrushDiag"
        const val DEFAULT_CANVAS_WIDTH = 1500
        const val DEFAULT_CANVAS_HEIGHT = 2000
        private const val AA_MARGIN_PX = 4f
        private const val STAMP_DIRTY_MARGIN_PX = 4f
        private const val MAX_PENDING_UNDO_JOBS = 8
    }
}
