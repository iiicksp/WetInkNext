package com.wetinknext.engine.brush

import com.wetinknext.engine.core.DirtyRect
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DabBuffer(val capacity: Int = DEFAULT_CAPACITY) {
    init { require(capacity > 0) }
    val floats = ByteBuffer.allocateDirect(capacity * FLOATS_PER_DAB * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    var count = 0; private set; var overflowCount = 0L; private set
    fun clear() { count = 0; overflowCount = 0L; floats.clear() }
    fun add(
        x: Float,
        y: Float,
        radius: Float,
        rotation: Float,
        coverage: Float,
        flow: Float,
        hardness: Float,
        dx: Float,
        dy: Float,
    ): Boolean {
        if (count >= capacity) {
            overflowCount++
            return false
        }
        floats.put(x)
        floats.put(y)
        floats.put(radius)
        floats.put(rotation)
        floats.put(coverage.coerceIn(0f, 1f))
        floats.put(flow.coerceIn(0f, 1f))
        floats.put(hardness.coerceIn(0f, 1f))
        floats.put(dx)
        floats.put(dy)
        count++
        return true
    }
    fun prepareForUpload() { floats.position(0); floats.limit(count * FLOATS_PER_DAB) }
    fun prepareForUpload(firstDab: Int, dabCount: Int) {
        require(firstDab >= 0)
        require(dabCount >= 0)
        require(firstDab + dabCount <= count)
        floats.position(firstDab * FLOATS_PER_DAB)
        floats.limit((firstDab + dabCount) * FLOATS_PER_DAB)
    }
    /** Restores the direct buffer for appending after a GL upload. */
    fun finishUpload() {
        floats.limit(capacity * FLOATS_PER_DAB)
        floats.position(count * FLOATS_PER_DAB)
    }

    /**
     * Includes the exact circular footprint of each emitted dab plus a caller
     * supplied safety fringe for analytic AA and textured-tip filtering.
     */
    fun includeDirtyRect(rect: DirtyRect, extraMargin: Float) {
        val safeExtraMargin = extraMargin.coerceAtLeast(0f)
        for (index in 0 until count) {
            val offset = index * FLOATS_PER_DAB
            val x = floats.get(offset)
            val y = floats.get(offset + 1)
            val radius = floats.get(offset + 2).coerceAtLeast(0f) + safeExtraMargin
            rect.include(
                left = x - radius,
                top = y - radius,
                right = x + radius,
                bottom = y + radius,
            )
        }
    }
    companion object { const val FLOATS_PER_DAB = 9; const val DEFAULT_CAPACITY = 8192 }
}
