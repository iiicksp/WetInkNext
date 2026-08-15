package com.wetinknext.engine.canvas

/**
 * Document-facing state of a paint layer.  It deliberately contains no GL
 * names or textures, so it can later be shared by persistence, animation and
 * the layer-list UI without coupling them to the render thread.
 */
data class LayerMetadata(
    val id: Long,
    var name: String,
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var opacity: Float = 1f,
    var blendMode: BlendMode = BlendMode.NORMAL,
    var version: Long = 0L,
)
