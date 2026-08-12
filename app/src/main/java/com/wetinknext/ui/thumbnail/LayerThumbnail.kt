package com.wetinknext.ui.thumbnail

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LayerThumbnailKey(
    val layerId: Long,
    val version: Long,
)

/** Process-local cache; the layer version prevents recycled rows showing stale previews. */
private object LayerThumbnailBitmapCache {
    private val bitmaps = LruCache<LayerThumbnailKey, ImageBitmap>(48)

    fun get(key: LayerThumbnailKey): ImageBitmap? = bitmaps.get(key)

    fun put(key: LayerThumbnailKey, bitmap: ImageBitmap) {
        bitmaps.put(key, bitmap)
    }
}

/**
 * A persisted WebP preview with alpha and the document's native aspect ratio.
 * The last decoded bitmap remains visible while a newer version is read from disk.
 */
@Composable
fun LayerThumbnail(
    layerId: Long,
    path: String?,
    thumbnailVersion: Long,
    modifier: Modifier = Modifier,
) {
    val key = remember(layerId, thumbnailVersion) {
        LayerThumbnailKey(layerId, thumbnailVersion)
    }
    var displayedBitmap by remember(layerId) {
        mutableStateOf(LayerThumbnailBitmapCache.get(key))
    }

    LaunchedEffect(key, path) {
        val bitmap = LayerThumbnailBitmapCache.get(key) ?: withContext(Dispatchers.IO) {
            path
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?.asImageBitmap()
                ?.also { LayerThumbnailBitmapCache.put(key, it) }
        }
        if (bitmap != null) {
            displayedBitmap = bitmap
        }
    }

    val previewModifier = displayedBitmap?.let { bitmap ->
        modifier.aspectRatio(
            ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat(),
            matchHeightConstraintsFirst = true,
        )
    } ?: modifier

    // No panel-coloured backing: alpha in the layer WebP stays alpha in UI.
    // The dimensions come from the image itself, so a portrait document does
    // not acquire horizontal bars just because its row is wider than it is.
    Box(modifier = previewModifier) {
        displayedBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Layer preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
