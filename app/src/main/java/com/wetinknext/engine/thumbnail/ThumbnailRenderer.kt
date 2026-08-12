package com.wetinknext.engine.thumbnail

import android.opengl.GLES30
import com.wetinknext.engine.canvas.Compositor
import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.canvas.PaintLayer
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GL-thread renderer for document thumbnails.
 *
 * It renders directly from document render targets, never from the surface
 * framebuffer. Camera position, zoom, editor chrome and Android system UI can
 * therefore never leak into a preview.
 */
class ThumbnailRenderer {
    private val target = RenderTarget()
    private val compositor = Compositor()
    private var geometry: CanvasGeometry? = null
    private var geometryWidth = 0
    private var geometryHeight = 0
    private val canvasToClip = FloatArray(16)
    private var readBuffer: ByteBuffer? = null
    private var created = false

    /**
     * Returns top-to-bottom RGBA8 bytes for one layer.
     *
     * A hidden layer still receives a preview of its own pixels; the visibility
     * flag belongs to project composition, whereas a layer thumbnail is used to
     * identify that layer in the layers panel. Layer opacity is preserved.
     */
    fun renderLayer(
        layer: PaintLayer,
        outputWidth: Int,
        outputHeight: Int,
    ): ByteArray {
        GlCheck.checkOnGlThread()
        require(outputWidth > 0 && outputHeight > 0) { "Thumbnail size must be positive" }
        if (!layer.created || layer.target.width <= 0 || layer.target.height <= 0) {
            return ByteArray(outputWidth * outputHeight * RGBA_BYTES_PER_PIXEL)
        }

        prepare(
            canvasWidth = layer.target.width,
            canvasHeight = layer.target.height,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
        )
        target.clear(0f, 0f, 0f, 0f)
        target.bind()
        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        try {
            compositor.renderLayer(
                geometry = checkNotNull(geometry),
                layer = layer,
                canvasWidth = layer.target.width,
                canvasHeight = layer.target.height,
                canvasToClip = canvasToClip,
            )
            return readTopDownRgba(outputWidth, outputHeight)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    /**
     * Returns a top-to-bottom RGBA8 image of all visible layers, from the
     * bottom of [layers] to the top. The compositor applies every layer's
     * opacity and follows the same blend-policy path as the on-canvas view.
     */
    fun renderProject(
        layers: LayerStack,
        outputWidth: Int,
        outputHeight: Int,
    ): ByteArray {
        GlCheck.checkOnGlThread()
        require(outputWidth > 0 && outputHeight > 0) { "Thumbnail size must be positive" }
        require(layers.canvasWidth > 0 && layers.canvasHeight > 0) {
            "Cannot render a thumbnail for an empty canvas"
        }

        prepare(
            canvasWidth = layers.canvasWidth,
            canvasHeight = layers.canvasHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
        )
        target.clear(0f, 0f, 0f, 0f)
        target.bind()
        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        try {
            compositor.render(
                geometry = checkNotNull(geometry),
                layers = layers,
                activeLayerId = NO_ACTIVE_LAYER,
                strokeTextureId = 0,
                strokeErase = false,
                canvasToClip = canvasToClip,
                strokeOpacity = 1f,
            )
            return readTopDownRgba(outputWidth, outputHeight)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    /** Releases GL objects. Call while the context is current. */
    fun release() {
        GlCheck.checkOnGlThread()
        readBuffer = null
        target.release()
        geometry?.release()
        geometry = null
        geometryWidth = 0
        geometryHeight = 0
        if (created) compositor.release()
        created = false
    }

    private fun prepare(
        canvasWidth: Int,
        canvasHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ) {
        if (!created) {
            compositor.create()
            created = true
        }
        if (geometry == null || geometryWidth != canvasWidth || geometryHeight != canvasHeight) {
            geometry?.release()
            geometry = CanvasGeometry().also { it.create(canvasWidth, canvasHeight) }
            geometryWidth = canvasWidth
            geometryHeight = canvasHeight
        }
        if (target.width != outputWidth || target.height != outputHeight) {
            target.create(outputWidth, outputHeight, preferHalfFloat = false)
        }
        buildAspectFitMatrix(canvasWidth, canvasHeight, outputWidth, outputHeight, canvasToClip)
    }

    private fun readTopDownRgba(width: Int, height: Int): ByteArray {
        val rowBytes = width * RGBA_BYTES_PER_PIXEL
        val byteCount = rowBytes * height
        val buffer = obtainReadBuffer(byteCount)
        buffer.clear()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        try {
            GLES30.glReadPixels(
                0,
                0,
                width,
                height,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                buffer,
            )
            GlCheck.noError("ThumbnailRenderer glReadPixels")

            val pixels = ByteArray(byteCount)
            for (destinationRow in 0 until height) {
                val sourceOffset = (height - 1 - destinationRow) * rowBytes
                buffer.position(sourceOffset)
                buffer.get(pixels, destinationRow * rowBytes, rowBytes)
            }
            return pixels
        } finally {
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        }
    }

    private fun obtainReadBuffer(size: Int): ByteBuffer {
        val current = readBuffer
        if (current != null && current.capacity() >= size) return current
        return ByteBuffer.allocateDirect(size)
            .order(ByteOrder.nativeOrder())
            .also { readBuffer = it }
    }

    private fun buildAspectFitMatrix(
        canvasWidth: Int,
        canvasHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        out: FloatArray,
    ) {
        val canvasAspect = canvasWidth.toFloat() / canvasHeight.toFloat()
        val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()
        val scaleX = if (canvasAspect < outputAspect) canvasAspect / outputAspect else 1f
        val scaleY = if (canvasAspect > outputAspect) outputAspect / canvasAspect else 1f

        out.fill(0f)
        out[0] = 2f * scaleX / canvasWidth.toFloat()
        out[5] = 2f * scaleY / canvasHeight.toFloat()
        out[10] = 1f
        out[12] = -scaleX
        out[13] = -scaleY
        out[15] = 1f
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
        const val NO_ACTIVE_LAYER = -1L
    }
}
