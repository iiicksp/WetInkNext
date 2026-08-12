package com.wetinknext.engine.thumbnail

import android.graphics.Bitmap
import android.os.Build
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Persists a raw OpenGL thumbnail as WebP.
 *
 * [rgba] follows [android.opengl.GLES30.glReadPixels] row ordering: the first
 * row is the bottom row. The encoder converts it to Android Bitmap's top-down
 * order while preserving RGBA alpha exactly before WebP compression.
 */
class ThumbnailEncoder {
    fun encode(
        rgba: ByteArray,
        width: Int,
        height: Int,
        output: File,
        lossless: Boolean,
    ) = encodeInternal(
        rgba = rgba,
        width = width,
        height = height,
        output = output,
        lossless = lossless,
        inputRowsBottomUp = true,
    )

    /**
     * Writes an image returned by [ThumbnailRenderer], whose rows have already
     * been normalised into Android's top-to-bottom order.
     */
    fun encodeTopDown(
        rgba: ByteArray,
        width: Int,
        height: Int,
        output: File,
        lossless: Boolean,
    ) = encodeInternal(
        rgba = rgba,
        width = width,
        height = height,
        output = output,
        lossless = lossless,
        inputRowsBottomUp = false,
    )

    private fun encodeInternal(
        rgba: ByteArray,
        width: Int,
        height: Int,
        output: File,
        lossless: Boolean,
        inputRowsBottomUp: Boolean,
    ) {
        require(width > 0 && height > 0) { "Thumbnail dimensions must be positive" }
        val expectedByteCount = width * height * RGBA_BYTES_PER_PIXEL
        require(rgba.size == expectedByteCount) {
            "RGBA byte count ${rgba.size} does not match ${width}x$height"
        }

        val pixels = IntArray(width * height)
        for (bitmapY in 0 until height) {
            // Bitmap begins at the top; glReadPixels begins at the bottom.
            val sourceY = if (inputRowsBottomUp) height - 1 - bitmapY else bitmapY
            var source = sourceY * width * RGBA_BYTES_PER_PIXEL
            val destination = bitmapY * width
            for (x in 0 until width) {
                val redLinear = (rgba[source++].toInt() and 0xFF) / 255f
                val greenLinear = (rgba[source++].toInt() and 0xFF) / 255f
                val blueLinear = (rgba[source++].toInt() and 0xFF) / 255f
                val alpha = rgba[source++].toInt() and 0xFF
                val red = (linearToSrgb(redLinear) * 255f).roundToInt().coerceIn(0, 255)
                val green = (linearToSrgb(greenLinear) * 255f).roundToInt().coerceIn(0, 255)
                val blue = (linearToSrgb(blueLinear) * 255f).roundToInt().coerceIn(0, 255)
                pixels[destination + x] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        output.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            output.outputStream().use { stream ->
                check(bitmap.compress(webpFormat(lossless), quality(lossless), stream)) {
                    "WebP encoding failed for ${output.absolutePath}"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun webpFormat(lossless: Boolean): Bitmap.CompressFormat = when {
        lossless && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Bitmap.CompressFormat.WEBP_LOSSLESS
        !lossless && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Bitmap.CompressFormat.WEBP_LOSSY
        else -> Bitmap.CompressFormat.WEBP
    }

    private fun quality(lossless: Boolean): Int = if (lossless) 100 else PROJECT_PREVIEW_QUALITY

    private fun linearToSrgb(value: Float): Float {
        val linear = value.coerceIn(0f, 1f)
        return if (linear <= 0.0031308f) linear * 12.92f
        else (1.055 * linear.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
        const val PROJECT_PREVIEW_QUALITY = 88
    }
}
