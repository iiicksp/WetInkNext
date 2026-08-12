package com.wetinknext.data.project

import android.graphics.Bitmap
import android.os.Build
import com.wetinknext.engine.core.ThumbnailCapture
import java.io.ByteArrayOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/** CPU-only encoder. Call from a background coroutine, never from the GL thread. */
object ThumbnailEncoder {
    fun encodeWebp(image: ThumbnailCapture.Rgba): ByteArray {
        val pixels = IntArray(image.width * image.height)
        for (destinationY in 0 until image.height) {
            // ThumbnailRenderer already flips glReadPixels into Bitmap order.
            var source = destinationY * image.width * 4
            val destination = destinationY * image.width
            for (x in 0 until image.width) {
                val redLinear = (image.pixels[source++].toInt() and 0xFF) / 255f
                val greenLinear = (image.pixels[source++].toInt() and 0xFF) / 255f
                val blueLinear = (image.pixels[source++].toInt() and 0xFF) / 255f
                val a = image.pixels[source++].toInt() and 0xFF
                val r = (linearToSrgb(redLinear) * 255f).roundToInt().coerceIn(0, 255)
                val g = (linearToSrgb(greenLinear) * 255f).roundToInt().coerceIn(0, 255)
                val b = (linearToSrgb(blueLinear) * 255f).roundToInt().coerceIn(0, 255)
                pixels[destination + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(webpFormat(), 88, output)) { "WebP encoding failed" }
            bitmap.recycle()
            output.toByteArray()
        }
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    private fun linearToSrgb(value: Float): Float {
        val linear = value.coerceIn(0f, 1f)
        return if (linear <= 0.0031308f) linear * 12.92f
        else (1.055 * linear.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    }
}
