package com.wetinknext.engine.core

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.wetinknext.engine.brush.TextureLoader
import com.wetinknext.engine.brush.LoadedBrushTexture

/** Thread boundary between Compose commands and the GL-owned paint engine. */
class PaintSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    private val engineRenderer = EngineRenderer()

    private val textureLoader = TextureLoader(context)
    private var grainGeneration = 0L

    var onTextureError: ((String, Throwable) -> Unit)? = null

    var onEditorStateChange: ((EditorUiState) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
        setRenderer(engineRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        engineRenderer.onStateChange = { state ->
            post { onEditorStateChange?.invoke(state) }
        }
        engineRenderer.setOnSecondaryPointerDown {
            engineRenderer.requestCancelFromInput()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        engineRenderer.onTouchEvent(event) || super.onTouchEvent(event)

    fun requestState() = queueEvent { engineRenderer.requestState() }

    fun undo() = queueEvent { engineRenderer.undo() }

    fun redo() = queueEvent { engineRenderer.redo() }

    fun setActiveLayer(id: Long) = queueEvent { engineRenderer.setActiveLayer(id) }

    fun addLayer() = queueEvent { engineRenderer.addLayer() }

    fun removeLayer(id: Long) = queueEvent { engineRenderer.removeLayer(id) }

    fun setLayerVisible(id: Long, visible: Boolean) =
        queueEvent { engineRenderer.setLayerVisible(id, visible) }

    fun setLayerOpacity(id: Long, opacity: Float) =
        queueEvent { engineRenderer.setLayerOpacity(id, opacity) }

    fun setBrushSize(px: Float) = queueEvent { engineRenderer.setBrushSize(px) }

    fun setBrushOpacity(opacity: Float) = queueEvent { engineRenderer.setBrushOpacity(opacity) }

    fun loadGrainTexture(
        path: String,
        scale: Float = 1f,
        canvasLocked: Boolean = true,
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
                        depth = depth,
                        contrast = contrast,
                    )
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
        }
    }

    override fun onPause() {
        engineRenderer.cancelActiveStroke()
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        grainGeneration++
        textureLoader.shutdown()

        queueEvent { engineRenderer.releaseGlObjects() }
        super.onDetachedFromWindow()
    }
}
