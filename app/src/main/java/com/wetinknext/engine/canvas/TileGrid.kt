package com.wetinknext.engine.canvas

import com.wetinknext.engine.undo.TileCoord
import com.wetinknext.engine.undo.TileSnapshot

/** Immutable CPU-only description of a document's tile lattice. */
class TileGrid(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val tileSize: Int = TileSnapshot.TILE_SIZE,
) {
    init {
        require(canvasWidth > 0 && canvasHeight > 0) {
            "canvas must be positive, was ${canvasWidth}x${canvasHeight}"
        }
        require(tileSize > 0) { "tileSize must be positive, was $tileSize" }
    }

    val tilesX: Int = ceilDiv(canvasWidth, tileSize)
    val tilesY: Int = ceilDiv(canvasHeight, tileSize)
    val tileCount: Int get() = tilesX * tilesY

    fun contains(tx: Int, ty: Int): Boolean = tx in 0 until tilesX && ty in 0 until tilesY
    fun contains(coord: TileCoord): Boolean = contains(coord.tx, coord.ty)

    /** True when this lattice already describes the given canvas. */
    fun matches(width: Int, height: Int, tileSize: Int = this.tileSize): Boolean =
        canvasWidth == width && canvasHeight == height && this.tileSize == tileSize

    fun coordAt(x: Int, y: Int): TileCoord? =
        if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) null
        else TileCoord(x / tileSize, y / tileSize)

    fun tileLeft(tx: Int): Int = tx * tileSize
    fun tileTop(ty: Int): Int = ty * tileSize
    fun tileWidth(tx: Int): Int = minOf(tileSize, canvasWidth - tileLeft(tx))
    fun tileHeight(ty: Int): Int = minOf(tileSize, canvasHeight - tileTop(ty))

    /** Writes [left, top, right, bottom] of [coord] into [out]. */
    fun tileBounds(coord: TileCoord, out: IntArray = IntArray(4)): IntArray {
        require(out.size >= 4) { "out must hold 4 values" }
        require(contains(coord)) { "tile $coord is outside a ${tilesX}x${tilesY} grid" }
        val left = tileLeft(coord.tx)
        val top = tileTop(coord.ty)
        out[0] = left
        out[1] = top
        out[2] = left + tileWidth(coord.tx)
        out[3] = top + tileHeight(coord.ty)
        return out
    }

    fun tilesIntersecting(bounds: IntArray): List<TileCoord> {
        require(bounds.size >= 4) { "bounds must hold 4 values" }
        return tilesIntersecting(bounds[0], bounds[1], bounds[2], bounds[3])
    }

    fun tilesIntersecting(left: Int, top: Int, right: Int, bottom: Int): List<TileCoord> =
        buildList { forEachTileIn(left, top, right, bottom) { add(it) } }

    /** Visits tiles overlapping the half-open rectangle [left, right) x [top, bottom). */
    fun forEachTileIn(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        action: (TileCoord) -> Unit,
    ) {
        val clampedLeft = left.coerceIn(0, canvasWidth)
        val clampedTop = top.coerceIn(0, canvasHeight)
        val clampedRight = right.coerceIn(0, canvasWidth)
        val clampedBottom = bottom.coerceIn(0, canvasHeight)
        if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) return

        val firstX = clampedLeft / tileSize
        val lastX = (clampedRight - 1) / tileSize
        val firstY = clampedTop / tileSize
        val lastY = (clampedBottom - 1) / tileSize
        for (ty in firstY..lastY) for (tx in firstX..lastX) action(TileCoord(tx, ty))
    }

    fun allTiles(): List<TileCoord> = buildList(tileCount) {
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) add(TileCoord(tx, ty))
    }

    override fun toString(): String =
        "TileGrid(${canvasWidth}x${canvasHeight}, ${tilesX}x${tilesY} tiles of $tileSize)"

    private companion object {
        fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
    }
}
