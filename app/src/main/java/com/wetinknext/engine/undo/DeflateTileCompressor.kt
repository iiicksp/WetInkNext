package com.wetinknext.engine.undo

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

class DeflateTileCompressor(
    private val level: Int = Deflater.BEST_SPEED,
) : TileCompressor {

    override fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(level)
        return try {
            deflater.setInput(data)
            deflater.finish()

            val output = ByteArrayOutputStream(data.size)
            val buffer = ByteArray(16 * 1024)

            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }

            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    override fun decompress(
        data: ByteArray,
        expectedSize: Int,
    ): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)

            val output = ByteArrayOutputStream(expectedSize)
            val buffer = ByteArray(16 * 1024)

            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.finished()) break
                check(count > 0) { "Decompression stalled or data corrupted" }
                output.write(buffer, 0, count)
            }

            output.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
