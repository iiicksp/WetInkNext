package com.wetinknext.engine.undo

/** One committed stroke, retaining exact states from before and after its merge. */
data class UndoEntry(
    val layerId: Long,
    val beforeTiles: List<TileSnapshot>,
    val afterTiles: List<TileSnapshot>,
    val tag: String = "stroke",
) {
    val memorySize: Int
        get() = beforeTiles.sumOf(TileSnapshot::memorySize) + afterTiles.sumOf(TileSnapshot::memorySize)

    fun dispose() {
        beforeTiles.forEach(TileSnapshot::dispose)
        afterTiles.forEach(TileSnapshot::dispose)
    }
}
