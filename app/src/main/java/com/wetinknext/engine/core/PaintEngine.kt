package com.wetinknext.engine.core

import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.engine.gl.RenderTarget

/** Lightweight document facade retained for engine callers outside the renderer. */
class PaintEngine {
    val layerStack = LayerStack()
    val camera: Camera get() = layerStack.camera
    val canvasTarget: RenderTarget get() = checkNotNull(layerStack.activeLayer()).target

    val canvasWidth: Int get() = layerStack.canvasWidth
    val canvasHeight: Int get() = layerStack.canvasHeight
    var initialized = false
        private set

    fun create(caps: GlCaps, width: Int, height: Int) {
        layerStack.create(caps, width, height)
        initialized = true
    }

    fun addLayer(name: String): Long = layerStack.addLayer(name).id

    fun removeLayer(id: Long): Boolean = layerStack.removeLayer(id) != null

    fun setActiveLayer(id: Long): Boolean = layerStack.setActive(id)

    fun release() {
        layerStack.release()
        initialized = false
    }
}
