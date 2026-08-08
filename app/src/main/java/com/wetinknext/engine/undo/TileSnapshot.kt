package com.wetinknext.engine.undo

data class TileCoord(val tx: Int, val ty: Int)

/** One full 256 px tile (or an edge tile) in the source layer's native format. */
class TileSnapshot(
    val coord: TileCoord,
    val pixelLeft: Int,
    val pixelTop: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val bytesPerPixel: Int,
    private val storedData: ByteArray,
    private val compressor: TileCompressor = IdentityTileCompressor,
) {
    val memorySize: Int get() = storedData.size
    val rawSize: Int get() = pixelWidth * pixelHeight * bytesPerPixel

    /** Returns raw bytes. */
    fun decompress(): ByteArray = compressor.decompress(storedData, rawSize)

    fun dispose() {
        // P6 keeps heap ByteArrays; a later off-heap implementation can release here.
    }

    companion object {
        const val TILE_SIZE = 256
        const val BYTES_PER_PIXEL_RGBA16F = 8
        const val BYTES_PER_PIXEL_RGBA8 = 4
    }
}
