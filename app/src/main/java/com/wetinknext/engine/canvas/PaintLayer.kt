package com.wetinknext.engine.canvas

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget

/** One paintable document layer and its private GPU target. */
class PaintLayer(val id: Long, name: String) {
    val metadata = LayerMetadata(id = id, name = name)
    val gpuTarget = RenderTarget()
    val tileLabel: String get() = "layer-$id"
    var tileResources: LayerTileResources? = null; private set
    val tileGrid: TileGrid? get() = tileResources?.grid
    val loadedTileCount: Int get() = tileResources?.loadedTileCount ?: 0
    var renderStorage: LayerRenderStorage = LayerRenderStorage.FULL_TARGET; private set
    val isTiled: Boolean get() = renderStorage == LayerRenderStorage.TILED
    val target: RenderTarget get() = gpuTarget
    var name: String get() = metadata.name; set(value) { metadata.name = value }
    var isVisible: Boolean get() = metadata.isVisible; set(value) { metadata.isVisible = value }
    var isLocked: Boolean get() = metadata.isLocked; set(value) { metadata.isLocked = value }
    var opacity: Float get() = metadata.opacity; set(value) { metadata.opacity = value.coerceIn(0f, 1f) }
    var blendMode: BlendMode get() = metadata.blendMode; set(value) { metadata.blendMode = value }
    var version: Long get() = metadata.version; set(value) { metadata.version = value }
    var created = false; private set

    fun create(allocator: LayerResourceAllocator, width: Int, height: Int): Boolean {
        if (created && target.width == width && target.height == height) return true
        if (!allocator.create(target, tileLabel, width, height)) return false
        target.clear(0f, 0f, 0f, 0f); created = true; return true
    }

    fun enableTiledStorage(allocator: LayerResourceAllocator, canvasWidth: Int, canvasHeight: Int) {
        val existing = tileResources
        if (existing != null && existing.grid.matches(canvasWidth, canvasHeight)) return
        existing?.releaseAll()
        tileResources = LayerTileResources(allocator, TileGrid(canvasWidth, canvasHeight), tileLabel)
    }

    /** Enables tiled display for a blank layer without allocating any tile. */
    fun startEmptyTiledStorage(allocator: LayerResourceAllocator, canvasWidth: Int, canvasHeight: Int) {
        enableTiledStorage(allocator, canvasWidth, canvasHeight)
        renderStorage = LayerRenderStorage.TILED
    }

    fun promoteToTiled(allocator: LayerResourceAllocator, canvasWidth: Int, canvasHeight: Int): Boolean {
        if (!created || target.width != canvasWidth || target.height != canvasHeight) return false
        enableTiledStorage(allocator, canvasWidth, canvasHeight)
        val resources = checkNotNull(tileResources)
        if (!LayerTileSeeder().seed(target, resources.grid, resources)) {
            resources.releaseAll(); renderStorage = LayerRenderStorage.FULL_TARGET; return false
        }
        renderStorage = LayerRenderStorage.TILED; return true
    }

    /** Copies only resident, changed tiles back to the compatibility target. */
    fun syncTiledTilesToFullTarget(bounds: IntArray) {
        if (!isTiled || target.framebufferId == 0) return
        val grid = tileGrid ?: return
        val resources = tileResources ?: return
        val oldDraw = IntArray(1); val oldRead = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, oldDraw, 0)
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0)
        try {
            for (coord in grid.tilesIntersecting(bounds)) {
                val tile = resources.peek(coord) ?: continue
                val b = grid.tileBounds(coord)
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, tile.framebufferId)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, target.framebufferId)
                GLES30.glBlitFramebuffer(0, 0, tile.width, tile.height, b[0], b[1], b[2], b[3], GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
                GlCheck.noError("sync tiled compatibility target")
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, oldRead[0])
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, oldDraw[0])
        }
    }

    fun demoteToFullTarget() { renderStorage = LayerRenderStorage.FULL_TARGET }
    fun disableTiledStorage() { tileResources?.releaseAll(); tileResources = null; renderStorage = LayerRenderStorage.FULL_TARGET }
    fun clear() { if (created) target.clear(0f, 0f, 0f, 0f); tileResources?.releaseAll() }
    fun release(allocator: LayerResourceAllocator) { disableTiledStorage(); allocator.release(target); created = false }
    fun resetGlHandles() { gpuTarget.resetHandles(); tileResources?.resetHandles(); created = false; renderStorage = LayerRenderStorage.FULL_TARGET }
    companion object { var useTiledStrokeMirror = true }
}
