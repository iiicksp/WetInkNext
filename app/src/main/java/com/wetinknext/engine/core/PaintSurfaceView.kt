package com.wetinknext.engine.core

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.wetinknext.engine.brush.BrushSettings
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
        renderMode = RENDERMODE_WHEN_DIRTY
        engineRenderer.onStateChange = { state ->
            post { onEditorStateChange?.invoke(state) }
            requestRender()
        }
        engineRenderer.setOnSecondaryPointerDown {
            engineRenderer.requestCancelFromInput()
            requestRender()
        }
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

    fun setActiveLayer(id: Long) = queueEvent { 
        engineRenderer.setActiveLayer(id)
        requestRender()
    }

    fun addLayer() = queueEvent { 
        engineRenderer.addLayer()
        requestRender()
    }

    fun removeLayer(id: Long) = queueEvent { 
        engineRenderer.removeLayer(id)
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

    fun setBrushSize(px: Float) = queueEvent { 
        engineRenderer.setBrushSize(px)
        requestRender()
    }

    fun setBrushOpacity(opacity: Float) = queueEvent { 
        engineRenderer.setBrushOpacity(opacity)
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

    fun setBrushColor(color: androidx.compose.ui.graphics.Color) = queueEvent {
        engineRenderer.setBrushColor(color)
        requestRender()
    }

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

    override fun onPause() {
        engineRenderer.cancelActiveStroke()
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        grainGeneration++
        textureLoader.shutdown()
        
        // Очищаем listeners, чтобы избежать утечек или вызовов в пустоту
        onTextureError = null
        onEditorStateChange = null
        engineRenderer.onStateChange = null
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
