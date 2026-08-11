package com.wetinknext.engine.brush

import org.junit.Assert.assertEquals
import org.junit.Test

class BrushSettingsTest {
    @Test
    fun resolved_clampsBrushValues() {
        val result = BrushSettings(
            baseRadiusPx = -10f,
            opacity = 3f,
            flow = -1f,
            spacing = 100f,
            pressureGamma = 0f,
            minSizeRatio = 4f,
        ).resolved()

        assertEquals(0.25f, result.baseRadiusPx, 0.001f)
        assertEquals(1f, result.opacity, 0.001f)
        assertEquals(0f, result.flow, 0.001f)
        assertEquals(4f, result.spacing, 0.001f)
        assertEquals(0.05f, result.pressureGamma, 0.001f)
        assertEquals(1f, result.minSizeRatio, 0.001f)
    }
}
