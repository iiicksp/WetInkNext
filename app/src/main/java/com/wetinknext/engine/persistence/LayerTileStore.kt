package com.wetinknext.engine.persistence

import com.wetinknext.engine.canvas.PaintLayer
import com.wetinknext.engine.undo.RawTileSnapshot
import com.wetinknext.engine.undo.TileCoord
import java.util.concurrent.ConcurrentHashMap

/** CPU cache of tiles captured after completed edits. No dab triggers persistence. */
class LayerTileStore(initialPayloads: Map<Long, ByteArray> = emptyMap()) {
    private val tiles = ConcurrentHashMap<Long, ConcurrentHashMap<TileCoord, PersistentLayerTiles.Tile>>()
    private val dirty = ConcurrentHashMap<Long, MutableSet<TileCoord>>()
    /** A cleared layer is persisted as a tiny empty tile collection, not as a full transparent canvas. */
    private val clearedLayers = ConcurrentHashMap.newKeySet<Long>()

    suspend fun writeTile(layerId: Long, tile: RawTileSnapshot) {
        tiles.getOrPut(layerId) { ConcurrentHashMap() }[tile.coord] = PersistentLayerTiles.fromRaw(tile)
        dirty.getOrPut(layerId) { ConcurrentHashMap.newKeySet() }.add(tile.coord)
    }

    suspend fun readTile(layerId: Long, coord: TileCoord): ByteArray =
        tiles[layerId]?.get(coord)?.bytes?.copyOf() ?: ByteArray(0)

    suspend fun writeLayer(layer: PaintLayer, changedTiles: List<RawTileSnapshot>) {
        require(layer.created)
        changedTiles.forEach { writeTile(layer.id, it) }
    }

    /**
     * GL-thread hand-off from a completed commit. Raw snapshots are immutable
     * after capture, and Undo only reads them, so retain their arrays rather
     * than allocating another full tile copy before persistence begins.
     */
    fun markDirty(layerId: Long, changedTiles: List<RawTileSnapshot>) {
        clearedLayers.remove(layerId)
        val target = tiles.getOrPut(layerId) { ConcurrentHashMap() }
        val dirtyCoords = dirty.getOrPut(layerId) { ConcurrentHashMap.newKeySet() }
        changedTiles.forEach { tile ->
            target[tile.coord] = PersistentLayerTiles.fromRawOwned(tile)
            dirtyCoords.add(tile.coord)
        }
    }

    /** Drops the previous cached pixels after a layer clear without allocating transparent tile data. */
    fun markLayerCleared(layerId: Long) {
        tiles.remove(layerId)
        dirty.remove(layerId)
        clearedLayers.add(layerId)
    }

    fun takeDirty(): Map<Long, Set<TileCoord>> = buildMap {
        dirty.forEach { (layerId, coords) -> put(layerId, coords.toSet()) }
        clearedLayers.forEach { layerId -> putIfAbsent(layerId, emptySet()) }
    }

    fun acknowledge(saved: Map<Long, Set<TileCoord>>) {
        saved.forEach { (layerId, coords) ->
            dirty[layerId]?.removeAll(coords)
            clearedLayers.remove(layerId)
        }
    }

    /** Full layer payloads only for layers that have dirty tiles. */
    fun payloadsForDirty(dirtySnapshot: Map<Long, Set<TileCoord>> = takeDirty()): Map<Long, ByteArray> =
        dirtySnapshot.keys.associateWith { layerId ->
            PersistentLayerTiles.encode(tiles[layerId]?.values.orEmpty())
        }

    init {
        initialPayloads.forEach { (layerId, payload) ->
            if (payload.isNotEmpty()) {
                val layer = tiles.getOrPut(layerId) { ConcurrentHashMap() }
                PersistentLayerTiles.decode(payload).forEach { layer[it.coord] = it }
            }
        }
    }
}
