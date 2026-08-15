package com.wetinknext.engine.gl

import android.util.Log
import com.wetinknext.BuildConfig

/**
 * GL-thread accounting for live [RenderTarget] textures.
 *
 * The owner registers the format actually selected by [RenderTarget.create],
 * so an RGBA16F-to-RGBA8 fallback remains accurately represented in the budget.
 */
class RenderTargetBudget(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    var allocatedBytes: Long = 0L
        private set

    val remainingBytes: Long
        get() = (maxBytes - allocatedBytes).coerceAtLeast(0L)

    fun canAllocate(width: Int, height: Int, bytesPerPixel: Int): Boolean =
        bytesOf(width, height, bytesPerPixel) <= remainingBytes

    fun register(width: Int, height: Int, bytesPerPixel: Int, label: String) {
        allocatedBytes += bytesOf(width, height, bytesPerPixel)
        log("alloc", label, width, height, bytesPerPixel)
    }

    fun unregister(width: Int, height: Int, bytesPerPixel: Int, label: String) {
        allocatedBytes =
            (allocatedBytes - bytesOf(width, height, bytesPerPixel)).coerceAtLeast(0L)
        log("free", label, width, height, bytesPerPixel)
    }

    fun reset() {
        allocatedBytes = 0L
    }

    private fun bytesOf(width: Int, height: Int, bytesPerPixel: Int): Long =
        width.toLong() * height.toLong() * bytesPerPixel.toLong()

    private fun log(action: String, label: String, width: Int, height: Int, bpp: Int) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "$action $label ${width}x$height bpp=$bpp " +
                "${bytesOf(width, height, bpp) / MEBIBYTE}MB " +
                "total=${allocatedBytes / MEBIBYTE}/${maxBytes / MEBIBYTE}MB",
        )
    }

    companion object {
        // A 4096×2160 RGBA16F document layer takes about 67 MiB. 512 MiB
        // leaves room for several editable layers plus the temporary stroke
        // target, while still bounding allocation on mobile GPUs.
        const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
        private const val MEBIBYTE = 1024L * 1024L
        private const val TAG = "GpuBudget"
    }
}
