package com.wetinknext.engine.brush

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
        out: ResolvedDab,
    ) {
        val p = pressure.coerceIn(0f, 1f).let {
            if (settings.pressureGamma > 0f) it.pow(settings.pressureGamma) else it
        }

        val tilt = tiltMagnitude(tiltX, tiltY)

        // Size resolution
        var sizeFactor = 1f
        if (settings.pressureToSize) {
            sizeFactor = settings.minSizeRatio + (1f - settings.minSizeRatio) * p
        }
        sizeFactor *= (1f + settings.tiltToSize * tilt)

        out.radius = (settings.baseRadiusPx * sizeFactor).coerceAtLeast(0.25f)

        // Opacity resolution
        var opacityFactor = 1f

        if (settings.pressureToOpacity) {
            opacityFactor = p
        }

        opacityFactor *= (1f + settings.tiltToOpacity * tilt)

        out.coverage = opacityFactor.coerceIn(0f, 1f)
        out.opacity = (
            settings.opacity * out.coverage
        ).coerceIn(0f, 1f)
    }
}

data class ResolvedDab(
    var radius: Float = 0f,
    var opacity: Float = 0f,
    var coverage: Float = 1f,
)
