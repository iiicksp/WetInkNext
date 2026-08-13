package com.wetinknext.engine.canvas

import com.wetinknext.engine.gl.BudgetedTargets
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

    fun create(
        targets: BudgetedTargets,
        width: Int,
        height: Int,
        preferHalfFloat: Boolean,
    ): Boolean {
        if (created && target.width == width && target.height == height) return true
        if (!targets.create(target, "layer-$id", width, height, preferHalfFloat)) return false
        target.clear(0f, 0f, 0f, 0f)
        created = true
        return true
    }

    fun clear() {
        if (created) target.clear(0f, 0f, 0f, 0f)
    }

    fun release(targets: BudgetedTargets) {
        targets.release(target)
        created = false
    }
}
