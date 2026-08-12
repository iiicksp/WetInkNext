package com.wetinknext.engine.brush

/**
 * Preallocated input store for the future geometric ribbon renderer.
 *
 * It deliberately has no dependency on the active capsule pipeline. A later
 * change will consume these samples to build the inner contour, outer AA
 * fringe, caps, and ROUND/MITER/BEVEL joins.
 */
class RibbonGeometryBuilder(
    private val maxPoints: Int = 8_192,
) {
    init {
        require(maxPoints > 0) { "maxPoints must be positive" }
    }

    private val positions = FloatArray(maxPoints * 2)
    private val radii = FloatArray(maxPoints)
    private val coverage = FloatArray(maxPoints)

    var count: Int = 0
        private set

    fun clear() {
        count = 0
    }

    fun addPoint(
        x: Float,
        y: Float,
        radius: Float,
        coverageValue: Float,
    ): Boolean {
        if (count >= maxPoints) return false

        val offset = count * 2
        positions[offset] = x
        positions[offset + 1] = y
        radii[count] = radius.coerceAtLeast(0f)
        coverage[count] = coverageValue.coerceIn(0f, 1f)
        count++
        return true
    }
}
