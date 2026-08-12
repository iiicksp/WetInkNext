package com.wetinknext.engine.core

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import androidx.compose.ui.graphics.toArgb
import com.wetinknext.BuildConfig
import com.wetinknext.domain.document.LayerDocument
import com.wetinknext.domain.document.ProjectDocument
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
import com.wetinknext.engine.canvas.LinearPresentRenderer
import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.canvas.PaintLayer
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
import com.wetinknext.engine.persistence.LayerTileStore
import com.wetinknext.engine.thumbnail.ThumbnailBuildResult
import com.wetinknext.engine.thumbnail.ThumbnailBuildScheduler
import com.wetinknext.engine.thumbnail.ThumbnailRenderer
import com.wetinknext.engine.undo.TileSnapshotRestore
import com.wetinknext.engine.undo.UndoManager
import com.wetinknext.engine.undo.UndoOperationType
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The P6 render-thread owner. Active dabs are accumulated only in strokeTarget;
 * UP snapshots and merges them into the selected PaintLayer atomically.
 */
class EngineRenderer(
    private var projectDocument: ProjectDocument = ProjectDocument.newUntitled(),
    private val initialLayerTiles: Map<Long, ByteArray> = emptyMap(),
    private val thumbnailOutputDirectory: File? = null,
) : GLSurfaceView.Renderer {
    private var caps: GlCaps? = null
    private val layerStack = LayerStack()
    private val undoManager = UndoManager()
    private val layerTileStore = LayerTileStore(initialLayerTiles)
    private var documentSession: DocumentSession? = null
    private val undoPipeline = UndoCompressionPipeline(
        requestRender = { onInputRenderRequested?.invoke() },
    )
    private var undoRestoreFailures = 0
    private var glThread: Thread? = null
    /**
     * Preview files may finish decoding before GLSurfaceView calls
     * [onSurfaceCreated]. Keep them as plain JVM data until the document's
     * LayerStack has been created on the GL thread.
     */
    private var deferredRestoredLayerThumbnails: Map<Long, File> = emptyMap()

    private var geometry: CanvasGeometry? = null
    private var compositor: Compositor? = null
    private var presentRenderer: LinearPresentRenderer? = null
    private val thumbnailCapture = ThumbnailCapture()
    private val layerThumbnailPaths = mutableMapOf<Long, String>()
    /** Last layer version for which a WebP preview was successfully published. */
    private val layerThumbnailVersions = mutableMapOf<Long, Long>()
    private val thumbnailScheduler = thumbnailOutputDirectory?.let { outputDirectory ->
        ThumbnailBuildScheduler(
            outputDirectory = outputDirectory,
            renderer = ThumbnailRenderer(),
            requestGlFrame = { onInputRenderRequested?.invoke() },
            onSaved = { result -> onThumbnailBuildSaved?.invoke(result) },
            onFailure = { error ->
                if (BuildConfig.DEBUG) Log.e(THUMBNAIL_TAG, "Thumbnail build failed", error)
            },
        )
    }
    private var dabRenderer: DabRenderer? = null
    private var capsuleRenderer: CapsuleStrokeRenderer? = null
    private var grainTexture: BrushTexture? = null
    private var grainPath: String? = null
    private var shapeTexture: BrushTexture? = null
    private var shapePath: String? = null
    private val strokeTarget = RenderTarget()
    /** Canvas-sized premultiplied linear composition, presented only once per frame. */
    private val compositeTarget = RenderTarget()
    private val canvasToFboMatrix = FloatArray(16)
    private val strokeCommitter = StrokeCommitter(
        strokeTarget = strokeTarget,
        canvasToFbo = canvasToFboMatrix,
        undoPipeline = undoPipeline,
        undoManager = undoManager,
        onTilesCommitted = { layer, tiles ->
            layerTileStore.markDirty(layer.id, tiles)
            documentSession?.markLayerDirty(layer.id)
            publishDirtyTiles()
            markLayerThumbnailDirty(layer.id)
        },
        onLayerCleared = { layer ->
            layerTileStore.markLayerCleared(layer.id)
            documentSession?.markLayerDirty(layer.id)
            publishDirtyTiles()
            markLayerThumbnailDirty(layer.id)
        },
    )
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

    /** Metadata-only snapshot for persistence; invoked on the GL thread. */
    var onProjectDocumentChange: ((ProjectDocument) -> Unit)? = null

    /** Called only after document metadata and all stored layer payloads reached the GPU. */
    var onDocumentSessionLoaded: (() -> Unit)? = null
    var onDirtyLayerTiles: ((ProjectDocument, Map<Long, ByteArray>, Map<Long, Set<com.wetinknext.engine.undo.TileCoord>>) -> Unit)? = null
    var onThumbnailCaptured: ((ThumbnailCapture.Rgba) -> Unit)? = null
    /** Posted on the UI thread after the scheduler published the current previews. */
    var onThumbnailBuildSaved: ((ThumbnailBuildResult) -> Unit)? = null

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
    private var canvasBackdropMode = CanvasBackdropMode.GRID

    fun setCanvasBackdrop(backdropArgb: Int, gridArgb: Int, mode: CanvasBackdropMode) {
        argbToRgb(backdropArgb, canvasBackdropColor)
        argbToRgb(gridArgb, canvasGridColor)
        canvasBackdropMode = mode
    }

    /** These methods are called from GLSurfaceView.queueEvent by the UI layer. */
    fun undo() {
        if (undoPipeline.pendingCount > 0) return
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
            persistRestoredTiles(layer, entry.beforeTiles)
            updateProjectDocument { it }
            publishState()
            return
        }
    }

    fun redo() {
        if (undoPipeline.pendingCount > 0) return
        resetStroke()
        while (true) {
            val entry = undoManager.peekRedo() ?: return
            val layer = layerStack.findLayerById(entry.layerId)
            if (layer == null) {
                undoManager.dropRedo(entry)
                continue
            }
            val restored = if (entry.operation == UndoOperationType.CLEAR_LAYER) {
                layer.clear()
                true
            } else {
                TileSnapshotRestore.restore(layer.target, entry.afterTiles)
            }
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
            if (entry.operation == UndoOperationType.CLEAR_LAYER) {
                layerTileStore.markLayerCleared(layer.id)
                documentSession?.markLayerDirty(layer.id)
                publishDirtyTiles()
                markLayerThumbnailDirty(layer.id)
            } else {
                persistRestoredTiles(layer, entry.afterTiles)
            }
            updateProjectDocument { it }
            publishState()
            return
        }
    }

    fun setActiveLayer(id: Long): Boolean {
        resetStroke()
        return layerStack.setActive(id).also { changed ->
            if (changed) {
                updateProjectDocument { it.copy(activeLayerId = id) }
                publishState()
            }
        }
    }

    fun addLayer(): Long = addLayer(nextLayerName())

    fun addLayer(name: String): Long {
        resetStroke()
        val layer = layerStack.addLayer(name)
        updateProjectDocument { document ->
            document.copy(
                layers = document.layers + layerDocumentFrom(layer),
                activeLayerId = layer.id,
            )
        }
        captureThumbnail(listOf(layer.id))
        publishState()
        return layer.id
    }

    /** Creates an editable pixel-identical copy directly above [id]. */
    fun duplicateLayer(id: Long): Long? {
        checkOnGlThread()
        resetStroke()
        val source = layerStack.findLayerById(id) ?: return null
        val sourceIndex = layerStack.indexOfLayer(id)
        if (sourceIndex < 0 || !source.created) return null

        val duplicate = layerStack.addLayer(
            name = nextDuplicateLayerName(source.name),
            insertAt = sourceIndex + 1,
        ).also {
            it.isVisible = source.isVisible
            it.isLocked = false
            it.opacity = source.opacity
            it.blendMode = source.blendMode
            it.version = source.version
        }

        // Same-sized RenderTargets have the same format, so a framebuffer
        // blit preserves premultiplied pixels exactly without a CPU readback.
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, source.target.framebufferId)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, duplicate.target.framebufferId)
        GLES30.glBlitFramebuffer(
            0, 0, layerStack.canvasWidth, layerStack.canvasHeight,
            0, 0, layerStack.canvasWidth, layerStack.canvasHeight,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_NEAREST,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GlCheck.noError("Duplicate layer")

        val raw = com.wetinknext.engine.undo.TileSnapshotCapture.capture(
            target = duplicate.target,
            bounds = intArrayOf(0, 0, layerStack.canvasWidth, layerStack.canvasHeight),
        )
        layerTileStore.markDirty(duplicate.id, raw)
        documentSession?.markLayerDirty(duplicate.id)
        updateProjectDocument { it.copy(activeLayerId = duplicate.id, layers = layerStack.allLayers().map(::layerDocumentFrom)) }
        publishDirtyTiles()
        captureThumbnail(listOf(duplicate.id))
        publishState()
        return duplicate.id
    }

    fun removeLayer(id: Long): Boolean {
        checkOnGlThread()
        resetStroke()
        invalidatePendingUndoHistory()
        val removed = layerStack.removeLayer(id) != null
        if (removed) {
            undoManager.removeEntriesForLayer(id)
            layerThumbnailPaths.remove(id)
            layerThumbnailVersions.remove(id)
            thumbnailScheduler?.removeLayer(id)
            updateProjectDocument { document ->
                document.copy(
                    layers = document.layers.filterNot { it.id == id },
                    activeLayerId = layerStack.activeLayerId,
                )
            }
            captureThumbnail()
            publishState()
        }
        return removed
    }

    /** Moves one layer in the document order and rebuilds only the project preview. */
    fun moveLayer(id: Long, delta: Int): Boolean {
        checkOnGlThread()
        resetStroke()
        val moved = layerStack.moveLayer(id, delta)
        if (moved) {
            updateProjectDocument { it }
            captureThumbnail()
            publishState()
        }
        return moved
    }

    fun setLayerVisible(id: Long, visible: Boolean) {
        resetStroke()
        val layer = layerStack.findLayerById(id) ?: return
        layer.isVisible = visible
        syncLayerDocument(layer)
        captureThumbnail()
        publishState()
    }

    fun setLayerOpacity(id: Long, opacity: Float) {
        val layer = layerStack.findLayerById(id) ?: return
        layer.opacity = opacity.coerceIn(0f, 1f)
        syncLayerDocument(layer)
        captureThumbnail(listOf(layer.id))
        publishState()
    }

    /** Clears only the editable active layer. The locked background is protected. */
    fun clearActiveLayer(): Boolean {
        checkOnGlThread()
        resetStroke()
        val layer = layerStack.activeLayer() ?: return false
        val cleared = strokeCommitter.clearLayer(
            layer = layer,
            canvasWidth = layerStack.canvasWidth,
            canvasHeight = layerStack.canvasHeight,
        )
        if (cleared) {
            updateProjectDocument { it }
            publishState()
        }
        return cleared
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
        undoPipeline.ensureRunning()
        thumbnailScheduler?.ensureRunning()
        val nextCaps = GlCaps.query()
        caps = nextCaps
        documentSession = DocumentSession(
            document = projectDocument,
            layerStack = layerStack,
            undoManager = undoManager,
            layerTiles = initialLayerTiles,
        ).also { it.loadIntoGpu(nextCaps) }
        if (deferredRestoredLayerThumbnails.isNotEmpty()) {
            val previews = deferredRestoredLayerThumbnails
            deferredRestoredLayerThumbnails = emptyMap()
            installRestoredLayerThumbnails(previews)
        }
        strokeTarget.create(
            projectDocument.width,
            projectDocument.height,
            nextCaps.supportsHalfFloatColorBuffer,
        )
        strokeTarget.clear(0f, 0f, 0f, 0f)
        compositeTarget.create(
            projectDocument.width,
            projectDocument.height,
            nextCaps.supportsHalfFloatColorBuffer,
        )
        compositeTarget.clear(0f, 0f, 0f, 0f)
        ViewTransform.buildCanvasToFbo(
            projectDocument.width.toFloat(),
            projectDocument.height.toFloat(),
            canvasToFboMatrix,
        )
        geometry = CanvasGeometry().also {
            it.create(projectDocument.width, projectDocument.height)
        }
        compositor = Compositor().also { it.create() }
        presentRenderer = LinearPresentRenderer().also { it.create() }
        dabRenderer = DabRenderer(dabBuffer.capacity).also { it.create() }
        capsuleRenderer = CapsuleStrokeRenderer().also {
            it.create()
        }
        strokeBlitter = StrokeBlitter().also { it.create() }
        // Build both the gallery preview and the individual layer previews
        // once the complete GPU session is available.
        captureThumbnail(layerStack.allLayers().map(PaintLayer::id))
        onDocumentSessionLoaded?.invoke()
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
        if (undoPipeline.process(undoManager)) publishState()
        thumbnailScheduler?.processCompleted()
        thumbnailScheduler?.buildIfNeeded(layerStack)

        val frameStrokeBrush = activeStrokeBrush
        if (strokeActive && frameStrokeBrush?.renderMode == BrushRenderMode.RIBBON) {
            renderCapsulePreview(frameStrokeBrush)
        } else if (strokeActive && frameStrokeBrush?.renderMode == BrushRenderMode.STAMP && dabBuffer.count > 0) {
            // No longer clearing and drawing everything here, handled incrementally in drainInput
        }

        // All document layers and the active stroke preview blend in linear,
        // premultiplied RGBA before the one sRGB conversion at presentation.
        compositeTarget.clear(0f, 0f, 0f, 0f)
        GLES30.glViewport(0, 0, layerStack.canvasWidth, layerStack.canvasHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        val currentGeometry = geometry ?: return
        val currentCompositor = compositor ?: return
        val currentPresentRenderer = presentRenderer ?: return
        currentCompositor.render(
            destination = compositeTarget,
            geometry = currentGeometry,
            layers = layerStack,
            activeLayerId = layerStack.activeLayerId,
            strokeTextureId = if (
                strokeActive &&
                (dabBuffer.count > 0 || (frameStrokeBrush?.renderMode == BrushRenderMode.RIBBON && capsuleEmitter.hasStroke))
            ) strokeTarget.textureId else 0,
            strokeOpacity = frameStrokeBrush?.opacity?.coerceIn(0f, 1f) ?: 1f,
            canvasToClip = canvasToFboMatrix,
        )

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(canvasBackdropColor[0], canvasBackdropColor[1], canvasBackdropColor[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        drawBackdropPattern()
        layerStack.camera.snapshot().buildCanvasToClip(
            screenWidth.toFloat(),
            screenHeight.toFloat(),
            canvasToClipMatrix,
        )
        // Explicitly restore the default framebuffer before the final sRGB pass.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        currentPresentRenderer.render(
            geometry = currentGeometry,
            sourceTextureId = compositeTarget.textureId,
            canvasToClipMatrix = canvasToClipMatrix,
            canvasWidth = layerStack.canvasWidth,
            canvasHeight = layerStack.canvasHeight,
            viewportWidth = screenWidth,
            viewportHeight = screenHeight,
        )
    }

    private fun drawBackdropPattern() {
        if (canvasBackdropMode == CanvasBackdropMode.SOLID) return
        val cellPx = 28
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(canvasGridColor[0], canvasGridColor[1], canvasGridColor[2], 1f)
        if (canvasBackdropMode == CanvasBackdropMode.CHECKERBOARD) {
            var row = 0
            var y = 0
            while (y < screenHeight) {
                var column = row and 1
                var x = 0
                while (x < screenWidth) {
                    if (column == 0) {
                        GLES30.glScissor(x, y, cellPx, cellPx)
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    }
                    column = 1 - column
                    x += cellPx
                }
                row++
                y += cellPx
            }
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            return
        }
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

    /** Invalidates worker results created before a destructive document change. */
    private fun invalidatePendingUndoHistory() {
        checkOnGlThread()
        undoPipeline.invalidate()
    }

    private fun shutdownUndoExecutor() {
        undoPipeline.shutdown()
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
        presentRenderer?.release()
        presentRenderer = null
        thumbnailScheduler?.shutdown()
        thumbnailCapture.release()
        documentSession = null
        geometry?.release()
        geometry = null
        strokeTarget.release()
        compositeTarget.release()
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
        val blitter = strokeBlitter ?: return
        val strokeGeometry = geometry ?: return

        // Complete the isolated local-coverage mask before it is composited.
        // Global brush opacity is deliberately applied only by the blit.
        drawPendingStampPreview("UP")

        if (strokeCommitter.commit(
            layer = layer,
            geometry = strokeGeometry,
            blitter = blitter,
            dirtyBounds = dirtyBounds,
            canvasWidth = layerStack.canvasWidth,
            canvasHeight = layerStack.canvasHeight,
            opacity = strokeBrush.opacity,
        )) {
            publishState()
        }
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

    /**
     * Builds the persisted metadata from the GL-owned runtime state. UI state
     * is never treated as the document source of truth.
     */
    fun buildDocumentSnapshot(): ProjectDocument {
        checkOnGlThread()
        return projectDocument.copy(
            updatedAt = System.currentTimeMillis(),
            activeLayerId = layerStack.activeLayerId,
            layers = layerStack.allLayers().map(::layerDocumentFrom),
        )
    }

    private fun updateProjectDocument(
        transform: (ProjectDocument) -> ProjectDocument,
    ) {
        projectDocument = transform(projectDocument)
        projectDocument = buildDocumentSnapshot()
        documentSession?.markProjectDirty()
        onProjectDocumentChange?.invoke(projectDocument)
    }

    fun acknowledgeSavedTiles(saved: Map<Long, Set<com.wetinknext.engine.undo.TileCoord>>) {
        layerTileStore.acknowledge(saved)
        documentSession?.markSaved(saved.keys)
    }

    private fun publishDirtyTiles() {
        val dirty = layerTileStore.takeDirty()
        if (dirty.isEmpty()) return
        projectDocument = buildDocumentSnapshot()
        documentSession?.markProjectDirty()
        onDirtyLayerTiles?.invoke(projectDocument, layerTileStore.payloadsForDirty(dirty), dirty)
    }

    /** Re-captures restored GPU tiles so Undo/Redo is persisted like a stroke. */
    private fun persistRestoredTiles(
        layer: PaintLayer,
        tiles: List<com.wetinknext.engine.undo.TileSnapshot>,
    ) {
        if (tiles.isEmpty()) return
        val left = tiles.minOf { it.pixelLeft }
        val top = tiles.minOf { it.pixelTop }
        val right = tiles.maxOf { it.pixelLeft + it.pixelWidth }
        val bottom = tiles.maxOf { it.pixelTop + it.pixelHeight }
        val raw = com.wetinknext.engine.undo.TileSnapshotCapture.capture(
            target = layer.target,
            bounds = intArrayOf(left, top, right, bottom),
        )
        layerTileStore.markDirty(layer.id, raw)
        publishDirtyTiles()
        markLayerThumbnailDirty(layer.id)
    }

    private fun captureThumbnail(dirtyLayerIds: Collection<Long> = emptyList()) {
        if (caps == null || geometry == null || compositor == null) return
        val scheduler = thumbnailScheduler
        if (scheduler != null) {
            scheduler.markDirty(projectDirty = true, dirtyLayerIds = dirtyLayerIds)
            onInputRenderRequested?.invoke()
        } else {
            onThumbnailCaptured?.invoke(thumbnailCapture.capture(layerStack))
        }
    }

    /** Called only after a successful GPU edit of [layerId]. */
    private fun markLayerThumbnailDirty(layerId: Long) {
        val scheduler = thumbnailScheduler
        if (scheduler != null) {
            scheduler.markLayerDirty(layerId)
            scheduler.markProjectDirty()
            onInputRenderRequested?.invoke()
        } else {
            captureThumbnail()
        }
    }

    /**
     * Records published preview files on the GL thread. Scheduler generations
     * ensure every listed layer still has the pixels used to create its image.
     */
    fun applyThumbnailBuild(result: ThumbnailBuildResult) {
        checkOnGlThread()
        result.layerPreviews.forEach { (layerId, file) ->
            layerStack.findLayerById(layerId)?.let { layer ->
                layerThumbnailPaths[layerId] = file.absolutePath
                layerThumbnailVersions[layerId] = layer.version
            }
        }
        layerThumbnailPaths.keys.retainAll(layerStack.allLayers().map(PaintLayer::id).toSet())
        layerThumbnailVersions.keys.retainAll(layerStack.allLayers().map(PaintLayer::id).toSet())
        // layerDocumentFrom copies the current PaintLayer.version into
        // thumbnailVersion, and updateProjectDocument atomically publishes the
        // resulting document snapshot to the autosave bridge.
        updateProjectDocument { it }
        publishState()
    }

    /**
     * Makes WebP previews restored from the persisted project immediately
     * available to Compose. An older persisted preview may never replace a
     * newer runtime image after a stroke has incremented the layer version.
     */
    fun installRestoredLayerThumbnails(previews: Map<Long, File>) {
        // queueEvent() is allowed to run before Renderer.onSurfaceCreated().
        // At that time no GL owner or LayerStack exists yet, so checking the
        // thread here used to crash application startup.
        if (glThread == null) {
            deferredRestoredLayerThumbnails = previews
            return
        }
        checkOnGlThread()
        var changed = false
        previews.forEach { (layerId, file) ->
            val layer = layerStack.findLayerById(layerId) ?: return@forEach
            val storedVersion = projectDocument.layers
                .firstOrNull { it.id == layerId }
                ?.thumbnailVersion
                ?: return@forEach
            if (file.isFile && layer.version == storedVersion && layerThumbnailVersions[layerId] == null) {
                layerThumbnailPaths[layerId] = file.absolutePath
                layerThumbnailVersions[layerId] = storedVersion
                changed = true
            }
        }
        if (changed) publishState()
    }

    private fun syncLayerDocument(layer: PaintLayer) {
        updateProjectDocument { document ->
            document.copy(
                layers = document.layers.map { stored ->
                    if (stored.id == layer.id) layerDocumentFrom(layer, stored.pixelFile) else stored
                },
                activeLayerId = layerStack.activeLayerId,
            )
        }
    }

    private fun layerDocumentFrom(
        layer: PaintLayer,
        pixelFile: String = ProjectDocument.pixelFileFor(layer.id),
    ): LayerDocument = LayerDocument(
        id = layer.id,
        name = layer.name,
        visible = layer.isVisible,
        locked = layer.isLocked,
        opacity = layer.opacity,
        blendMode = layer.blendMode,
        pixelFile = pixelFile,
        thumbnailVersion = layerThumbnailVersions[layer.id]
            ?: projectDocument.layers.firstOrNull { it.id == layer.id }?.thumbnailVersion
            ?: 0L,
    )

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
                        thumbnailPath = layerThumbnailPaths[layer.id],
                        thumbnailVersion = layerThumbnailVersions[layer.id]
                            ?: projectDocument.layers.firstOrNull { it.id == layer.id }?.thumbnailVersion
                            ?: 0L,
                    )
                },
                canUndo = undoManager.canUndo && undoPipeline.pendingCount == 0,
                canRedo = undoManager.canRedo && undoPipeline.pendingCount == 0,
                brushSizePx = brushSettings.baseRadiusPx * 2f,
                brushOpacity = brushSettings.opacity,
                activeLayerId = activeLayerId,
                ready = layerStack.canvasWidth > 0 && layerStack.canvasHeight > 0,
                undoDiagnostics = UndoDiagnostics(
                    pendingJobs = undoPipeline.pendingCount,
                    staleResults = undoPipeline.staleResultCount,
                    compressionFailures = undoPipeline.compressionFailureCount,
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

    private fun nextDuplicateLayerName(sourceName: String): String {
        val base = "$sourceName копия"
        val names = layerStack.allLayers().map(PaintLayer::name).toSet()
        if (base !in names) return base
        var number = 2
        while ("$base $number" in names) number++
        return "$base $number"
    }

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
            flow = strokeBrush.flow,
        )
    }

    private fun commitCapsuleStroke(strokeBrush: BrushSettings) {
        val layer = layerStack.activeLayer() ?: return
        val renderer = capsuleRenderer ?: return

        if (layer.isLocked) return
        if (!capsuleEmitter.hasStroke) return
        if (!computeCapsuleDirtyBounds(dirtyBounds)) return
        val blitter = strokeBlitter ?: return
        val strokeGeometry = geometry ?: return

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
            flow = strokeBrush.flow,
        )

        if (strokeCommitter.commit(
            layer = layer,
            geometry = strokeGeometry,
            blitter = blitter,
            dirtyBounds = dirtyBounds,
            canvasWidth = layerStack.canvasWidth,
            canvasHeight = layerStack.canvasHeight,
            opacity = strokeBrush.opacity,
        )) {
            publishState()
        }
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
        private const val THUMBNAIL_TAG = "ThumbnailBuild"
        private const val AA_MARGIN_PX = 4f
        private const val STAMP_DIRTY_MARGIN_PX = 4f
    }
}
