package com.wetinknext.engine.core

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent

/** Thread boundary between Compose commands and the GL-owned paint engine. */
class PaintSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    private val engineRenderer = EngineRenderer()

    var onEditorStateChange: ((EditorUiState) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
        setRenderer(engineRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        engineRenderer.onStateChange = { state ->
            post { onEditorStateChange?.invoke(state) }
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

    override fun onPause() {
        engineRenderer.cancelActiveStroke()
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        queueEvent { engineRenderer.releaseGlObjects() }
        super.onDetachedFromWindow()
    }
}
