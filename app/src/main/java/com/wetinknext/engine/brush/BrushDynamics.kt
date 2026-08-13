package com.wetinknext.engine.brush

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.sin

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

    /** Resolves all local, deterministic properties of one STAMP dab. */
    fun resolve(
        settings: BrushSettings,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        velocityPxPerSecond: Float,
        random01: Float,
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
        val velocityNorm = (velocityPxPerSecond / VELOCITY_REFERENCE_PX_PER_SECOND)
            .coerceIn(0f, 1f)
        val velocitySize = 1f - settings.velocityToSize * velocityNorm
        val tiltSize = 1f + settings.tiltToSize * tilt
        val random = random01.coerceIn(0f, 1f)
        val jitterSize = 1f + ((random * 2f) - 1f) * settings.sizeJitter
        out.radius = (
            settings.baseRadiusPx * pressureSize * velocitySize * tiltSize * jitterSize
        ).coerceAtLeast(0.25f)

        val pressureCoverage = if (settings.pressureToOpacity) p else 1f
        val velocityCoverage = 1f - settings.velocityToOpacity * velocityNorm

        // Tilt must not make a dab more opaque.
        val tiltCoverage = 1f - (0.45f * settings.tiltToOpacity * tilt)
        val jitterCoverage = 1f + ((random * 2f) - 1f) * settings.opacityJitter
        out.coverage = (
            pressureCoverage * velocityCoverage * tiltCoverage * jitterCoverage
        ).coerceIn(0f, 1f)

        out.flow = settings.flow.coerceIn(0f, 1f)
        out.hardness = settings.hardness.coerceIn(0f, 1f)
        out.rotationRad = tiltAngle(tiltX, tiltY) * settings.tiltToRotation

        val scatterRadius = settings.scatter.coerceAtLeast(0f) * out.radius
        val scatterAngle = random * (Math.PI.toFloat() * 2f)
        out.scatterX = cos(scatterAngle) * scatterRadius
        out.scatterY = sin(scatterAngle) * scatterRadius
    }

    private fun tiltAngle(tiltX: Float, tiltY: Float): Float = atan2(tiltY, tiltX)

    private const val VELOCITY_REFERENCE_PX_PER_SECOND = 2_400f
}

data class ResolvedDab(
    var radius: Float = 0f,
    var rotationRad: Float = 0f,
    var coverage: Float = 1f,
    var flow: Float = 1f,
    var hardness: Float = 1f,
    var scatterX: Float = 0f,
    var scatterY: Float = 0f,
)
