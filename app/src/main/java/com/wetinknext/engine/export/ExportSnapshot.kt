package com.wetinknext.engine.export

/**
 * Immutable cross-thread snapshot of the document, captured on the GL thread.
 *
 * All pixel buffers are top-to-bottom RGBA8, matching the Android
 * [android.graphics.Bitmap] row order, so encoders can feed them straight into
 * [android.graphics.Bitmap.copyPixelsFromBuffer] without any flipping.
 */
data class ExportLayerSnapshot(
    val id: Long,
    val name: String,
    val visible: Boolean,
    val width: Int,
    val height: Int,
    /**
     * Top-to-bottom RGBA8 of this layer's pixels. Layer opacity is already
     * baked into the pixels by the capture pass, so a PSD writer stores every
     * record as fully opaque (255) and Photoshop flattens it exactly like the
     * editor composite.
     */
    val rgba: ByteArray,
)

data class ExportSnapshot(
    val documentName: String,
    val width: Int,
    val height: Int,
    /** Top-to-bottom RGBA8 of all visible layers composited exactly as on canvas. */
    val composite: ByteArray,
    /** Editor layer order, bottom layer first. */
    val layers: List<ExportLayerSnapshot>,
)