package com.wetinknext.engine.brush

import kotlin.math.sqrt

class Stabilizer(private val filter: OneEuroFilter = OneEuroFilter()) {
    var smoothing = 0f
    var streamline = 0f
    var x = 0f; private set
    var y = 0f; private set
    var velocity = 0f; private set

    private var pulledX = 0f
    private var pulledY = 0f
    private var hasLast = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private val filtered = FloatArray(2)

    fun reset() { 
        filter.reset()
        x = 0f; y = 0f; velocity = 0f
        hasLast = false; lastX = 0f; lastY = 0f; lastT = 0L
    }

    fun process(timestampNanos: Long, rawX: Float, rawY: Float) {
        if (!hasLast) {
            pulledX = rawX
            pulledY = rawY
        } else {
            val r = streamline * 150f
            val dx = rawX - pulledX
            val dy = rawY - pulledY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > r) {
                if (dist > 0.001f) {
                    pulledX = rawX - (dx / dist) * r
                    pulledY = rawY - (dy / dist) * r
                }
            }
        }

        if (smoothing <= 0f) {
            x = pulledX
            y = pulledY
        } else {
            filter.minCutoff = 3.0f - smoothing * (3.0f - 0.1f)
            filter.beta = 0.5f - smoothing * (0.5f - 0.01f)
            filter.filter(timestampNanos, pulledX, pulledY, filtered)
            x = filtered[0]
            y = filtered[1]
        }

        if (hasLast) { 
            var dt = (timestampNanos - lastT) / 1_000_000_000f
            if (dt < 1f / 1000f) dt = 1f / 1000f
            val d = sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY))
            velocity += 0.2f * (d / dt - velocity) 
        }
        hasLast = true; lastX = x; lastY = y; lastT = timestampNanos
    }
}
