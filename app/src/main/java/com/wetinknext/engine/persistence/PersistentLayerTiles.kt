package com.wetinknext.engine.persistence

import com.wetinknext.engine.undo.RawTileSnapshot
import com.wetinknext.engine.undo.TileCoord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Binary, versioned collection of 256px tiles for one persistent paint layer. */
object PersistentLayerTiles {
    private const val MAGIC = 0x57544C53 // WTLS
    private const val VERSION = 2
    private const val HEADER_BYTES = 12
    private const val TILE_HEADER_BYTES_V1 = 32
    private const val TILE_HEADER_BYTES_V2 = 36

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
        val version = input.int
        require(version in setOf(1, VERSION)) { "Unsupported persistent layer tile version" }
        val count = input.int
        require(count >= 0) { "Invalid tile count" }
        return List(count) {
            val headerBytes = if (version == 1) TILE_HEADER_BYTES_V1 else TILE_HEADER_BYTES_V2
            require(input.remaining() >= headerBytes) { "Truncated tile header" }
            val tx = input.int; val ty = input.int
            val left = input.int; val top = input.int
            val width = input.int; val height = input.int
            val bpp = input.int
            val rawSize = input.int
            require(
                width > 0 && height > 0 && bpp in setOf(4, 8) &&
                    rawSize == width * height * bpp
            ) { "Invalid tile payload" }

            val bytes = if (version == 1) {
                require(rawSize <= input.remaining()) { "Truncated tile payload" }
                ByteArray(rawSize).also(input::get)
            } else {
                val storedSize = input.int
                require(storedSize > 0 && storedSize <= input.remaining()) { "Invalid compressed tile payload" }
                val compressed = ByteArray(storedSize).also(input::get)
                inflate(compressed, rawSize)
            }
            Tile(TileCoord(tx, ty), left, top, width, height, bpp, bytes)
        }.also { require(!input.hasRemaining()) { "Trailing tile payload data" } }
    }

    fun encode(tiles: Collection<Tile>): ByteArray {
        val encoded = tiles
            .sortedWith(compareBy<Tile> { it.coord.ty }.thenBy { it.coord.tx })
            .map { tile -> tile to deflate(tile.bytes) }
        val size = HEADER_BYTES + encoded.sumOf { (tile, compressed) ->
            TILE_HEADER_BYTES_V2 + compressed.size
        }
        val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        output.putInt(MAGIC).putInt(VERSION).putInt(tiles.size)
        encoded.forEach { (tile, compressed) ->
            output.putInt(tile.coord.tx).putInt(tile.coord.ty)
            output.putInt(tile.pixelLeft).putInt(tile.pixelTop)
            output.putInt(tile.pixelWidth).putInt(tile.pixelHeight)
            output.putInt(tile.bytesPerPixel).putInt(tile.bytes.size)
            output.putInt(compressed.size).put(compressed)
        }
        return output.array()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val compressor = Deflater(Deflater.BEST_SPEED)
        return try {
            compressor.setInput(raw)
            compressor.finish()
            // zlib's incompressible-data bound; a tile is at most 512 KiB.
            val destination = ByteArray(raw.size + raw.size / 8 + 64)
            val size = compressor.deflate(destination)
            require(compressor.finished()) { "Tile compression did not finish" }
            destination.copyOf(size)
        } finally {
            compressor.end()
        }
    }

    private fun inflate(compressed: ByteArray, rawSize: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val raw = ByteArray(rawSize)
            val size = inflater.inflate(raw)
            require(size == rawSize && inflater.finished()) { "Tile decompression failed" }
            raw
        } catch (error: DataFormatException) {
            throw IllegalArgumentException("Invalid compressed tile data", error)
        } finally {
            inflater.end()
        }
    }

    fun fromRaw(tile: RawTileSnapshot) = Tile(
        tile.coord, tile.pixelLeft, tile.pixelTop, tile.pixelWidth, tile.pixelHeight,
        tile.bytesPerPixel, tile.rawBytes.copyOf(),
    )

    /**
     * Shares the immutable raw byte array captured for a completed commit.
     * The Undo compression job only reads the same array, so no additional
     * copy is needed on the GL thread. Callers must not mutate [tile] after
     * passing it here.
     */
    fun fromRawOwned(tile: RawTileSnapshot) = Tile(
        tile.coord,
        tile.pixelLeft,
        tile.pixelTop,
        tile.pixelWidth,
        tile.pixelHeight,
        tile.bytesPerPixel,
        tile.rawBytes,
    )
}
