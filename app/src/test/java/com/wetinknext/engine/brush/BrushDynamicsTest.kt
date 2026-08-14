package com.wetinknext.engine.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            twistRad = 0f,
            trajectoryDx = 1f,
            trajectoryDy = 0f,
            velocityPxPerSecond = 0f,
            randomSize = 0.5f,
            randomOpacity = 0.5f,
            randomRotation = 0.5f,
            randomScatterRadius = 0f,
            randomScatterAngle = 0f,
            out = result,
        )

        // p = 0.5 ^ 2 = 0.25; pressure size = .325; tilt size = 1.5.
        assertEquals(4.875f, result.radius, 0.0001f)
        // Tilt attenuates, rather than amplifies, local coverage.
        assertEquals(0.1375f, result.coverage, 0.0001f)
        assertEquals(1f, result.flow, 0.0001f)
        assertEquals(1f, result.hardness, 0.0001f)
    }

    @Test
    fun resolve_appliesVelocityAndTiltRotation() {
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
            velocityPxPerSecond = 1_200f,
            random01 = 0.5f,
            out = result,
        )

        assertEquals(7.5f, result.radius, .0001f)
        assertEquals(.75f, result.coverage, .0001f)
        assertEquals(PI.toFloat() * .25f, result.rotationRad, .0001f)
    }

    @Test
    fun resolve_usesProvidedRandomValueDeterministically() {
        val settings = BrushSettings(
            baseRadiusPx = 10f,
            pressureToSize = false,
            pressureToOpacity = false,
            sizeJitter = .2f,
            opacityJitter = .2f,
            scatter = .5f,
        )
        val first = ResolvedDab()
        val repeated = ResolvedDab()
        val other = ResolvedDab()

        BrushDynamics.resolve(settings, 1f, 0f, 0f, 0f, .75f, first)
        BrushDynamics.resolve(settings, 1f, 0f, 0f, 0f, .75f, repeated)
        BrushDynamics.resolve(settings, 1f, 0f, 0f, 0f, .25f, other)

        assertEquals(first.radius, repeated.radius, 0f)
        assertEquals(first.coverage, repeated.coverage, 0f)
        assertEquals(first.scatterX, repeated.scatterX, 0f)
        assertEquals(first.scatterY, repeated.scatterY, 0f)
        assertNotEquals(first.radius, other.radius, 0.0001f)
    }
    @Test
    fun twoIdenticalBrushesWithDifferentRandomStreamsYieldIndependentValues() {
        val settings = BrushSettings(
            sizeJitter = 1f,
            opacityJitter = 1f,
            rotationJitter = 1f,
        )
        val dab1 = ResolvedDab()
        BrushDynamics.resolve(settings, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f, 0.1f, 0.2f, 0.3f, 0f, 0f, dab1)
            
        val dab2 = ResolvedDab()
        BrushDynamics.resolve(settings, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f, 0.9f, 0.8f, 0.7f, 0f, 0f, dab2)

        assertNotEquals(dab1.radius, dab2.radius)
        assertNotEquals(dab1.coverage, dab2.coverage)
        assertNotEquals(dab1.rotation, dab2.rotation)
    }

    @Test
    fun lutChangesPressureShape() {
        val settings = BrushSettings(
            baseRadiusPx = 14f,
            pressureCurve = DynamicsCurve(listOf(0f, 0.2f, 1f)),
            pressureToSize = true,
            minSizeRatio = 0f,
        )
        val dabMid = ResolvedDab()
        BrushDynamics.resolve(settings, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f, 0.5f, 0f, 0f, dabMid)
        
        assertEquals(14f * 0.2f, dabMid.radius, 0.001f)
    }
}