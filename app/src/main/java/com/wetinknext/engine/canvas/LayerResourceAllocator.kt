package com.wetinknext.engine.canvas

import com.wetinknext.engine.gl.BudgetedTargets
import com.wetinknext.engine.gl.RenderTarget

/**
 * Render-thread owner of document-layer textures.  Keeping this policy out of
 * [LayerStack] makes the document model independent from the GPU format and
 * gives temporary tools one clear place to coordinate memory with layers.
 */
class LayerResourceAllocator(
    private val targets: BudgetedTargets,
    val useHalfFloat: Boolean,
) {
    fun create(target: RenderTarget, label: String, width: Int, height: Int): Boolean =
        targets.create(target, label, width, height, useHalfFloat)

    fun release(target: RenderTarget) {
        targets.release(target)
    }
}
