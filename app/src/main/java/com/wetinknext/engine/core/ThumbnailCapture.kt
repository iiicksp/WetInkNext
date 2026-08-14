package com.wetinknext.engine.core

import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.thumbnail.ThumbnailRenderer

/** Captures only the composited canvas; Compose panels and system UI are excluded. */
class ThumbnailCapture {
    data class Rgba(val width: Int, val height: Int, val pixels: ByteArray)
    private val renderer = ThumbnailRenderer()

    fun capture(layers: LayerStack): Rgba {
        GlCheck.checkOnGlThread()
        require(layers.canvasWidth > 0 && layers.canvasHeight > 0) { "Cannot capture an empty canvas" }
        val longestEdge = 384
        val scale = longestEdge.toFloat() / maxOf(layers.canvasWidth, layers.canvasHeight).toFloat()
        val captureWidth = (layers.canvasWidth * scale).toInt().coerceAtLeast(1)
        val captureHeight = (layers.canvasHeight * scale).toInt().coerceAtLeast(1)
        return Rgba(
            captureWidth,
            captureHeight,
            renderer.renderProject(layers, captureWidth, captureHeight),
        )
    }

    fun resetHandles() = renderer.resetHandles()

    fun release() = renderer.release()
}
