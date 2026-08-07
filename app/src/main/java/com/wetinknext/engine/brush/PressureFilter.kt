package com.wetinknext.engine.brush

import kotlin.math.exp

/**
 * Time-based stylus pressure smoothing.
 * A zero pressure sample while the pointer is still in contact is treated as a
 * device glitch; Android sends the real zero value on UP after the final point.
 */
class PressureFilter {
    private var initialized = false
    private var lastTimestampNanos = 0L
    private var value = 0f

    fun reset() { initialized = false; lastTimestampNanos = 0L; value = 0f }

    fun filter(timestampNanos: Long, rawPressure: Float): Float {
        val raw = rawPressure.coerceIn(0f, 1f)
        if (!initialized) { initialized = true; lastTimestampNanos = timestampNanos; value = raw; return value }
        val dt = ((timestampNanos - lastTimestampNanos) / NANOS_PER_SECOND).coerceIn(MIN_DT, MAX_DT)
        lastTimestampNanos = timestampNanos
        val stableRaw = if (raw <= ZERO_GLITCH_THRESHOLD && value > ZERO_GLITCH_THRESHOLD) value else raw
        val tau = if (stableRaw >= value) RISE_TAU_SECONDS else FALL_TAU_SECONDS
        val alpha = 1f - exp((-dt / tau).toDouble()).toFloat()
        value += (stableRaw - value) * alpha
        return value.coerceIn(0f, 1f)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MIN_DT = 1f / 1000f
        const val MAX_DT = 1f / 20f
        const val RISE_TAU_SECONDS = .018f
        const val FALL_TAU_SECONDS = .055f
        const val ZERO_GLITCH_THRESHOLD = .01f
    }
}
