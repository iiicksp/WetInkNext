package com.wetinknext.engine.canvas

import com.wetinknext.engine.gl.RenderTarget

/** One paintable document layer and its private GPU target. */
class PaintLayer(
    val id: Long,
    var name: String,
) {
    val target = RenderTarget()

    var isVisible = true
    var isLocked = false
    var opacity = 1f
    var blendMode = BlendMode.NORMAL
    var version = 0L

    var created = false
        private set

    fun create(width: Int, height: Int, preferHalfFloat: Boolean) {
        if (created && target.width == width && target.height == height) return
        target.create(width, height, preferHalfFloat)
        target.clear(0f, 0f, 0f, 0f)
        created = true
    }

    fun clear() {
        if (created) target.clear(0f, 0f, 0f, 0f)
    }

    fun release() {
        target.release()
        created = false
    }
}
