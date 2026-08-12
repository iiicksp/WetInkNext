package com.wetinknext.engine.canvas

import com.wetinknext.engine.core.Camera
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.domain.document.ProjectDocument

/**
 * Render-thread-owned ordered collection of document layers.
 * Index 0 is the bottom-most layer and the final item is the top-most layer.
 */
class LayerStack {
    val camera = Camera()

    private val layers = mutableListOf<PaintLayer>()
    private var nextId = 1L
    private var documentUsesHalfFloat = false

    var activeLayerId: Long = NO_LAYER
        private set
    var canvasWidth: Int = 0
        private set
    var canvasHeight: Int = 0
        private set

    val count: Int get() = layers.size

    fun allLayers(): List<PaintLayer> = layers

    fun activeLayer(): PaintLayer? = findLayerById(activeLayerId)

    fun findLayerById(id: Long): PaintLayer? = layers.firstOrNull { it.id == id }

    fun indexOfLayer(id: Long): Int = layers.indexOfFirst { it.id == id }

    /** Creates the runtime stack from persisted layer metadata. */
    fun create(caps: GlCaps, document: ProjectDocument) {
        release()
        canvasWidth = document.width
        canvasHeight = document.height
        documentUsesHalfFloat = caps.supportsHalfFloatColorBuffer

        document.layers.forEachIndexed { index, source ->
            val layer = PaintLayer(source.id, source.name).also {
                it.isVisible = source.visible
                it.isLocked = source.locked
                it.opacity = source.opacity
                it.blendMode = source.blendMode
                it.version = source.thumbnailVersion
                it.create(canvasWidth, canvasHeight, documentUsesHalfFloat)
            }
            // Tile restoration is a later persistence step. Preserve the new
            // document's white background until a stored pixel payload is loaded.
            if (index == 0 && source.locked) {
                layer.target.clear(1f, 1f, 1f, 1f)
            }
            layers += layer
        }

        activeLayerId = document.activeLayerId ?: layers.last().id
        nextId = (layers.maxOf { it.id } + 1L).coerceAtLeast(1L)
    }

    /** Creates the locked opaque white background and one transparent drawing layer. */
    fun create(caps: GlCaps, width: Int, height: Int) {
        require(width > 0 && height > 0)
        release()
        canvasWidth = width
        canvasHeight = height
        documentUsesHalfFloat = caps.supportsHalfFloatColorBuffer

        val background = newLayer("Фон", documentUsesHalfFloat).also {
            it.isLocked = true
            it.target.clear(1f, 1f, 1f, 1f)
        }
        layers += background

        val drawing = newLayer("Слой 1", documentUsesHalfFloat)
        layers += drawing
        activeLayerId = drawing.id
    }

    fun addLayer(name: String, insertAt: Int = layers.size): PaintLayer {
        check(canvasWidth > 0 && canvasHeight > 0) { "LayerStack has not been created" }
        val layer = newLayer(name, documentUsesHalfFloat)
        layers.add(insertAt.coerceIn(0, layers.size), layer)
        activeLayerId = layer.id
        return layer
    }

    /** A document must retain at least one layer; locked layers cannot be removed. */
    fun removeLayer(id: Long): PaintLayer? {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0 || layers.size <= 1) return null
        val layer = layers[index]
        if (layer.isLocked) return null

        layers.removeAt(index)
        layer.release()
        if (activeLayerId == id) {
            activeLayerId = layers[index.coerceAtMost(layers.lastIndex)].id
        }
        return layer
    }

    fun setActive(id: Long): Boolean {
        if (findLayerById(id) == null) return false
        activeLayerId = id
        return true
    }

    fun moveLayer(id: Long, delta: Int): Boolean {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return false
        val newIndex = (index + delta).coerceIn(0, layers.lastIndex)
        if (newIndex == index) return false
        val layer = layers.removeAt(index)
        layers.add(newIndex, layer)
        return true
    }

    fun release() {
        layers.forEach(PaintLayer::release)
        layers.clear()
        activeLayerId = NO_LAYER
        canvasWidth = 0
        canvasHeight = 0
        documentUsesHalfFloat = false
    }

    private fun newLayer(name: String, useHalfFloat: Boolean): PaintLayer =
        PaintLayer(nextId++, name).also { it.create(canvasWidth, canvasHeight, useHalfFloat) }

    private companion object {
        const val NO_LAYER = -1L
    }
}
