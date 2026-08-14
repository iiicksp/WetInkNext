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
import com.wetinknext.engine.brush.BlendPolicy
import com.wetinknext.engine.brush.StrokeRenderMode
import com.wetinknext.engine.brush.CapsuleEmitter
import com.wetinknext.engine.brush.CapsuleStrokeRenderer
import com.wetinknext.engine.brush.RibbonMeshRenderer
import com.wetinknext.engine.brush.ColorSpaces
import com.wetinknext.engine.brush.DabBuffer
import com.wetinknext.engine.brush.DabRenderer
import com.wetinknext.engine.brush.StampEmitter
import com.wetinknext.engine.brush.WetSimulationRenderer
import com.wetinknext.engine.canvas.CanvasBackdropRenderer
import com.wetinknext.engine.canvas.Compositor
import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.canvas.LinearTextureBlitter
import com.wetinknext.engine.canvas.PaintLayer
import com.wetinknext.engine.canvas.StrokeBlitter
import com.wetinknext.engine.canvas.NonBuildupStrokeRenderer
import com.wetinknext.engine.canvas.ScreenPresentRenderer
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.BudgetedTargets
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.RenderTargetBudget
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
import com.wetinknext.engine.undo.PboReadbackProbe
import com.wetinknext.engine.undo.DocumentCommand
import com.wetinknext.engine.undo.LayerPropertiesState
import com.wetinknext.engine.undo.RemovedLayerState
import com.wetinknext.engine.undo.UndoEntry
import com.wetinknext.engine.undo.DeflateTileCompressor
import com.wetinknext.engine.undo.UndoManager
import com.wetinknext.engine.undo.UndoOperationType
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
    /** Serializes persistent tile payloads off the GL thread after a commit. */
    private var tilePayloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
    private var backdropRenderer: CanvasBackdropRenderer? = null
    private var screenPresentRenderer: ScreenPresentRenderer? = null
    private var linearTextureBlitter: LinearTextureBlitter? = null
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
    private var ribbonMeshRenderer: RibbonMeshRenderer? = null
    private var grainTexture: BrushTexture? = null
    private var grainPath: String? = null
    private var shapeTexture: BrushTexture? = null
    private var shapePath: String? = null
    private var secondaryShapeTexture: BrushTexture? = null
    private var secondaryShapePath: String? = null
    private val gpuBudget = RenderTargetBudget()
    private val targets = BudgetedTargets(gpuBudget)
    private val strokeTarget = RenderTarget()
    private val smudgeTarget = RenderTarget()
    /** RGBA8 screen-space preview; never used for final document pixels. */
    private val strokePreviewTarget = RenderTarget()
    /** Screen-sized union mask for realtime STAMP NON_BUILDUP preview. */
    private val strokeCoveragePreviewTarget = RenderTarget()
    /** RGBA8 coverage accumulator used by the NON_BUILDUP stroke path. */
    private val strokeCoverageTarget = RenderTarget()
    /** Screen-sized linear frame composition. Source layers remain document-sized. */
    private val compositeTarget = RenderTarget()
    /** Confirmed layers below the active one, cached only for a live stroke preview. */
    private val lowerCompositeTarget = RenderTarget()
    /** Confirmed layers above the active one, cached only for a live stroke preview. */
    private val upperCompositeTarget = RenderTarget()
    /** Half-float document-sized fluid-dynamics renderer for WET brushes. */
    private val wetSimulationRenderer = WetSimulationRenderer()
    /** Ping-pong fluid targets. RGB = premultiplied pigment, A = water amount. */
    private val wetTargetA = RenderTarget()
    private val wetTargetB = RenderTarget()
    /** Where the finalised pigment-coverage of a WET stroke is produced before merging. */
    private val wetCompositeTarget = RenderTarget()
    private var wetFrontIsA = true
    /** Last fluid-step timestamp; real deltaSeconds keeps diffusion frame-rate independent. */
    private var wetStepLastNanos = 0L
    /** Reused brush-tip velocity in document UV/s (direction-aligned) for advection. */
    private val wetMotionUv = FloatArray(2)
    /** Current fluid source. The ping-pong `wetFrontIsA` flag flips each step. */
    private val wetFront: RenderTarget
        get() = if (wetFrontIsA) wetTargetA else wetTargetB
    /** Current fluid destination (the other side of the ping-pong). */
    private val wetBack: RenderTarget
        get() = if (wetFrontIsA) wetTargetB else wetTargetA
    private var strokeCacheEnabled = false
    private var compositeCacheDirty = true
    private var cachedPreviewActiveLayerIndex = -1
    private val cachedPreviewCanvasToClipMatrix = FloatArray(16)
    private val canvasToFboMatrix = FloatArray(16)
    private val canvasToPreviewMatrix = FloatArray(16)
    private val strokeCommitter = StrokeCommitter(
        strokeTarget = strokeTarget,
        canvasToFbo = canvasToFboMatrix,
        undoPipeline = undoPipeline,
        undoManager = undoManager,
        requestRender = { onInputRenderRequested?.invoke() },
        onTilesCommitted = { layer, tiles ->
            invalidateCompositeCache()
            layerTileStore.markDirty(layer.id, tiles)
            documentSession?.markLayerDirty(layer.id)
            publishDirtyTiles()
            markLayerThumbnailDirty(layer.id)
        },
        onLayerCleared = { layer ->
            invalidateCompositeCache()
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
    private var maxInputQueueDepth = 0
    private var droppedBatchCountAtLastLog = 0L
    private var frameStartNanos = 0L
    private var lastFrameLogNanos = 0L
    private var frameCounter = 0
    private var inputBatchesSinceLastFrameLog = 0
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
    /** Tool state, deliberately independent from the selected brush preset. */
    private var eraserEnabled = false
    /** Tool snapshot: toggling tools never changes an already-active stroke. */
    private var activeStrokeErase = false
    private var activeStrokeBrush: BrushSettings? = null
    private var strokeBlitter: StrokeBlitter? = null
    private var nonBuildupStrokeRenderer: NonBuildupStrokeRenderer? = null
    private var capsulePreviewInitialized = false
    private var renderedRibbonMeshVersion = -1L
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
        if (undoPipeline.pendingCount > 0 || strokeCommitter.pendingReadbackCount > 0) return
        resetStroke()
        while (true) {
            val entry = undoManager.peekUndo() ?: return
            val restored = undoCommand(entry.command)
            if (!restored) {
                undoRestoreFailures++
                publishState()
                return
            }
            if (!undoManager.commitUndo(entry)) {
                publishState()
                return
            }
            invalidateCompositeCache()
            updateProjectDocument { it }
            publishState()
            return
        }
    }

    fun redo() {
        if (undoPipeline.pendingCount > 0 || strokeCommitter.pendingReadbackCount > 0) return
        resetStroke()
        while (true) {
            val entry = undoManager.peekRedo() ?: return
            val restored = redoCommand(entry.command)
            if (!restored) {
                undoRestoreFailures++
                publishState()
                return
            }
            if (!undoManager.commitRedo(entry)) {
                publishState()
                return
            }
            invalidateCompositeCache()
            updateProjectDocument { it }
            publishState()
            return
        }
    }

    private fun undoCommand(command: DocumentCommand): Boolean {
        return when (command) {
        is DocumentCommand.TileEdit -> {
            layerStack.findLayerById(command.layerId)?.let { layer ->
                TileSnapshotRestore.restore(layer.target, command.beforeTiles).also { restored ->
                    if (restored) {
                        layer.version++
                        persistRestoredTiles(layer, command.beforeTiles)
                    }
                }
            } ?: false
        }

        is DocumentCommand.LayerProperties -> {
            val layer = layerStack.findLayerById(command.layerId) ?: return false
            applyLayerProperties(layer, command.before)
            true
        }

        is DocumentCommand.RemoveLayer -> restoreRemovedLayer(command)
        }
    }

    private fun redoCommand(command: DocumentCommand): Boolean {
        return when (command) {
        is DocumentCommand.TileEdit -> {
            layerStack.findLayerById(command.layerId)?.let { layer ->
                val restored = if (command.operation == UndoOperationType.CLEAR_LAYER) {
                    layer.clear()
                    layerTileStore.markLayerCleared(layer.id)
                    documentSession?.markLayerDirty(layer.id)
                    publishDirtyTiles()
                    markLayerThumbnailDirty(layer.id)
                    true
                } else {
                    TileSnapshotRestore.restore(layer.target, command.afterTiles)
                }
                if (restored) {
                    layer.version++
                    if (command.operation != UndoOperationType.CLEAR_LAYER) {
                        persistRestoredTiles(layer, command.afterTiles)
                    }
                }
                restored
            } ?: false
        }

        is DocumentCommand.LayerProperties -> {
            val layer = layerStack.findLayerById(command.layerId) ?: return false
            applyLayerProperties(layer, command.after)
            true
        }

        is DocumentCommand.RemoveLayer -> removeLayerForRedo(command)
        }
    }

    private fun layerProperties(layer: PaintLayer) = LayerPropertiesState(
        visible = layer.isVisible,
        locked = layer.isLocked,
        opacity = layer.opacity,
        blendMode = layer.blendMode,
    )

    private fun applyLayerProperties(layer: PaintLayer, state: LayerPropertiesState) {
        layer.isVisible = state.visible
        layer.isLocked = state.locked
        layer.opacity = state.opacity.coerceIn(0f, 1f)
        layer.blendMode = state.blendMode
        syncLayerDocument(layer)
        captureThumbnail(listOf(layer.id))
    }

    private fun restoreRemovedLayer(command: DocumentCommand.RemoveLayer): Boolean {
        val state = command.layer
        val layer = layerStack.restoreLayer(
            id = command.layerId,
            name = state.name,
            insertAt = state.index,
            visible = state.visible,
            locked = state.locked,
            opacity = state.opacity,
            blendMode = state.blendMode,
            version = state.version,
        ) ?: return false
        if (!TileSnapshotRestore.restore(layer.target, state.tiles)) return false
        layerStack.setActive(state.activeLayerIdBefore)
        layerTileStore.markDirty(layer.id, state.tiles.map { tile ->
            com.wetinknext.engine.undo.RawTileSnapshot(
                coord = tile.coord,
                pixelLeft = tile.pixelLeft,
                pixelTop = tile.pixelTop,
                pixelWidth = tile.pixelWidth,
                pixelHeight = tile.pixelHeight,
                bytesPerPixel = tile.bytesPerPixel,
                rawBytes = tile.decompress(),
            )
        })
        documentSession?.markLayerDirty(layer.id)
        publishDirtyTiles()
        markLayerThumbnailDirty(layer.id)
        return true
    }

    private fun removeLayerForRedo(command: DocumentCommand.RemoveLayer): Boolean {
        val removed = layerStack.removeLayer(command.layerId) ?: return false
        layerStack.setActive(command.layer.activeLayerIdAfter)
        layerThumbnailPaths.remove(command.layerId)
        layerThumbnailVersions.remove(command.layerId)
        thumbnailScheduler?.removeLayer(command.layerId)
        return true
    }

    fun setActiveLayer(id: Long): Boolean {
        resetStroke()
        return layerStack.setActive(id).also { changed ->
            if (changed) {
                invalidateCompositeCache()
                updateProjectDocument { it.copy(activeLayerId = id) }
                publishState()
            }
        }
    }

    fun addLayer(): Long? = addLayer(nextLayerName())

    fun addLayer(name: String): Long? {
        resetStroke()
        val layer = layerStack.addLayer(name) ?: run {
            publishState()
            return null
        }
        invalidateCompositeCache()
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
        ) ?: run {
            publishState()
            return null
        }
        duplicate.also {
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
        invalidateCompositeCache()

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
        val layer = layerStack.findLayerById(id) ?: return false
        if (layer.isLocked || layerStack.count <= 1) return false
        val index = layerStack.indexOfLayer(id)
        val raw = com.wetinknext.engine.undo.TileSnapshotCapture.capture(
            target = layer.target,
            bounds = intArrayOf(0, 0, layerStack.canvasWidth, layerStack.canvasHeight),
        )
        val command = DocumentCommand.RemoveLayer(
            layerId = layer.id,
            layer = RemovedLayerState(
                index = index,
                activeLayerIdBefore = layerStack.activeLayerId,
                activeLayerIdAfter = if (layerStack.activeLayerId == id) {
                    layerStack.allLayers().getOrNull((index + 1).coerceAtMost(layerStack.count - 1))?.id
                        ?: layerStack.allLayers().getOrNull(index - 1)?.id
                        ?: layerStack.activeLayerId
                } else {
                    layerStack.activeLayerId
                },
                name = layer.name,
                visible = layer.isVisible,
                locked = layer.isLocked,
                opacity = layer.opacity,
                blendMode = layer.blendMode,
                version = layer.version,
                tiles = raw.map { it.compress(DeflateTileCompressor()) },
            ),
        )
        val removed = layerStack.removeLayer(id) != null
        if (removed) {
            invalidateCompositeCache()
            undoManager.push(UndoEntry(command))
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
            invalidateCompositeCache()
            updateProjectDocument { it }
            captureThumbnail()
            publishState()
        }
        return moved
    }

    fun setLayerVisible(id: Long, visible: Boolean) {
        resetStroke()
        val layer = layerStack.findLayerById(id) ?: return
        if (layer.isVisible == visible) return
        val before = layerProperties(layer)
        layer.isVisible = visible
        undoManager.push(
            UndoEntry(
                DocumentCommand.LayerProperties(
                    layerId = id,
                    before = before,
                    after = layerProperties(layer),
                    tag = "set_layer_visible",
                ),
            ),
        )
        invalidateCompositeCache()
        syncLayerDocument(layer)
        captureThumbnail()
        publishState()
    }

    fun setLayerOpacity(id: Long, opacity: Float) {
        val layer = layerStack.findLayerById(id) ?: return
        val nextOpacity = opacity.coerceIn(0f, 1f)
        if (layer.opacity == nextOpacity) return
        val before = layerProperties(layer)
        layer.opacity = nextOpacity
        undoManager.push(
            UndoEntry(
                DocumentCommand.LayerProperties(
                    layerId = id,
                    before = before,
                    after = layerProperties(layer),
                    tag = "set_layer_opacity",
                ),
            ),
        )
        invalidateCompositeCache()
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
        if (cleared is StrokeCommitter.CommitResult.Queued) {
            updateProjectDocument { it }
            publishState()
        }
        return cleared is StrokeCommitter.CommitResult.Queued
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

    fun setEraserEnabled(enabled: Boolean) {
        eraserEnabled = enabled
    }

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
        ensureTilePayloadExecutor()
        thumbnailScheduler?.ensureRunning()
        val nextCaps = GlCaps.query()
        caps = nextCaps
        documentSession = DocumentSession(
            document = projectDocument,
            layerStack = layerStack,
            undoManager = undoManager,
            layerTiles = initialLayerTiles,
        ).also { it.loadIntoGpu(nextCaps, targets) }
        if (deferredRestoredLayerThumbnails.isNotEmpty()) {
            val previews = deferredRestoredLayerThumbnails
            deferredRestoredLayerThumbnails = emptyMap()
            installRestoredLayerThumbnails(previews)
        }
        check(
            targets.create(
                target = strokeTarget,
                label = "strokeTarget",
                width = projectDocument.width,
                height = projectDocument.height,
                preferHalfFloat = nextCaps.supportsHalfFloatColorBuffer,
            ),
        ) { "GPU budget cannot fit the document stroke target" }
        strokeTarget.clear(0f, 0f, 0f, 0f)
        PboReadbackProbe.verify(strokeTarget)

        check(
            targets.create(
                target = smudgeTarget,
                label = "smudgeTarget",
                width = projectDocument.width,
                height = projectDocument.height,
                preferHalfFloat = nextCaps.supportsHalfFloatColorBuffer,
            ),
        ) { "GPU budget cannot fit the document smudge target" }
        smudgeTarget.clear(0f, 0f, 0f, 0f)
        ViewTransform.buildCanvasToFbo(
            projectDocument.width.toFloat(),
            projectDocument.height.toFloat(),
            canvasToFboMatrix,
        )
        geometry = CanvasGeometry().also {
            it.create(projectDocument.width, projectDocument.height)
        }
        compositor = Compositor().also { it.create() }
        backdropRenderer = CanvasBackdropRenderer().also { it.create() }
        screenPresentRenderer = ScreenPresentRenderer().also { it.create() }
        linearTextureBlitter = LinearTextureBlitter().also { it.create() }
        invalidateCompositeCache()
        dabRenderer = DabRenderer(dabBuffer.capacity).also { it.create() }
        capsuleRenderer = CapsuleStrokeRenderer().also {
            it.create()
        }
        ribbonMeshRenderer = RibbonMeshRenderer().also { it.create() }
        wetSimulationRenderer.create()
        strokeBlitter = StrokeBlitter().also { it.create() }
        nonBuildupStrokeRenderer = NonBuildupStrokeRenderer().also { it.create() }
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
        releaseStrokeCaches()
        targets.release(strokePreviewTarget)
        targets.release(strokeCoveragePreviewTarget)
        check(
            targets.create(
                target = compositeTarget,
                label = "compositeTarget",
                width = screenWidth,
                height = screenHeight,
                preferHalfFloat = false,
            ),
        ) { "GPU budget cannot fit the screen composite target" }
        compositeTarget.clear(0f, 0f, 0f, 0f)
        invalidateCompositeCache()
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        layerStack.camera.fitCanvas(layerStack.canvasWidth, layerStack.canvasHeight, screenWidth, screenHeight)
        GlCheck.noError("P6 surface changed")
    }

    override fun onDrawFrame(gl: GL10?) {
        frameStartNanos = System.nanoTime()
        if (cancelRequested.compareAndSet(true, false)) resetStroke()
        if (undoGestureRequested.compareAndSet(true, false)) undo()
        if (redoGestureRequested.compareAndSet(true, false)) redo()
        if (resetCameraGestureRequested.compareAndSet(true, false)) resetCamera()
        inputBatchesSinceLastFrameLog += drainInput()
        if (strokeCommitter.processPendingReadbacks()) publishState()
        if (undoPipeline.process(undoManager)) publishState()
        thumbnailScheduler?.processCompleted()
        // Thumbnail capture calls glReadPixels. A pending gallery preview must
        // never steal a frame from an active brush stroke.
        if (!strokeActive) thumbnailScheduler?.buildIfNeeded(layerStack)

        val frameStrokeBrush = activeStrokeBrush
        if (strokeActive && frameStrokeBrush?.renderMode == BrushRenderMode.RIBBON) {
            renderCapsulePreview(frameStrokeBrush)
        } else if (strokeActive && frameStrokeBrush?.renderMode == BrushRenderMode.STAMP) {
            if (frameStrokeBrush.emissionUsesTime) {
                val timeEmitted = stampEmitter.advanceTime(System.nanoTime(), dabBuffer)
                if (timeEmitted > 0) {
                    drawPendingStampPreview("TIME")
                }
                // Keep the render loop going while the pen is held down
                onInputRenderRequested?.invoke()
            }
        }

        val currentGeometry = geometry ?: run {
            logFrameIfNeeded()
            return
        }
        val currentCompositor = compositor ?: run {
            logFrameIfNeeded()
            return
        }
        val currentScreenPresentRenderer = screenPresentRenderer ?: run {
            logFrameIfNeeded()
            return
        }

        dabRenderer?.setScreenDimensions(screenWidth.toFloat(), screenHeight.toFloat())
        ribbonMeshRenderer?.setScreenDimensions(screenWidth.toFloat(), screenHeight.toFloat())

        layerStack.camera.snapshot().buildCanvasToClip(
            screenWidth.toFloat(),
            screenHeight.toFloat(),
            canvasToClipMatrix,
        )

        val hasNonBuildupPreview = strokeActive &&
            frameStrokeBrush?.effectiveStrokeRenderMode == StrokeRenderMode.NON_BUILDUP &&
            (dabBuffer.count > 0 || capsuleEmitter.hasStroke)
        val isWet = activeStrokeBrush?.renderMode == BrushRenderMode.WET
        val previewTextureId = if (
            strokeActive &&
            !hasNonBuildupPreview &&
            (strokePreviewTarget.textureId != 0 || isWet) &&
            (dabBuffer.count > 0 ||
                (frameStrokeBrush?.renderMode == BrushRenderMode.RIBBON && capsuleEmitter.hasStroke))
        ) {
            if (isWet) wetFront.textureId else strokePreviewTarget.textureId
        } else {
            0
        }
        val isScreenSpace = !isWet && (previewTextureId != 0 || hasNonBuildupPreview && strokeCoveragePreviewTarget.textureId != 0)

        renderDocumentComposite(
            compositor = currentCompositor,
            geometry = currentGeometry,
            previewTextureId = previewTextureId,
            previewCoverageTextureId = if (hasNonBuildupPreview && strokeCoveragePreviewTarget.textureId != 0) {
                strokeCoveragePreviewTarget.textureId
            } else {
                0
            },
            previewMode = if (hasNonBuildupPreview) {
                StrokeRenderMode.NON_BUILDUP
            } else {
                StrokeRenderMode.NORMAL_BUILDUP
            },
            strokeOpacity = frameStrokeBrush?.opacity?.coerceIn(0f, 1f) ?: 1f,
            strokeIsScreenSpace = isScreenSpace,
        )

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(canvasBackdropColor[0], canvasBackdropColor[1], canvasBackdropColor[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        drawBackdropPattern()
        currentScreenPresentRenderer.render(
            sourceTextureId = compositeTarget.textureId,
            viewportWidth = screenWidth,
            viewportHeight = screenHeight,
        )
        logFrameIfNeeded()
    }

    /**
     * During a live stroke, the confirmed layers above and below the active
     * layer do not change. Cache those two screen-space composites and redraw
     * only the active layer plus its preview on MOVE. This preserves layer
     * ordering for both paint and eraser previews.
     */
    private fun renderDocumentComposite(
        compositor: Compositor,
        geometry: CanvasGeometry,
        previewTextureId: Int,
        previewCoverageTextureId: Int,
        previewMode: StrokeRenderMode,
        strokeOpacity: Float,
        strokeIsScreenSpace: Boolean,
    ) {
        val activeLayerIndex = layerStack.indexOfLayer(layerStack.activeLayerId)
        val textureBlitter = linearTextureBlitter
        val canUseStrokeCache = strokeActive &&
            strokeCacheEnabled &&
            textureBlitter != null &&
            activeLayerIndex in 0 until layerStack.count &&
            lowerCompositeTarget.textureId != 0 &&
            upperCompositeTarget.textureId != 0

        if (!canUseStrokeCache) {
            compositeTarget.clear(0f, 0f, 0f, 0f)
            GLES30.glViewport(0, 0, screenWidth, screenHeight)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            compositor.render(
                destination = compositeTarget,
                geometry = geometry,
                layers = layerStack,
                activeLayerId = layerStack.activeLayerId,
                strokeTextureId = previewTextureId,
                strokeCoverageTextureId = previewCoverageTextureId,
                strokeIsScreenSpace = strokeIsScreenSpace,
                strokeMode = previewMode,
                strokeErase = activeStrokeErase,
                strokeColorLinear = activeStrokeColorLinear,
                strokeOpacity = strokeOpacity,
                canvasToClip = canvasToClipMatrix,
            )
            return
        }

        rebuildPreviewCompositeCachesIfNeeded(
            compositor = compositor,
            geometry = geometry,
            activeLayerIndex = activeLayerIndex,
        )

        compositeTarget.clear(0f, 0f, 0f, 0f)
        compositeTarget.bind()
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        if (activeLayerIndex > 0) {
            textureBlitter.blit(lowerCompositeTarget.textureId)
        }
        compositor.render(
            destination = compositeTarget,
            geometry = geometry,
            layers = layerStack,
            activeLayerId = layerStack.activeLayerId,
            strokeTextureId = previewTextureId,
            strokeCoverageTextureId = previewCoverageTextureId,
            strokeIsScreenSpace = strokeIsScreenSpace,
            strokeMode = previewMode,
            strokeErase = activeStrokeErase,
            strokeColorLinear = activeStrokeColorLinear,
            strokeOpacity = strokeOpacity,
            canvasToClip = canvasToClipMatrix,
            firstLayerIndex = activeLayerIndex,
            lastLayerExclusive = activeLayerIndex + 1,
        )
        if (activeLayerIndex < layerStack.count - 1) {
            textureBlitter.blit(upperCompositeTarget.textureId)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun rebuildPreviewCompositeCachesIfNeeded(
        compositor: Compositor,
        geometry: CanvasGeometry,
        activeLayerIndex: Int,
    ) {
        val cameraChanged = !canvasToClipMatrix.contentEquals(cachedPreviewCanvasToClipMatrix)
        if (!compositeCacheDirty &&
            cachedPreviewActiveLayerIndex == activeLayerIndex &&
            !cameraChanged
        ) {
            return
        }

        lowerCompositeTarget.clear(0f, 0f, 0f, 0f)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        compositor.render(
            destination = lowerCompositeTarget,
            geometry = geometry,
            layers = layerStack,
            activeLayerId = -1L,
            strokeTextureId = 0,
            strokeErase = false,
            strokeOpacity = 1f,
            canvasToClip = canvasToClipMatrix,
            firstLayerIndex = 0,
            lastLayerExclusive = activeLayerIndex,
        )

        upperCompositeTarget.clear(0f, 0f, 0f, 0f)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        compositor.render(
            destination = upperCompositeTarget,
            geometry = geometry,
            layers = layerStack,
            activeLayerId = -1L,
            strokeTextureId = 0,
            strokeErase = false,
            strokeOpacity = 1f,
            canvasToClip = canvasToClipMatrix,
            firstLayerIndex = activeLayerIndex + 1,
            lastLayerExclusive = layerStack.count,
        )

        canvasToClipMatrix.copyInto(cachedPreviewCanvasToClipMatrix)
        cachedPreviewActiveLayerIndex = activeLayerIndex
        compositeCacheDirty = false
    }

    private fun invalidateCompositeCache() {
        compositeCacheDirty = true
        cachedPreviewActiveLayerIndex = -1
    }

    /** Optional screen-space caches: failure falls back to full compositing. */
    private fun ensureStrokeCaches(): Boolean {
        if (strokeCacheEnabled) return true

        val lowerCreated = targets.create(
            target = lowerCompositeTarget,
            label = "lowerComposite",
            width = screenWidth,
            height = screenHeight,
            preferHalfFloat = false,
        )
        val upperCreated = lowerCreated && targets.create(
            target = upperCompositeTarget,
            label = "upperComposite",
            width = screenWidth,
            height = screenHeight,
            preferHalfFloat = false,
        )
        if (!upperCreated) {
            targets.release(lowerCompositeTarget)
            targets.release(upperCompositeTarget)
            strokeCacheEnabled = false
            if (BuildConfig.DEBUG) {
                Log.w(FRAME_DIAG_TAG, "stroke cache disabled: GPU budget")
            }
            return false
        }

        lowerCompositeTarget.clear(0f, 0f, 0f, 0f)
        upperCompositeTarget.clear(0f, 0f, 0f, 0f)
        strokeCacheEnabled = true
        invalidateCompositeCache()
        return true
    }

    private fun releaseStrokeCaches() {
        targets.release(lowerCompositeTarget)
        targets.release(upperCompositeTarget)
        strokeCacheEnabled = false
        invalidateCompositeCache()
    }

    /** Optional preview target; a failure affects only realtime preview, not commit. */
    private fun ensurePreviewTarget(nonBuildup: Boolean): Boolean {
        val target = if (nonBuildup) strokeCoveragePreviewTarget else strokePreviewTarget
        val label = if (nonBuildup) "coveragePreview" else "strokePreview"
        if (target.textureId != 0) {
            target.clear(0f, 0f, 0f, 0f)
            return true
        }

        val created = targets.create(
            target = target,
            label = label,
            width = screenWidth,
            height = screenHeight,
            preferHalfFloat = false,
        )
        if (created) {
            target.clear(0f, 0f, 0f, 0f)
        }
        return created
    }

    /** Required only at NON_BUILDUP commit; omitted while the user is drawing. */
    private fun ensureCoverageTarget(): Boolean {
        if (strokeCoverageTarget.textureId != 0) return true
        fun createCoverage() = targets.create(
            target = strokeCoverageTarget,
            label = "strokeCoverage",
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            preferHalfFloat = false,
        )

        if (createCoverage()) return true
        // A commit is more important than a live-composite optimisation.
        releaseStrokeCaches()
        return createCoverage()
    }

    private fun drawBackdropPattern() {
        val mode = when (canvasBackdropMode) {
            CanvasBackdropMode.SOLID -> return
            CanvasBackdropMode.GRID -> CanvasBackdropRenderer.MODE_GRID
            CanvasBackdropMode.CHECKERBOARD -> CanvasBackdropRenderer.MODE_CHECKERBOARD
        }
        backdropRenderer?.render(
            viewportWidth = screenWidth,
            viewportHeight = screenHeight,
            backgroundColor = canvasBackdropColor,
            gridColor = canvasGridColor,
            mode = mode,
        )
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

    private fun ensureTilePayloadExecutor() {
        if (tilePayloadExecutor.isShutdown) {
            tilePayloadExecutor = Executors.newSingleThreadExecutor()
        }
    }

    private fun shutdownTilePayloadExecutor() {
        tilePayloadExecutor.shutdownNow()
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
        strokeCommitter.releaseReadbacks()
        undoManager.clear()
        shutdownUndoExecutor()
        shutdownTilePayloadExecutor()
        dabRenderer?.release()
        dabRenderer = null
        grainTexture?.release()
        grainTexture = null
        grainPath = null
        grainTexture?.release()
        grainTexture = null
        grainPath = null
        shapeTexture?.release()
        shapeTexture = null
        shapePath = null
        secondaryShapeTexture?.release()
        secondaryShapeTexture = null
        secondaryShapePath = null
        capsuleRenderer?.release()
        capsuleRenderer = null
        ribbonMeshRenderer?.release()
        ribbonMeshRenderer = null
        wetSimulationRenderer.release()
        strokeBlitter?.release()
        strokeBlitter = null
        nonBuildupStrokeRenderer?.release()
        nonBuildupStrokeRenderer = null
        compositor?.release()
        compositor = null
        backdropRenderer?.release()
        backdropRenderer = null
        screenPresentRenderer?.release()
        screenPresentRenderer = null
        linearTextureBlitter?.release()
        linearTextureBlitter = null
        thumbnailScheduler?.shutdown()
        thumbnailCapture.release()
        documentSession = null
        geometry?.release()
        geometry = null
        targets.releaseAll()
        gpuBudget.reset()
        layerStack.release()
        caps = null
    }

    private fun drainInput(): Int {
        var processedBatches = 0
        val startedAtNanos = System.nanoTime()
        while (
            processedBatches < MAX_INPUT_BATCHES_PER_FRAME &&
            System.nanoTime() - startedAtNanos < MAX_INPUT_PROCESS_NANOS
        ) {
            val batch = inputQueue.poll() ?: break
            try {
                when (batch.action) {
                    InputAction.DOWN -> {
                        if (!batch.isEmpty()) {
                            resetStroke()
                            val strokeBrush = brushSettings.resolved()
                            activeStrokeBrush = strokeBrush
                            ColorSpaces.srgb8ToLinear(strokeBrush.colorArgb, activeStrokeColorLinear)
                            activeStrokeColorLinear.copyInto(strokeColorLinear)
                            activeStrokeErase = eraserEnabled
                            strokeActive = true
                            val previewAllocated = ensurePreviewTarget(
                                strokeBrush.effectiveStrokeRenderMode == StrokeRenderMode.NON_BUILDUP,
                            )
                            ensureStrokeCaches()

                            when (strokeBrush.renderMode) {
                                BrushRenderMode.RIBBON -> {
                                    capsuleEmitter.begin(
                                        batch = batch,
                                        out = checkNotNull(capsuleRenderer),
                                        strokeSettings = strokeBrush,
                                    )
                                }
                                BrushRenderMode.STAMP -> {
                                    dabRenderer?.apply {
                                        beginStroke()
                                        setFalloff(strokeBrush.falloff)
                                        squareStroke = strokeBrush.squareStroke
                                        noAntialias = strokeBrush.noAntialias
                                        if (strokeBrush.colorPull > 0f) {
                                            captureSmudgeBackground()
                                            setSmudge(smudgeTarget.textureId, strokeBrush.colorPull, strokeBrush.colorPullLength)
                                        } else {
                                            clearSmudge()
                                        }
                                    }
                                    stampEmitter.begin(batch, dabBuffer, strokeBrush)
                                    stampPreviewInitialized = previewAllocated
                                    if (previewAllocated) drawPendingStampPreview("DOWN")
                                }
                                BrushRenderMode.WET -> {
                                    if (!ensureWetTargets()) {
                                        resetStroke()
                                        // cannot return here from inside when/try, so we just break out of stroke logic
                                        strokeActive = false
                                    } else {
                                        dabRenderer?.apply {
                                            beginStroke()
                                            setFalloff(strokeBrush.falloff)
                                        squareStroke = strokeBrush.squareStroke
                                        noAntialias = strokeBrush.noAntialias
                                        }
                                        stampEmitter.begin(batch, dabBuffer, strokeBrush)
                                        drawPendingWet()
                                        stampPreviewInitialized = previewAllocated
                                    }
                                }
                            }
                        }
                    }

                    InputAction.MOVE -> if (strokeActive) {
                        val strokeBrush = activeStrokeBrush
                            ?: error("Missing active stroke snapshot")
                        when (strokeBrush.renderMode) {
                            BrushRenderMode.RIBBON -> {
                                capsuleEmitter.append(
                                    batch = batch,
                                    out = checkNotNull(capsuleRenderer),
                                )
                            }
                            BrushRenderMode.STAMP -> {
                                stampEmitter.append(batch, dabBuffer)
                                drawPendingStampPreview("MOVE")
                            }
                            BrushRenderMode.WET -> {
                                stampEmitter.append(batch, dabBuffer)
                                drawPendingWet()
                            }
                        }
                    }

                    InputAction.UP -> if (strokeActive) {
                        val strokeBrush = activeStrokeBrush
                            ?: error("Missing active stroke snapshot")
                        when (strokeBrush.renderMode) {
                            BrushRenderMode.RIBBON -> {
                                val renderer = checkNotNull(capsuleRenderer)
                                capsuleEmitter.append(
                                    batch = batch,
                                    out = renderer,
                                )
                                capsuleEmitter.finish(
                                    out = renderer,
                                    cancel = false,
                                )
                                commitStroke(strokeBrush)
                            }
                            BrushRenderMode.STAMP -> {
                                stampEmitter.append(batch, dabBuffer)
                                stampEmitter.finish(dabBuffer, cancel = false)
                                commitStroke(strokeBrush)
                            }
                            BrushRenderMode.WET -> {
                                stampEmitter.append(batch, dabBuffer)
                                drawPendingWet()
                                stampEmitter.finish(dabBuffer, cancel = false)
                                commitStroke(strokeBrush)
                            }
                        }

                        resetStroke()
                    }

                    InputAction.CANCEL -> resetStroke()
                }
            } finally {
                inputPool.release(batch)
            }
            processedBatches++
        }

        val queueDepth = inputQueue.size
        if (queueDepth > maxInputQueueDepth) maxInputQueueDepth = queueDepth
        if (BuildConfig.DEBUG && inputCapturer.droppedBatches != droppedBatchCountAtLastLog) {
            droppedBatchCountAtLastLog = inputCapturer.droppedBatches
            Log.w(
                INPUT_LATENCY_TAG,
                "queue=$queueDepth max=$maxInputQueueDepth dropped=${inputCapturer.droppedBatches}",
            )
        }

        // Keep a continuation frame scheduled, but never let a delayed input
        // backlog monopolise the GL thread and visibly freeze the canvas.
        if (inputQueue.isNotEmpty()) {
            onInputRenderRequested?.invoke()
        }
        return processedBatches
    }

    /** Debug-only aggregate; never writes Logcat from the MOVE hot path. */
    private fun logFrameIfNeeded() {
        frameCounter++
        if (!BuildConfig.DEBUG ||
            frameStartNanos - lastFrameLogNanos < FRAME_LOG_INTERVAL_NANOS
        ) {
            return
        }

        val frameMs = (System.nanoTime() - frameStartNanos) / 1_000_000f
        Log.d(
            FRAME_DIAG_TAG,
            "frameMs=$frameMs queue=${inputQueue.size} frames=$frameCounter " +
                "inputBatches=$inputBatchesSinceLastFrameLog dabs=${dabBuffer.count} " +
                "undoPending=${undoPipeline.pendingCount}",
        )
        lastFrameLogNanos = frameStartNanos
        frameCounter = 0
        inputBatchesSinceLastFrameLog = 0
    }

    private fun commitStroke(strokeBrush: BrushSettings) {
        val layer = layerStack.activeLayer() ?: return
        if (layer.isLocked || dabBuffer.count == 0) return
        if (!computeDirtyPixelBounds(dirtyBounds)) return
        val blitter = strokeBlitter ?: return
        val strokeGeometry = geometry ?: return

        if (strokeBrush.renderMode == BrushRenderMode.WET) {
            // Finalise the fluid buffer into pigment coverage (alpha = max pigment),
            // so a drying wash is not faded by leftover water.
            updateWetMotion()
            wetSimulationRenderer.step(
                source = wetFront,
                destination = wetCompositeTarget,
                wet = strokeBrush.wet,
                deltaSeconds = WetSimulationRenderer.DEFAULT_DELTA_SECONDS,
                motionUvPerSecondX = wetMotionUv[0],
                motionUvPerSecondY = wetMotionUv[1],
                finalize = true,
            )
            // The wash spreads beyond the source dabs; commit a dilated region so
            // the diffused edge is actually merged. Margin scales with spread.
            expandWetDirtyBounds()
            val commitResult = strokeCommitter.commit(
                sourceTarget = wetCompositeTarget,
                layer = layer,
                geometry = strokeGeometry,
                blitter = blitter,
                dirtyBounds = dirtyBounds,
                canvasWidth = layerStack.canvasWidth,
                canvasHeight = layerStack.canvasHeight,
                opacity = 1f,
                erase = false,
                strokeMode = StrokeRenderMode.NORMAL_BUILDUP
            )
            if (commitResult is com.wetinknext.engine.core.StrokeCommitter.CommitResult.Queued) {
                publishState()
            }
            wetStepLastNanos = 0L
            return
        }

        if (strokeBrush.effectiveStrokeRenderMode == StrokeRenderMode.NON_BUILDUP) {
            val renderer = dabRenderer ?: return
            val nonBuildupBlitter = nonBuildupStrokeRenderer ?: return
            if (!ensureCoverageTarget()) return
            strokeCoverageTarget.clear(0f, 0f, 0f, 0f)
            renderer.drawCoverageInto(
                target = strokeCoverageTarget,
                width = layerStack.canvasWidth,
                height = layerStack.canvasHeight,
                canvasToFbo = canvasToFboMatrix,
                dabs = dabBuffer,
            )
            if (strokeCommitter.commitNonBuildup(
                layer = layer,
                geometry = strokeGeometry,
                blitter = nonBuildupBlitter,
                coverageTarget = strokeCoverageTarget,
                colorLinear = activeStrokeColorLinear,
                dirtyBounds = dirtyBounds,
                canvasWidth = layerStack.canvasWidth,
                canvasHeight = layerStack.canvasHeight,
                opacity = strokeBrush.opacity,
                erase = activeStrokeErase,
            ) is StrokeCommitter.CommitResult.Queued) {
                publishState()
            }
            return
        }

        // Preview is screen-sized. Rebuild the document-sized target once on
        // UP, then commit those exact pixels to the active layer.
        strokeTarget.clear(0f, 0f, 0f, 0f)
        val renderer = dabRenderer ?: return
        renderer.drawInto(
            target = strokeTarget,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            canvasToFbo = canvasToFboMatrix,
            dabs = dabBuffer,
            colorLinear = activeStrokeColorLinear,
            blendPolicy = strokeBrush.blendPolicy,
            strokeOpacity = 1f,
        )

        if (strokeCommitter.commit(
            layer = layer,
            geometry = strokeGeometry,
            blitter = blitter,
            dirtyBounds = dirtyBounds,
            canvasWidth = layerStack.canvasWidth,
            canvasHeight = layerStack.canvasHeight,
            opacity = strokeBrush.opacity,
            erase = activeStrokeErase,
        ) is StrokeCommitter.CommitResult.Queued) {
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

    /**
     * Dilates the committed dirty region for a WET stroke so the diffused wash
     * edge (which spreads past the source dabs) is actually merged into the layer.
     */
    private fun expandWetDirtyBounds() {
        val spread = activeStrokeBrush?.wet?.spread ?: 0f
        val diag = kotlin.math.hypot(
            layerStack.canvasWidth.toFloat(),
            layerStack.canvasHeight.toFloat(),
        )
        val margin = (WET_WASH_MARGIN_PX + WET_WASH_MARGIN_SCALE * spread * diag)
            .coerceAtMost(0.12f * diag)
            .coerceAtLeast(WET_WASH_MARGIN_PX.toFloat())
        val m = margin.toInt()
        dirtyBounds[0] = (dirtyBounds[0] - m).coerceAtLeast(0)
        dirtyBounds[1] = (dirtyBounds[1] - m).coerceAtLeast(0)
        dirtyBounds[2] = (dirtyBounds[2] + m).coerceAtMost(layerStack.canvasWidth)
        dirtyBounds[3] = (dirtyBounds[3] + m).coerceAtMost(layerStack.canvasHeight)
    }

    private fun ensureWetTargets(): Boolean {
        val halfFloat = caps?.supportsHalfFloatColorBuffer == true
        val a = targets.create(
            target = wetTargetA,
            label = "wetTargetA",
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            preferHalfFloat = halfFloat,
        )
        val b = targets.create(
            target = wetTargetB,
            label = "wetTargetB",
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            preferHalfFloat = halfFloat,
        )
        val c = targets.create(
            target = wetCompositeTarget,
            label = "wetCompositeTarget",
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            preferHalfFloat = halfFloat,
        )
        if (!a || !b || !c) {
            targets.release(wetTargetA)
            targets.release(wetTargetB)
            targets.release(wetCompositeTarget)
            return false
        }
        wetFrontIsA = true
        wetTargetA.clear(0f, 0f, 0f, 0f)
        wetTargetB.clear(0f, 0f, 0f, 0f)
        return true
    }

    private fun drawPendingWet() {
        val brush = activeStrokeBrush ?: return
        val renderer = dabRenderer ?: return

        // Route the dab renderer into the fluid buffer (RGB pigment, A = water).
        renderer.setWetMode(true)
        renderer.setWetness(brush.wet.wetness)

        renderer.drawPendingInto(
            target = wetFront,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            canvasToFbo = canvasToFboMatrix,
            dabs = dabBuffer,
            colorLinear = activeStrokeColorLinear,
            blendPolicy = com.wetinknext.engine.brush.BlendPolicy.NORMAL_BUILDUP,
            strokeOpacity = 1f,
        )

        val now = System.nanoTime()
        val dt = if (wetStepLastNanos == 0L) {
            WetSimulationRenderer.DEFAULT_DELTA_SECONDS
        } else {
            (now - wetStepLastNanos) / 1_000_000_000f
        }
        wetStepLastNanos = now

        updateWetMotion()

        wetSimulationRenderer.step(
            source = wetFront,
            destination = wetBack,
            wet = brush.wet,
            deltaSeconds = dt,
            motionUvPerSecondX = wetMotionUv[0],
            motionUvPerSecondY = wetMotionUv[1],
            finalize = false,
        )
        wetFrontIsA = !wetFrontIsA
    }

    /**
     * Latest brush-tip velocity in document UV/s, aligned to the direction of
     * travel (the last dab displacement). Magnitude uses the low-passed
     * [StampEmitter] speed so fast strokes smear a drying wash the right way.
     */
    private fun updateWetMotion() {
        wetMotionUv[0] = 0f
        wetMotionUv[1] = 0f
        if (dabBuffer.count <= 0) return
        val speed = stampEmitter.lastVelocityPxPerSecond
        if (speed <= 1f) return
        val o = (dabBuffer.count - 1) * DabBuffer.FLOATS_PER_DAB
        val dx = dabBuffer.floats.get(o + 7)
        val dy = dabBuffer.floats.get(o + 8)
        val len = kotlin.math.hypot(dx, dy)
        if (len <= 1e-4f) return
        val cw = layerStack.canvasWidth.toFloat().coerceAtLeast(1f)
        val ch = layerStack.canvasHeight.toFloat().coerceAtLeast(1f)
        wetMotionUv[0] = (dx / len) * speed / cw
        wetMotionUv[1] = (dy / len) * speed / ch
    }

    private fun drawPendingStampPreview(phase: String) {
        val renderer = dabRenderer ?: return
        val strokeBrush = activeStrokeBrush
            ?: error("Missing active stroke snapshot")
        val previewTarget = if (
            strokeBrush.effectiveStrokeRenderMode == StrokeRenderMode.NON_BUILDUP
        ) {
            strokeCoveragePreviewTarget
        } else {
            strokePreviewTarget
        }
        if (previewTarget.textureId == 0) return
        check(renderer.uploadedCount <= dabBuffer.count) {
            "Stamp preview rewound: uploaded=${renderer.uploadedCount}, dabs=${dabBuffer.count}"
        }
        if (LOG_STAMP_PREVIEW && BuildConfig.DEBUG) {
            Log.d(
                BRUSH_DIAG_TAG,
                "phase=$phase dabs=${dabBuffer.count} pending=${dabBuffer.count - renderer.uploadedCount}",
            )
        }
        layerStack.camera.snapshot().buildCanvasToClip(
            screenWidth.toFloat(),
            screenHeight.toFloat(),
            canvasToPreviewMatrix,
        )
        if (strokeBrush.effectiveStrokeRenderMode == StrokeRenderMode.NON_BUILDUP) {
            renderer.drawPendingCoveragePreviewInto(
                target = strokeCoveragePreviewTarget,
                previewWidth = screenWidth,
                previewHeight = screenHeight,
                documentWidth = layerStack.canvasWidth,
                documentHeight = layerStack.canvasHeight,
                canvasToClip = canvasToPreviewMatrix,
                dabs = dabBuffer,
            )
        } else {
            renderer.drawPendingPreviewInto(
                target = strokePreviewTarget,
                previewWidth = screenWidth,
                previewHeight = screenHeight,
                documentWidth = layerStack.canvasWidth,
                documentHeight = layerStack.canvasHeight,
                canvasToClip = canvasToPreviewMatrix,
                dabs = dabBuffer,
                colorLinear = activeStrokeColorLinear,
                blendPolicy = BlendPolicy.NORMAL_BUILDUP,
                strokeOpacity = 1f,
            )
        }
        check(renderer.uploadedCount == dabBuffer.count) {
            "Stamp preview did not consume all dabs: uploaded=${renderer.uploadedCount}, dabs=${dabBuffer.count}"
        }
    }

    private fun resetStroke() {
        strokeActive = false
        activeStrokeErase = false
        activeStrokeBrush = null
        stampEmitter.reset()
        dabBuffer.clear()
        capsuleEmitter.reset()
        capsuleRenderer?.clearStrokeData()
        capsulePreviewInitialized = false
        renderedRibbonMeshVersion = -1L
        stampPreviewInitialized = false
        dabRenderer?.clearStrokeData()
        // Always restore the dab renderer to the non-wet path after a stroke;
        // WET deposits re-enable it inside drawPendingWet.
        dabRenderer?.setWetMode(false)
        wetStepLastNanos = 0L
        if (strokeTarget.framebufferId != 0) {
            strokeTarget.clear(0f, 0f, 0f, 0f)
        }
        releaseStrokeCaches()
        targets.release(strokePreviewTarget)
        targets.release(strokeCoveragePreviewTarget)
        targets.release(strokeCoverageTarget)
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
        val documentSnapshot = buildDocumentSnapshot()
        projectDocument = documentSnapshot
        documentSession?.markProjectDirty()
        // PersistentLayerTiles.encode() can copy tens of MB for a mature 4K
        // layer. Never do that allocation while the GL thread is responsible
        // for accepting stylus input and drawing the next preview frame.
        try {
            tilePayloadExecutor.execute {
                val payloads = runCatching {
                    layerTileStore.payloadsForDirty(dirty)
                }.getOrElse { error ->
                    if (BuildConfig.DEBUG) Log.e("TilePersistence", "Tile payload encoding failed", error)
                    return@execute
                }
                onDirtyLayerTiles?.invoke(documentSnapshot, payloads, dirty)
            }
        } catch (_: RejectedExecutionException) {
            // Context release cancelled a stale save. The next active session
            // will retain its dirty tiles and schedule a fresh publication.
        }
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
        // Thumbnail capture performs glReadPixels. Preview must never queue it
        // while a stylus stroke owns the GL frame budget.
        if (strokeActive) return
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
                canUndo = undoManager.canUndo && undoPipeline.pendingCount == 0 && strokeCommitter.pendingReadbackCount == 0,
                canRedo = undoManager.canRedo && undoPipeline.pendingCount == 0 && strokeCommitter.pendingReadbackCount == 0,
                brushSizePx = brushSettings.baseRadiusPx * 2f,
                brushOpacity = brushSettings.opacity,
                activeLayerId = activeLayerId,
                ready = layerStack.canvasWidth > 0 && layerStack.canvasHeight > 0,
                undoDiagnostics = UndoDiagnostics(
                    pendingJobs = undoPipeline.pendingCount + strokeCommitter.pendingReadbackCount,
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
        screenSpace: Boolean,
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
            dabRenderer?.setGrainScreenSpace(screenSpace)

            ribbonMeshRenderer?.setGrainTexture(
                textureId = newTexture.textureId,
                scale = scale,
                depth = depth,
                contrast = contrast,
            )
            ribbonMeshRenderer?.setGrainScreenSpace(screenSpace)

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
        ribbonMeshRenderer?.clearGrainTexture()

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

    fun applyLoadedSecondaryShape(
        loaded: LoadedBrushTexture,
        scale: Float,
    ) {
        val oldTexture = secondaryShapeTexture
        val newTexture = BrushTexture()

        try {
            newTexture.createFromRgba(loaded.width, loaded.height, loaded.rgba)
            dabRenderer?.setSecondaryShape(newTexture.textureId, scale)
            secondaryShapeTexture = newTexture
            secondaryShapePath = loaded.path
            oldTexture?.release()
        } catch (error: Throwable) {
            newTexture.release()
            throw error
        }
    }

    fun clearSecondaryShapeTexture() {
        dabRenderer?.clearSecondaryShape()
        secondaryShapeTexture?.release()
        secondaryShapeTexture = null
        secondaryShapePath = null
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
        val renderer = ribbonMeshRenderer ?: return
        if (renderedRibbonMeshVersion == capsuleEmitter.ribbonMeshVersion) return
        val mesh = capsuleEmitter.buildRibbonMesh() ?: return

        // A geometric mesh is redrawn as a whole. Clearing avoids building up
        // the same triangles on every display frame.
        val previewTarget = if (strokeBrush.effectiveStrokeRenderMode == StrokeRenderMode.NON_BUILDUP) {
            strokeCoveragePreviewTarget
        } else strokePreviewTarget
        if (previewTarget.textureId == 0) return
        previewTarget.clear(0f, 0f, 0f, 0f)
        capsulePreviewInitialized = true
        renderedRibbonMeshVersion = capsuleEmitter.ribbonMeshVersion

        layerStack.camera.snapshot().buildCanvasToClip(
            screenWidth.toFloat(),
            screenHeight.toFloat(),
            canvasToPreviewMatrix,
        )
        if (strokeBrush.effectiveStrokeRenderMode == StrokeRenderMode.NON_BUILDUP) {
            renderer.draw(
                target = strokeCoveragePreviewTarget,
                width = screenWidth,
                height = screenHeight,
                matrix = canvasToPreviewMatrix,
                mesh = mesh,
                color = activeStrokeColorLinear,
                flow = strokeBrush.flow,
                coverageOnly = true,
            )
        } else {
            renderer.draw(
                target = strokePreviewTarget,
                width = screenWidth,
                height = screenHeight,
                matrix = canvasToPreviewMatrix,
                mesh = mesh,
                color = activeStrokeColorLinear,
                flow = strokeBrush.flow,
                coverageOnly = false,
            )
        }
    }

    private fun captureSmudgeBackground() {
        val comp = compositor ?: return
        val geom = geometry ?: return
        smudgeTarget.clear(0f, 0f, 0f, 0f)
        smudgeTarget.bind()
        GLES30.glViewport(0, 0, smudgeTarget.width, smudgeTarget.height)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        comp.render(
            destination = smudgeTarget,
            geometry = geom,
            layers = layerStack,
            activeLayerId = -1L,
            strokeTextureId = 0,
            strokeCoverageTextureId = 0,
            strokeIsScreenSpace = false,
            strokeMode = StrokeRenderMode.NORMAL_BUILDUP,
            strokeErase = false,
            strokeColorLinear = activeStrokeColorLinear,
            strokeOpacity = 1f,
            canvasToClip = canvasToFboMatrix,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun commitCapsuleStroke(strokeBrush: BrushSettings) {
        val layer = layerStack.activeLayer() ?: return
        val renderer = ribbonMeshRenderer ?: return
        val mesh = capsuleEmitter.buildRibbonMesh() ?: return

        if (layer.isLocked) return
        if (!capsuleEmitter.hasStroke) return
        if (!computeCapsuleDirtyBounds(dirtyBounds)) return
        val blitter = strokeBlitter ?: return
        val strokeGeometry = geometry ?: return

        if (strokeBrush.effectiveStrokeRenderMode == StrokeRenderMode.NON_BUILDUP) {
            val nonBuildupBlitter = nonBuildupStrokeRenderer ?: return
            if (!ensureCoverageTarget()) return
            strokeCoverageTarget.clear(0f, 0f, 0f, 0f)
            val drawn = renderer.draw(
                target = strokeCoverageTarget,
                width = layerStack.canvasWidth,
                height = layerStack.canvasHeight,
                matrix = canvasToFboMatrix,
                mesh = mesh,
                color = activeStrokeColorLinear,
                flow = strokeBrush.flow,
                coverageOnly = true,
            )
            if (!drawn) {
                if (BuildConfig.DEBUG) {
                    Log.w(FRAME_DIAG_TAG, "ribbon commit skipped: mesh over limit")
                }
                return
            }
            if (strokeCommitter.commitNonBuildup(
                layer = layer,
                geometry = strokeGeometry,
                blitter = nonBuildupBlitter,
                coverageTarget = strokeCoverageTarget,
                colorLinear = activeStrokeColorLinear,
                dirtyBounds = dirtyBounds,
                canvasWidth = layerStack.canvasWidth,
                canvasHeight = layerStack.canvasHeight,
                opacity = strokeBrush.opacity,
                erase = activeStrokeErase,
            ) is StrokeCommitter.CommitResult.Queued) {
                publishState()
            }
            return
        }

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

        val drawn = renderer.draw(
            target = strokeTarget,
            width = layerStack.canvasWidth,
            height = layerStack.canvasHeight,
            matrix = canvasToFboMatrix,
            mesh = mesh,
            color = activeStrokeColorLinear,
            flow = strokeBrush.flow,
            coverageOnly = false,
        )
        if (!drawn) {
            if (BuildConfig.DEBUG) {
                Log.w(FRAME_DIAG_TAG, "ribbon commit skipped: mesh over limit")
            }
            return
        }

        if (strokeCommitter.commit(
            layer = layer,
            geometry = strokeGeometry,
            blitter = blitter,
            dirtyBounds = dirtyBounds,
            canvasWidth = layerStack.canvasWidth,
            canvasHeight = layerStack.canvasHeight,
            opacity = strokeBrush.opacity,
            erase = activeStrokeErase,
        ) is StrokeCommitter.CommitResult.Queued) {
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
        /** Bounded GL-thread work per frame; ordered input is never dropped. */
        private const val MAX_INPUT_BATCHES_PER_FRAME = 6
        /** Protects the render budget even when one backlog batch is expensive. */
        private const val MAX_INPUT_PROCESS_NANOS = 2_000_000L
        /** Emit a single aggregate timing record per second in debug builds. */
        private const val FRAME_LOG_INTERVAL_NANOS = 1_000_000_000L
        /** Per-MOVE Logcat I/O is expensive on debug tablet builds. */
        private const val LOG_STAMP_PREVIEW = false
        private const val BRUSH_DIAG_TAG = "BrushDiag"
        private const val INPUT_LATENCY_TAG = "InputLatency"
        private const val FRAME_DIAG_TAG = "WetInkFrame"
        private const val THUMBNAIL_TAG = "ThumbnailBuild"
        private const val AA_MARGIN_PX = 4f
        private const val STAMP_DIRTY_MARGIN_PX = 4f
        /** Base extra region committed around a WET wash past its source dabs. */
        private const val WET_WASH_MARGIN_PX = 48
        /** Per-unit-of-spread extra diagonal margin for wet diffusion. */
        private const val WET_WASH_MARGIN_SCALE = 0.04f
    }
}
