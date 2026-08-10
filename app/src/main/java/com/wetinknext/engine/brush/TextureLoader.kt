package com.wetinknext.engine.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class LoadedBrushTexture(
    val path: String,
    val width: Int,
    val height: Int,
    val rgba: ByteBuffer,
)

class TextureLoader(
    private val context: Context,
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor(),
) {
    fun loadAsync(
        path: String,
        onLoaded: (LoadedBrushTexture) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        executor.execute {
            try {
                val loaded = decode(path)
                onLoaded(loaded)
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }

    private fun decode(path: String): LoadedBrushTexture {
        val source = decodeBitmap(path)

        val bitmap = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val width = bitmap.width
        val height = bitmap.height

        val rgba = ByteBuffer
            .allocateDirect(width * height * 4)
            .order(ByteOrder.nativeOrder())

        // Bitmap stores ARGB_8888 pixels as packed ARGB ints. Uploading that
        // buffer directly makes the byte order platform-dependent; GLES expects
        // the explicit RGBA byte sequence declared in BrushTexture.
        val pixels = IntArray(width * height)
        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height,
        )
        for (pixel in pixels) {
            rgba.put(((pixel ushr 16) and 0xFF).toByte())
            rgba.put(((pixel ushr 8) and 0xFF).toByte())
            rgba.put((pixel and 0xFF).toByte())
            rgba.put(((pixel ushr 24) and 0xFF).toByte())
        }
        rgba.flip()

        if (bitmap !== source && !bitmap.isRecycled) {
            bitmap.recycle()
        }

        if (!source.isRecycled && source !== bitmap) {
            source.recycle()
        }

        return LoadedBrushTexture(
            path = path,
            width = width,
            height = height,
            rgba = rgba,
        )
    }

    private fun decodeBitmap(path: String): Bitmap {
        val bitmap = if (path.startsWith("asset:")) {
            val assetPath = path.removePrefix("asset:")

            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } else {
            BitmapFactory.decodeFile(path)
        }

        return requireNotNull(bitmap) {
            "Cannot decode brush texture: $path"
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
