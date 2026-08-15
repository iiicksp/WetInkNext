package com.wetinknext.engine.canvas

import com.wetinknext.engine.gl.RenderTarget

/** One paintable document layer and its private GPU target. */
class PaintLayer(
    val id: Long,
    name: String,
) {
    val metadata = LayerMetadata(id = id, name = name)
    val gpuTarget = RenderTarget()

    /** Shared by the full-size target and future tile diagnostics. */
    val tileLabel: String get() = "layer-$id"

    /**
     * Prepared tiled backing store. The compositor still reads [gpuTarget] in
     * this migration stage, so merely enabling this owner allocates no tiles.
     */
    var tileResources: LayerTileResources? = null
        private set

    val tileGrid: TileGrid? get() = tileResources?.grid
    val loadedTileCount: Int get() = tileResources?.loadedTileCount ?: 0

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
        if (!allocator.create(target, tileLabel, width, height)) return false
        target.clear(0f, 0f, 0f, 0f)
        created = true
        return true
    }

    /**
     * Attaches an initially empty tiled store. Repeating the call for the same
     * canvas keeps existing resident tiles; resizing releases them first.
     * Render/GL thread only.
     */
    fun enableTiledStorage(
        allocator: LayerResourceAllocator,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val existing = tileResources
        if (existing != null && existing.grid.matches(canvasWidth, canvasHeight)) return
        existing?.releaseAll()
        tileResources = LayerTileResources(
            allocator = allocator,
            grid = TileGrid(canvasWidth, canvasHeight),
            label = tileLabel,
        )
    }

    /** Releases resident tile textures and detaches the tiled store. */
    fun disableTiledStorage() {
        tileResources?.releaseAll()
        tileResources = null
    }

    fun clear() {
        if (created) target.clear(0f, 0f, 0f, 0f)
    }

    fun release(allocator: LayerResourceAllocator) {
        disableTiledStorage()
        allocator.release(target)
        created = false
    }

    /** Keeps the tile lattice through context loss while dropping stale GL ids. */
    fun resetGlHandles() {
        gpuTarget.resetHandles()
        tileResources?.resetHandles()
        created = false
    }

    companion object {
        /** Debug-only stage 3C switch; false restores the pre-tile stroke path. */
        var useTiledStrokeMirror = true
    }
}
