package com.wetinknext.engine.brush

import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

object BrushDynamics {

    fun tiltMagnitude(
        tiltX: Float,
        tiltY: Float,
    ): Float {
        return sqrt(
            tiltX * tiltX +
                tiltY * tiltY,
        ).coerceIn(0f, 1f)
    }

    /**
     * Resolves the final radius and opacity for a single dab based on input.
     */
    fun resolve(
        settings: BrushSettings,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        velocity: Float,
        orientationRad: Float,
        out: ResolvedDab,
    ) {
        val p = pressure
            .coerceIn(0f, 1f)
            .let { it.toDouble().pow(settings.pressureGamma.toDouble()).toFloat() }

        val tilt = tiltMagnitude(tiltX, tiltY)

        val pressureSize = if (settings.pressureToSize) {
            settings.minSizeRatio + (1f - settings.minSizeRatio) * p
        } else {
            1f
        }
        val tiltSize = 1f + settings.tiltToSize * tilt
        val normalizedVelocity = (velocity / VELOCITY_REFERENCE_PX_PER_SECOND)
            .coerceIn(0f, 1f)
        val velocitySize = 1f + settings.velocityToSize * normalizedVelocity
        out.radius = (
            settings.baseRadiusPx * pressureSize * tiltSize * velocitySize
        ).coerceAtLeast(0.25f)

        val pressureOpacity = if (settings.pressureToOpacity) p else 1f
        val velocityOpacity = 1f - settings.velocityToOpacity * normalizedVelocity

        // Tilt must not make a dab more opaque.
        val tiltOpacity = 1f - (0.45f * settings.tiltToOpacity * tilt)
        out.coverage = (pressureOpacity * tiltOpacity * velocityOpacity).coerceIn(0f, 1f)

        // Local dab alpha only. Global brush opacity is applied during stroke blit.
        out.opacity = out.coverage
        out.rotation = orientationRad + settings.tiltToRotation * tiltAngle(tiltX, tiltY)
    }

    private fun tiltAngle(tiltX: Float, tiltY: Float): Float = atan2(tiltY, tiltX)

    private const val VELOCITY_REFERENCE_PX_PER_SECOND = 2_500f
}

data class ResolvedDab(
    var radius: Float = 0f,
    var opacity: Float = 1f,
    var coverage: Float = 1f,
    var rotation: Float = 0f,
)
