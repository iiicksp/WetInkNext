package com.wetinknext.engine.core

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.engine.brush.BrushSettings
import com.wetinknext.engine.brush.TextureLoader
import com.wetinknext.engine.brush.LoadedBrushTexture
import com.wetinknext.engine.thumbnail.ThumbnailBuildResult
import com.wetinknext.engine.thumbnail.ThumbnailPreviewFiles
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Thread boundary between Compose commands and the GL-owned paint engine. */
class PaintSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    document: ProjectDocument = ProjectDocument.newUntitled(),
    layerTiles: Map<Long, ByteArray> = emptyMap(),
    layerPreviews: Map<Long, ByteArray> = emptyMap(),
) : GLSurfaceView(context, attrs) {
    private val thumbnailOutputDirectory = File(context.cacheDir, "wetink-thumbnails/${document.id}")
    private val engineRenderer = EngineRenderer(
        projectDocument = document,
        initialLayerTiles = layerTiles,
        thumbnailOutputDirectory = thumbnailOutputDirectory,
    )

    private val textureLoader = TextureLoader(context)
    private val thumbnailRestoreExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var grainGeneration = 0L
    private var shapeGeneration = 0L

    var onTextureError: ((String, Throwable) -> Unit)? = null

    var onEditorStateChange: ((EditorUiState) -> Unit)? = null
    var onProjectDocumentChange: ((ProjectDocument) -> Unit)? = null
    private var documentSessionLoaded = false
    var onDocumentSessionLoaded: (() -> Unit)? = null
        set(value) {
            field = value
            if (documentSessionLoaded && value != null) post(value)
        }
    var onDirtyLayerTiles: ((ProjectDocument, Map<Long, ByteArray>, Map<Long, Set<com.wetinknext.engine.undo.TileCoord>>) -> Unit)? = null
    private val pendingThumbnailCapture = LatestValueDelivery<ThumbnailCapture.Rgba>()
    var onThumbnailCaptured: ((ThumbnailCapture.Rgba) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) {
                // setRenderer() may initialize EGL before AndroidView's caller
                // has installed its listener. Replay the latest capture then.
                post(::dispatchPendingThumbnail)
            }
        }
    /** File previews that were encoded on the worker and published to cache. */
    var onThumbnailBuildSaved: ((ThumbnailBuildResult) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
        setRenderer(engineRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        engineRenderer.onStateChange = { state ->
            post { onEditorStateChange?.invoke(state) }
            requestRender()
        }
        engineRenderer.onProjectDocumentChange = { document ->
            post { onProjectDocumentChange?.invoke(document) }
        }
        engineRenderer.onDocumentSessionLoaded = {
            documentSessionLoaded = true
            post { onDocumentSessionLoaded?.invoke() }
        }
        engineRenderer.onDirtyLayerTiles = { document, payloads, dirty -> post { onDirtyLayerTiles?.invoke(document, payloads, dirty) } }
        engineRenderer.onThumbnailCaptured = { image ->
            pendingThumbnailCapture.offer(image)
            post(::dispatchPendingThumbnail)
        }
        engineRenderer.onThumbnailBuildSaved = { result ->
            // The worker has published files, but document metadata and layer
            // UI state remain GL-owned. Update those first, then notify the
            // app-level persistence bridge on the main thread.
            post {
                queueEvent {
                    engineRenderer.applyThumbnailBuild(result)
                    requestRender()
                    post { onThumbnailBuildSaved?.invoke(result) }
                }
            }
        }
        engineRenderer.onInputRenderRequested = { requestRender() }
        restoreStoredLayerPreviews(layerPreviews)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = engineRenderer.onTouchEvent(event)
        if (handled) requestRender()
        return handled || super.onTouchEvent(event)
    }

    fun requestState() = queueEvent { 
        engineRenderer.requestState()
        requestRender()
    }

    fun undo() = queueEvent { 
        engineRenderer.undo()
        requestRender()
    }

    fun redo() = queueEvent { 
        engineRenderer.redo()
        requestRender()
    }

    fun acknowledgeSavedTiles(saved: Map<Long, Set<com.wetinknext.engine.undo.TileCoord>>) = queueEvent {
        engineRenderer.acknowledgeSavedTiles(saved)
    }

    fun setActiveLayer(id: Long) = queueEvent { 
        engineRenderer.setActiveLayer(id)
        requestRender()
    }

    fun addLayer() = queueEvent { 
        engineRenderer.addLayer()
        requestRender()
    }

    fun duplicateLayer(id: Long) = queueEvent {
        engineRenderer.duplicateLayer(id)
        requestRender()
    }

    fun removeLayer(id: Long) = queueEvent { 
        engineRenderer.removeLayer(id)
        requestRender()
    }

    fun moveLayer(id: Long, delta: Int) = queueEvent {
        engineRenderer.moveLayer(id, delta)
        requestRender()
    }

    fun setLayerVisible(id: Long, visible: Boolean) =
        queueEvent { 
            engineRenderer.setLayerVisible(id, visible)
            requestRender()
        }

    fun setLayerOpacity(id: Long, opacity: Float) =
        queueEvent { 
            engineRenderer.setLayerOpacity(id, opacity)
            requestRender()
        }

    fun clearActiveLayer() = queueEvent {
        engineRenderer.clearActiveLayer()
        requestRender()
    }

    fun toggleCanvasMirror() = queueEvent {
        engineRenderer.toggleCanvasMirror()
        requestRender()
    }

    fun setCanvasBackdrop(backdropArgb: Int, gridArgb: Int, mode: CanvasBackdropMode) = queueEvent {
        engineRenderer.setCanvasBackdrop(backdropArgb, gridArgb, mode)
        requestRender()
    }

    fun setBrushSize(px: Float) = queueEvent { 
        engineRenderer.setBrushSize(px)
        requestRender()
    }

    fun setBrushOpacity(opacity: Float) = queueEvent { 
        engineRenderer.setBrushOpacity(opacity)
        requestRender()
    }

    fun setEraserEnabled(enabled: Boolean) = queueEvent {
        engineRenderer.setEraserEnabled(enabled)
        requestRender()
    }

    fun applyBrush(settings: BrushSettings) = queueEvent {
        engineRenderer.applyBrush(settings)
        requestRender()
    }

    fun setBrushSettings(settings: BrushSettings) = queueEvent {
        engineRenderer.setBrushSettings(settings)
        requestRender()
    }

    /**
     * Captures a full-resolution export snapshot on the GL thread and delivers
     * it on the UI thread. Returns null when a stroke is in progress or the
     * canvas is empty (the UI should retry after the pen is lifted).
     */
    fun requestExportSnapshot(callback: (com.wetinknext.engine.export.ExportSnapshot?) -> Unit) = queueEvent {
        val snapshot = engineRenderer.requestExportSnapshot()
        post { callback(snapshot) }
    }

    /**
     * Renders a real brush sample (library panel / brush studio) on the GL
     * thread and delivers the RGBA bitmap on the UI thread. [callback] receives
     * null when the preview could not be rendered.
     */
    fun requestBrushPreview(
        settings: BrushSettings,
        callback: (com.wetinknext.engine.brush.BrushPreviewRenderer.PreviewResult?) -> Unit,
    ) = queueEvent {
        val result = engineRenderer.renderBrushPreview(settings)
        post { callback(result) }
    }

    // ---- Lasso selection + transform proxies ----

    // ---- Animation proxies ----

    fun toggleAnimationActive() = queueEvent {
        engineRenderer.toggleAnimationActive()
        requestRender()
    }

    fun animationTogglePlay() = queueEvent {
        engineRenderer.animationTogglePlay()
        requestRender()
    }

    fun animationSelectFrame(id: Long) = queueEvent {
        engineRenderer.setAnimationFrame(id)
        requestRender()
    }

    fun animationSetDocument(document: com.wetinknext.domain.animation.AnimationDocument) = queueEvent {
        engineRenderer.setAnimationDocument(document)
        requestRender()
    }

    fun animationAddFrame() = queueEvent {
        engineRenderer.animationAddFrame()
        requestRender()
    }

    fun animationDuplicateFrame(id: Long) = queueEvent {
        engineRenderer.animationDuplicateFrame(id)
        requestRender()
    }

    fun animationDeleteFrame(id: Long) = queueEvent {
        engineRenderer.animationDeleteFrame(id)
        requestRender()
    }

    fun animationMoveFrame(id: Long, direction: Int) = queueEvent {
        engineRenderer.animationMoveFrame(id, direction)
        requestRender()
    }

    fun animationSetHold(id: Long, hold: Int) = queueEvent {
        engineRenderer.animationSetHold(id, hold)
        requestRender()
    }

    fun beginSelection(shape: com.wetinknext.engine.selection.SelectionShape) = queueEvent {
        engineRenderer.beginSelection(shape)
        requestRender()
    }

    fun setSelectionShape(shape: com.wetinknext.engine.selection.SelectionShape) = queueEvent {
        engineRenderer.setSelectionShape(shape)
        requestRender()
    }

    fun clearSelection() = queueEvent {
        engineRenderer.clearSelection()
        requestRender()
    }

    fun deleteSelection() = queueEvent {
        engineRenderer.deleteSelection()
        requestRender()
    }

    fun beginTransform() = queueEvent {
        engineRenderer.beginTransform()
        requestRender()
    }

    fun cancelTransform() = queueEvent {
        engineRenderer.cancelTransform()
        requestRender()
    }

    fun applyTransform() = queueEvent {
        engineRenderer.applyTransform()
        requestRender()
    }

    fun setTransformMode(uniform: Boolean) = queueEvent {
        engineRenderer.setTransformUniform(uniform)
        requestRender()
    }

    fun transformResetTransforms() = queueEvent {
        engineRenderer.transformReset()
        requestRender()
    }

    fun transformFlipH() = queueEvent {
        engineRenderer.transformFlipHorizontal()
        requestRender()
    }

    fun transformFlipV() = queueEvent {
        engineRenderer.transformFlipVertical()
        requestRender()
    }

    fun transformRotate45() = queueEvent {
        engineRenderer.transformRotate45()
        requestRender()
    }

    fun setBrushColor(color: androidx.compose.ui.graphics.Color) = queueEvent {
        engineRenderer.setBrushColor(color)
        requestRender()
    }

    fun loadGrainTexture(
        path: String,
        scale: Float = 1f,
        canvasLocked: Boolean = true,
        screenSpace: Boolean = false,
        depth: Float = 1f,
        contrast: Float = 1f,
    ) {
        val generation = ++grainGeneration

        textureLoader.loadAsync(
            path = path,
            onLoaded = { loaded ->
                queueEvent {
                    if (generation != grainGeneration) {
                        return@queueEvent
                    }

                    engineRenderer.applyLoadedGrain(
                        loaded = loaded,
                        scale = scale,
                        canvasLocked = canvasLocked,
                        screenSpace = screenSpace,
                        depth = depth,
                        contrast = contrast,
                    )
                    requestRender()
                }
            },
            onError = { error ->
                post {
                    onTextureError?.invoke(path, error)
                }
            },
        )
    }

    fun clearGrainTexture() {
        grainGeneration++

        queueEvent {
            engineRenderer.clearGrainTexture()
            requestRender()
        }
    }

    fun loadShapeTexture(
        path: String,
        reverse: Boolean = false,
        rgbToAlpha: Boolean = false,
    ) {
        val generation = ++shapeGeneration
        textureLoader.loadAsync(
            path = path,
            onLoaded = { loaded ->
                queueEvent {
                    if (generation != shapeGeneration) return@queueEvent
                    engineRenderer.applyLoadedShape(loaded, reverse, rgbToAlpha)
                    requestRender()
                }
            },
            onError = { error -> post { onTextureError?.invoke(path, error) } },
        )
    }

    fun clearShapeTexture() {
        shapeGeneration++
        queueEvent {
            engineRenderer.clearShapeTexture()
            requestRender()
        }
    }

    fun loadSecondaryShapeTexture(
        path: String,
        scale: Float = 1f,
    ) {
        val generation = ++shapeGeneration
        textureLoader.loadAsync(
            path = path,
            onLoaded = { loaded ->
                queueEvent {
                    if (generation != shapeGeneration) return@queueEvent
                    engineRenderer.applyLoadedSecondaryShape(loaded, scale)
                    requestRender()
                }
            },
            onError = { error -> post { onTextureError?.invoke(path, error) } },
        )
    }

    fun clearSecondaryShapeTexture() {
        shapeGeneration++
        queueEvent {
            engineRenderer.clearSecondaryShapeTexture()
            requestRender()
        }
    }

    private fun dispatchPendingThumbnail() {
        pendingThumbnailCapture.dispatchTo(onThumbnailCaptured)
    }

    /** Restores persisted WebP bytes off the main and GL threads. */
    private fun restoreStoredLayerPreviews(layerPreviews: Map<Long, ByteArray>) {
        if (layerPreviews.isEmpty()) return
        thumbnailRestoreExecutor.execute {
            val files = runCatching {
                ThumbnailPreviewFiles.restoreLayerPreviews(
                    outputDirectory = thumbnailOutputDirectory,
                    previews = layerPreviews,
                )
            }.getOrElse { error ->
                post { onTextureError?.invoke("layer previews", error) }
                return@execute
            }
            queueEvent {
                engineRenderer.installRestoredLayerThumbnails(files)
                requestRender()
            }
        }
    }

    override fun onPause() {
        engineRenderer.cancelActiveStroke()
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        grainGeneration++
        shapeGeneration++
        textureLoader.shutdown()
        thumbnailRestoreExecutor.shutdownNow()
        
        // Очищаем listeners, чтобы избежать утечек или вызовов в пустоту
        onTextureError = null
        onEditorStateChange = null
        onProjectDocumentChange = null
        onDocumentSessionLoaded = null
        onDirtyLayerTiles = null
        onThumbnailCaptured = null
        onThumbnailBuildSaved = null
        pendingThumbnailCapture.clear()
        engineRenderer.onStateChange = null
        engineRenderer.onProjectDocumentChange = null
        engineRenderer.onDocumentSessionLoaded = null
        engineRenderer.onDirtyLayerTiles = null
        engineRenderer.onThumbnailCaptured = null
        engineRenderer.onThumbnailBuildSaved = null
        engineRenderer.onInputRenderRequested = null
        engineRenderer.setOnSecondaryPointerDown { }

        // Пытаемся освободить GL-объекты, но не рассчитываем на 100% успех здесь
        queueEvent { 
            // Отменяем активный штрих перед освобождением
            engineRenderer.cancelActiveStroke()
            engineRenderer.releaseGlObjects() 
        }
        super.onDetachedFromWindow()
    }
}
