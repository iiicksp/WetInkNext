package com.wetinknext.engine.persistence

import com.wetinknext.engine.undo.RawTileSnapshot
import com.wetinknext.engine.undo.TileCoord
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binary, versioned collection of 256px tiles for one persistent paint layer. */
object PersistentLayerTiles {
    private const val MAGIC = 0x57544C53 // WTLS
    private const val VERSION = 1
    private const val HEADER_BYTES = 12
    private const val TILE_HEADER_BYTES = 32

    data class Tile(
        val coord: TileCoord,
        val pixelLeft: Int,
        val pixelTop: Int,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val bytesPerPixel: Int,
        val bytes: ByteArray,
    )

    fun decode(payload: ByteArray): List<Tile> {
        if (payload.isEmpty()) return emptyList()
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        require(input.remaining() >= HEADER_BYTES && input.int == MAGIC) { "Unknown persistent layer tile payload" }
        require(input.int == VERSION) { "Unsupported persistent layer tile version" }
        val count = input.int
        require(count >= 0) { "Invalid tile count" }
        return List(count) {
            require(input.remaining() >= TILE_HEADER_BYTES) { "Truncated tile header" }
            val tx = input.int; val ty = input.int
            val left = input.int; val top = input.int
            val width = input.int; val height = input.int
            val bpp = input.int; val size = input.int
            require(width > 0 && height > 0 && bpp in setOf(4, 8) && size == width * height * bpp && size <= input.remaining()) { "Invalid tile payload" }
            Tile(TileCoord(tx, ty), left, top, width, height, bpp, ByteArray(size).also(input::get))
        }.also { require(!input.hasRemaining()) { "Trailing tile payload data" } }
    }

    fun encode(tiles: Collection<Tile>): ByteArray {
        val size = HEADER_BYTES + tiles.sumOf { TILE_HEADER_BYTES + it.bytes.size }
        val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        output.putInt(MAGIC).putInt(VERSION).putInt(tiles.size)
        tiles.sortedWith(compareBy<Tile> { it.coord.ty }.thenBy { it.coord.tx }).forEach { tile ->
            output.putInt(tile.coord.tx).putInt(tile.coord.ty)
            output.putInt(tile.pixelLeft).putInt(tile.pixelTop)
            output.putInt(tile.pixelWidth).putInt(tile.pixelHeight)
            output.putInt(tile.bytesPerPixel).putInt(tile.bytes.size).put(tile.bytes)
        }
        return output.array()
    }

    fun fromRaw(tile: RawTileSnapshot) = Tile(
        tile.coord, tile.pixelLeft, tile.pixelTop, tile.pixelWidth, tile.pixelHeight,
        tile.bytesPerPixel, tile.rawBytes.copyOf(),
    )
}
