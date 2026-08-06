package com.wetinknext.engine.undo

/**
 * Extension point for P6.5 compression. P6 uses the identity implementation,
 * so the 96 MB budget reflects the exact bytes that are currently retained.
 */
interface TileCompressor {
    fun compress(data: ByteArray): ByteArray

    fun decompress(data: ByteArray, expectedSize: Int): ByteArray
}

/** P6 storage policy: no compression and no extra byte-array allocation. */
object IdentityTileCompressor : TileCompressor {
    override fun compress(data: ByteArray): ByteArray = data

    override fun decompress(data: ByteArray, expectedSize: Int): ByteArray {
        check(data.size == expectedSize) {
            "Raw tile has ${data.size} bytes, expected $expectedSize"
        }
        return data
    }
}
