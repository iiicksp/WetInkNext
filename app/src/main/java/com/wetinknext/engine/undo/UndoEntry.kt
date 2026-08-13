package com.wetinknext.engine.undo

/** One reversible history operation, retaining exact states from before and after its merge. */
data class UndoEntry(
    val command: DocumentCommand,
) {
    constructor(
        layerId: Long,
        beforeTiles: List<TileSnapshot>,
        afterTiles: List<TileSnapshot>,
        operation: UndoOperationType = UndoOperationType.TILE_EDIT,
        tag: String = "stroke",
    ) : this(DocumentCommand.TileEdit(layerId, beforeTiles, afterTiles, operation, tag))

    val layerId: Long get() = command.layerId
    val operation: UndoOperationType get() = command.operation
    val tag: String get() = command.tag
    val beforeTiles: List<TileSnapshot>
        get() = (command as? DocumentCommand.TileEdit)?.beforeTiles.orEmpty()
    val afterTiles: List<TileSnapshot>
        get() = (command as? DocumentCommand.TileEdit)?.afterTiles.orEmpty()
    val memorySize: Long get() = command.memorySize

    fun dispose() {
        when (val value = command) {
            is DocumentCommand.TileEdit -> {
                value.beforeTiles.forEach(TileSnapshot::dispose)
                value.afterTiles.forEach(TileSnapshot::dispose)
            }
            is DocumentCommand.RemoveLayer -> value.layer.tiles.forEach(TileSnapshot::dispose)
            is DocumentCommand.LayerProperties -> Unit
        }
    }
}
