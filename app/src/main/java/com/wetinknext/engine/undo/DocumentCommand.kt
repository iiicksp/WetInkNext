package com.wetinknext.engine.undo

import com.wetinknext.engine.canvas.BlendMode

/**
 * One reversible document edit. Pixel and metadata edits intentionally share
 * the same history stack so their ordering is always the user's ordering.
 */
sealed interface DocumentCommand {
    val layerId: Long
    val operation: UndoOperationType
    val tag: String
    val memorySize: Long

    data class TileEdit(
        override val layerId: Long,
        val beforeTiles: List<TileSnapshot>,
        val afterTiles: List<TileSnapshot>,
        override val operation: UndoOperationType = UndoOperationType.TILE_EDIT,
        override val tag: String = "stroke",
    ) : DocumentCommand {
        override val memorySize: Long
            get() = beforeTiles.sumOf { it.memorySize.toLong() } + afterTiles.sumOf { it.memorySize.toLong() }
    }

    data class LayerProperties(
        override val layerId: Long,
        val before: LayerPropertiesState,
        val after: LayerPropertiesState,
        override val tag: String = "layer_properties",
    ) : DocumentCommand {
        override val operation: UndoOperationType = UndoOperationType.LAYER_PROPERTIES
        override val memorySize: Long = 0L
    }

    data class RemoveLayer(
        override val layerId: Long,
        val layer: RemovedLayerState,
        override val tag: String = "remove_layer",
    ) : DocumentCommand {
        override val operation: UndoOperationType = UndoOperationType.REMOVE_LAYER
        override val memorySize: Long get() = layer.tiles.sumOf { it.memorySize.toLong() }
    }
}

data class LayerPropertiesState(
    val visible: Boolean,
    val locked: Boolean,
    val opacity: Float,
    val blendMode: BlendMode,
)

/** Layer metadata and exact pixels retained while the layer is in undo history. */
data class RemovedLayerState(
    val index: Int,
    val activeLayerIdBefore: Long,
    val activeLayerIdAfter: Long,
    val name: String,
    val visible: Boolean,
    val locked: Boolean,
    val opacity: Float,
    val blendMode: BlendMode,
    val version: Long,
    val tiles: List<TileSnapshot>,
)
