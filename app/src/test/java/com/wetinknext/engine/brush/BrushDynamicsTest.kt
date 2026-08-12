package com.wetinknext.engine.brush

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class BrushDynamicsTest {
    @Test
    fun resolve_separatesLocalCoverageFromGlobalOpacity() {
        val settings = BrushSettings(
            baseRadiusPx = 10f,
            opacity = 0.2f,
            pressureGamma = 2f,
            minSizeRatio = 0.1f,
            tiltToSize = 0.5f,
            tiltToOpacity = 1f,
        )
        val result = ResolvedDab()

        BrushDynamics.resolve(
            settings = settings,
            pressure = 0.5f,
            tiltX = 1f,
            tiltY = 0f,
            velocity = 0f,
            orientationRad = 0f,
            out = result,
        )

        // p = 0.5 ^ 2 = 0.25; pressure size = .325; tilt size = 1.5.
        assertEquals(4.875f, result.radius, 0.0001f)
        // Tilt attenuates, rather than amplifies, local coverage.
        assertEquals(0.1375f, result.coverage, 0.0001f)
        assertEquals(result.coverage, result.opacity, 0.0001f)
    }

    @Test
    fun resolve_appliesVelocityAndOrientation() {
        val result = ResolvedDab()
        BrushDynamics.resolve(
            settings = BrushSettings(
                baseRadiusPx = 10f,
                pressureToSize = false,
                pressureToOpacity = false,
                velocityToSize = .5f,
                velocityToOpacity = .5f,
                tiltToRotation = .5f,
            ),
            pressure = 1f,
            tiltX = 0f,
            tiltY = 1f,
            velocity = 1_250f,
            orientationRad = .2f,
            out = result,
        )

        assertEquals(12.5f, result.radius, .0001f)
        assertEquals(.75f, result.coverage, .0001f)
        assertEquals(.2f + PI.toFloat() * .25f, result.rotation, .0001f)
    }
}
