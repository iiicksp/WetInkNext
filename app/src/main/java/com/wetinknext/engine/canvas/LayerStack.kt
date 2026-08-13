package com.wetinknext.engine.canvas

import com.wetinknext.engine.core.Camera
import com.wetinknext.engine.gl.BudgetedTargets
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
    private var targetOwner: BudgetedTargets? = null

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
    fun create(caps: GlCaps, document: ProjectDocument, targets: BudgetedTargets) {
        release()
        targetOwner = targets
        canvasWidth = document.width
        canvasHeight = document.height
        documentUsesHalfFloat = caps.supportsHalfFloatColorBuffer

        try {
            document.layers.forEachIndexed { index, source ->
                val layer = PaintLayer(source.id, source.name).also {
                    it.isVisible = source.visible
                    it.isLocked = source.locked
                    it.opacity = source.opacity
                    it.blendMode = source.blendMode
                    it.version = source.thumbnailVersion
                }
                check(layer.create(targets, canvasWidth, canvasHeight, documentUsesHalfFloat)) {
                    "GPU budget cannot fit mandatory layer ${source.id}"
                }
                layers += layer
                // Tile restoration is a later persistence step. Preserve the new
                // document's white background until a stored pixel payload is loaded.
                if (index == 0 && source.locked) {
                    layer.target.clear(1f, 1f, 1f, 1f)
                }
            }
        } catch (error: Throwable) {
            release()
            throw error
        }

        activeLayerId = document.activeLayerId ?: layers.last().id
        nextId = (layers.maxOf { it.id } + 1L).coerceAtLeast(1L)
    }

    /** Creates the locked opaque white background and one transparent drawing layer. */
    fun create(caps: GlCaps, width: Int, height: Int, targets: BudgetedTargets) {
        require(width > 0 && height > 0)
        release()
        targetOwner = targets
        canvasWidth = width
        canvasHeight = height
        documentUsesHalfFloat = caps.supportsHalfFloatColorBuffer

        val background = checkNotNull(newLayer("Фон", documentUsesHalfFloat)).also {
            it.isLocked = true
            it.target.clear(1f, 1f, 1f, 1f)
        }
        layers += background

        val drawing = checkNotNull(newLayer("Слой 1", documentUsesHalfFloat))
        layers += drawing
        activeLayerId = drawing.id
    }

    fun addLayer(name: String, insertAt: Int = layers.size): PaintLayer? {
        check(canvasWidth > 0 && canvasHeight > 0) { "LayerStack has not been created" }
        val layer = newLayer(name, documentUsesHalfFloat) ?: return null
        layers.add(insertAt.coerceIn(0, layers.size), layer)
        activeLayerId = layer.id
        return layer
    }

    /** Restores a previously removed layer with its stable document id. */
    fun restoreLayer(
        id: Long,
        name: String,
        insertAt: Int,
        visible: Boolean,
        locked: Boolean,
        opacity: Float,
        blendMode: BlendMode,
        version: Long,
    ): PaintLayer? {
        if (findLayerById(id) != null) return null
        val layer = PaintLayer(id, name)
        if (!layer.create(checkNotNull(targetOwner), canvasWidth, canvasHeight, documentUsesHalfFloat)) {
            return null
        }
        layer.isVisible = visible
        layer.isLocked = locked
        layer.opacity = opacity.coerceIn(0f, 1f)
        layer.blendMode = blendMode
        layer.version = version
        layers.add(insertAt.coerceIn(0, layers.size), layer)
        nextId = maxOf(nextId, id + 1L)
        activeLayerId = id
        return layer
    }

    /** A document must retain at least one layer; locked layers cannot be removed. */
    fun removeLayer(id: Long): PaintLayer? {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0 || layers.size <= 1) return null
        val layer = layers[index]
        if (layer.isLocked) return null

        layers.removeAt(index)
        layer.release(checkNotNull(targetOwner))
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
        targetOwner?.let { targets -> layers.forEach { it.release(targets) } }
        layers.clear()
        activeLayerId = NO_LAYER
        canvasWidth = 0
        canvasHeight = 0
        documentUsesHalfFloat = false
        targetOwner = null
    }

    private fun newLayer(name: String, useHalfFloat: Boolean): PaintLayer? {
        val layer = PaintLayer(nextId, name)
        if (!layer.create(checkNotNull(targetOwner), canvasWidth, canvasHeight, useHalfFloat)) {
            return null
        }
        nextId++
        return layer
    }

    private companion object {
        const val NO_LAYER = -1L
    }
}
