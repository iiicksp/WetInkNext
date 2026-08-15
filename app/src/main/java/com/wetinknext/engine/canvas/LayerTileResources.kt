package com.wetinknext.engine.canvas

import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.undo.TileCoord

/**
 * Lazy, render-thread-only GPU backing store for one layer's tiles.
 *
 * The migration keeps this independent from [PaintLayer.gpuTarget] until the
 * compositor and stroke pipeline can render tile-by-tile.
 */
class LayerTileResources(
    private val allocator: LayerResourceAllocator,
    val grid: TileGrid,
    private val label: String,
) {
    private val tiles = LinkedHashMap<TileCoord, RenderTarget>()

    val usesHalfFloat: Boolean get() = allocator.useHalfFloat
    val loadedTileCount: Int get() = tiles.size
    val loadedCoords: Set<TileCoord> get() = tiles.keys.toSet()

    fun peek(coord: TileCoord): RenderTarget? = tiles[coord]

    /** Obtains [coord], or null when the shared texture budget cannot fit it. */
    fun obtain(coord: TileCoord): RenderTarget? {
        require(grid.contains(coord)) { "tile $coord is outside $grid" }
        tiles[coord]?.let { return it }

        val target = RenderTarget()
        if (!allocator.create(
                target,
                "$label-tile-${coord.tx}-${coord.ty}",
                grid.tileWidth(coord.tx),
                grid.tileHeight(coord.ty),
            )
        ) return null
        target.clear(0f, 0f, 0f, 0f)
        tiles[coord] = target
        return target
    }

    fun release(coord: TileCoord) {
        tiles.remove(coord)?.let(allocator::release)
    }

    fun releaseAll() {
        tiles.values.toList().forEach(allocator::release)
        tiles.clear()
    }

    /** Drops stale GL names and removes their budget records after context loss. */
    fun resetHandles() {
        tiles.values.toList().forEach { target ->
            target.resetHandles()
            allocator.release(target)
        }
        tiles.clear()
    }
}
