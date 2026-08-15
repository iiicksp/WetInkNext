package com.wetinknext.engine.canvas

import com.wetinknext.engine.gl.RenderTarget

/** One paintable document layer and its private GPU target. */
class PaintLayer(
    val id: Long,
    name: String,
) {
    val metadata = LayerMetadata(id = id, name = name)
    val gpuTarget = RenderTarget()

    /** Compatibility accessors for render code during the gradual split. */
    val target: RenderTarget get() = gpuTarget
    var name: String
        get() = metadata.name
        set(value) { metadata.name = value }
    var isVisible: Boolean
        get() = metadata.isVisible
        set(value) { metadata.isVisible = value }
    var isLocked: Boolean
        get() = metadata.isLocked
        set(value) { metadata.isLocked = value }
    var opacity: Float
        get() = metadata.opacity
        set(value) { metadata.opacity = value.coerceIn(0f, 1f) }
    var blendMode: BlendMode
        get() = metadata.blendMode
        set(value) { metadata.blendMode = value }
    var version: Long
        get() = metadata.version
        set(value) { metadata.version = value }

    var created = false
        private set

    fun create(
        allocator: LayerResourceAllocator,
        width: Int,
        height: Int,
    ): Boolean {
        if (created && target.width == width && target.height == height) return true
        if (!allocator.create(target, "layer-$id", width, height)) return false
        target.clear(0f, 0f, 0f, 0f)
        created = true
        return true
    }

    fun clear() {
        if (created) target.clear(0f, 0f, 0f, 0f)
    }

    fun release(allocator: LayerResourceAllocator) {
        allocator.release(target)
        created = false
    }
}
