package com.wetinknext.engine.undo

/** Uncompressed tile data captured on the GL thread. */
class RawTileSnapshot(
    val coord: TileCoord,
    val pixelLeft: Int,
    val pixelTop: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val bytesPerPixel: Int,
    val rawBytes: ByteArray,
) {
    val rawSize: Int get() = rawBytes.size

    fun compress(compressor: TileCompressor): TileSnapshot {
        return TileSnapshot(
            coord = coord,
            pixelLeft = pixelLeft,
            pixelTop = pixelTop,
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            bytesPerPixel = bytesPerPixel,
            storedData = compressor.compress(rawBytes),
            compressor = compressor,
        )
    }
}
