package com.wetinknext.engine.gl

/** Owns target accounting so allocation and release always update one budget. */
class BudgetedTargets(
    val budget: RenderTargetBudget,
) {
    private data class Record(
        val width: Int,
        val height: Int,
        val bytesPerPixel: Int,
        val label: String,
    )

    private val records = HashMap<RenderTarget, Record>()

    /**
     * Returns false only when an RGBA8 target cannot fit the remaining budget.
     * Callers must gracefully omit optional targets in that case.
     */
    fun create(
        target: RenderTarget,
        label: String,
        width: Int,
        height: Int,
        preferHalfFloat: Boolean,
    ): Boolean {
        GlCheck.checkOnGlThread()
        release(target)
        if (width <= 0 || height <= 0) return false
        if (!budget.canAllocate(width, height, RGBA8_BYTES_PER_PIXEL)) return false

        val useHalfFloat =
            preferHalfFloat && budget.canAllocate(width, height, RGBA16F_BYTES_PER_PIXEL)
        target.create(width, height, useHalfFloat)

        val record = Record(
            width = width,
            height = height,
            bytesPerPixel = target.bytesPerPixel,
            label = label,
        )
        budget.register(record.width, record.height, record.bytesPerPixel, record.label)
        records[target] = record
        return true
    }

    fun release(target: RenderTarget) {
        val record = records.remove(target)
        if (target.framebufferId != 0 || target.textureId != 0) {
            target.release()
        }
        record?.let { budget.unregister(it.width, it.height, it.bytesPerPixel, it.label) }
    }

    fun releaseAll() {
        records.keys.toList().forEach(::release)
    }

    private companion object {
        const val RGBA8_BYTES_PER_PIXEL = 4
        const val RGBA16F_BYTES_PER_PIXEL = 8
    }
}
