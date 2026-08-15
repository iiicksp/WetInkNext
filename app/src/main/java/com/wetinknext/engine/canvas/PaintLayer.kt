package com.wetinknext.engine.canvas

import com.wetinknext.engine.gl.RenderTarget

/** One paintable document layer and its private GPU target. */
class PaintLayer(
    val id: Long,
    name: String,
) {
    val metadata = LayerMetadata(id = id, name = name)
    val gpuTarget = RenderTarget()
    val tileLabel: String get() = "layer-$id"

    var tileResources: LayerTileResources? = null
        private set
    val tileGrid: TileGrid? get() = tileResources?.grid
    val loadedTileCount: Int get() = tileResources?.loadedTileCount ?: 0

    /** Display source. FULL_TARGET remains the safe default until promotion succeeds. */
    var renderStorage: LayerRenderStorage = LayerRenderStorage.FULL_TARGET
        private set
    val isTiled: Boolean get() = renderStorage == LayerRenderStorage.TILED

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

    fun create(allocator: LayerResourceAllocator, width: Int, height: Int): Boolean {
        if (created && target.width == width && target.height == height) return true
        if (!allocator.create(target, tileLabel, width, height)) return false
        target.clear(0f, 0f, 0f, 0f)
        created = true
        return true
    }

    fun enableTiledStorage(allocator: LayerResourceAllocator, canvasWidth: Int, canvasHeight: Int) {
        val existing = tileResources
        if (existing != null && existing.grid.matches(canvasWidth, canvasHeight)) return
        existing?.releaseAll()
        tileResources = LayerTileResources(allocator, TileGrid(canvasWidth, canvasHeight), tileLabel)
    }

    /** Seeds all tiles from the compatibility target, then atomically switches display mode. */
    fun promoteToTiled(allocator: LayerResourceAllocator, canvasWidth: Int, canvasHeight: Int): Boolean {
        if (!created || target.width != canvasWidth || target.height != canvasHeight) return false
        enableTiledStorage(allocator, canvasWidth, canvasHeight)
        val resources = checkNotNull(tileResources)
        if (!LayerTileSeeder().seed(target, resources.grid, resources)) {
            resources.releaseAll()
            renderStorage = LayerRenderStorage.FULL_TARGET
            return false
        }
        renderStorage = LayerRenderStorage.TILED
        return true
    }

    fun demoteToFullTarget() {
        renderStorage = LayerRenderStorage.FULL_TARGET
    }

    fun disableTiledStorage() {
        tileResources?.releaseAll()
        tileResources = null
        renderStorage = LayerRenderStorage.FULL_TARGET
    }

    fun clear() {
        if (created) target.clear(0f, 0f, 0f, 0f)
        tileResources?.releaseAll()
    }

    fun release(allocator: LayerResourceAllocator) {
        disableTiledStorage()
        allocator.release(target)
        created = false
    }

    fun resetGlHandles() {
        gpuTarget.resetHandles()
        tileResources?.resetHandles()
        created = false
        renderStorage = LayerRenderStorage.FULL_TARGET
    }

    companion object {
        var useTiledStrokeMirror = true
    }
}
