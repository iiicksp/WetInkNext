package com.wetinknext.engine.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class LoadedBrushTexture(
    val path: String,
    val bitmap: Bitmap,
)

class TextureLoader(
    private val context: Context,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    fun loadAsync(
        path: String,
        onLoaded: (LoadedBrushTexture) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        executor.execute {
            try {
                val bitmap = when {
                    path.startsWith("asset:") -> {
                        val assetPath = path.removePrefix("asset:")
                        context.assets.open(assetPath).use { input ->
                            BitmapFactory.decodeStream(input)
                        }
                    }

                    else -> {
                        BitmapFactory.decodeFile(path)
                    }
                } ?: error("Unable to decode brush texture: $path")

                onLoaded(
                    LoadedBrushTexture(
                        path = path,
                        bitmap = bitmap,
                    ),
                )
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
