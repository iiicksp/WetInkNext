package com.wetinknext.engine.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushLibraryTest {
    @Test
    fun penPresetDisablesBothTapers() {
        assertEquals(0f, BrushLibrary.pen.settings.ribbon.taperStartPx, 0f)
        assertEquals(0f, BrushLibrary.pen.settings.ribbon.taperEndPx, 0f)
    }

    @Test
    fun airbrushIsSoftLowFlowStampInPaintCategory() {
        val settings = BrushLibrary.airbrush.settings

        assertEquals("Paint", settings.category)
        assertEquals(BrushRenderMode.STAMP, settings.renderMode)
        assertEquals(80f, settings.baseRadiusPx, 0f)
        assertEquals(0.18f, settings.opacity, 0f)
        assertEquals(0.08f, settings.flow, 0f)
        assertEquals(0.035f, settings.spacing, 0f)
        assertTrue(settings.spacingUsesDiameter)
        assertFalse(settings.pressureToSize)
        assertTrue(settings.pressureToOpacity)
        assertEquals(1.15f, settings.pressureGamma, 0f)
        assertEquals(0f, settings.hardness, 0f)
        assertEquals(3, settings.antiAliasLevel)
        assertEquals(BlendPolicy.NORMAL_BUILDUP, settings.blendPolicy)
        assertTrue(settings.useTempStrokeBuffer)
        assertTrue(BrushLibrary.allCategories.first { it.name == "Paint" }.brushes.contains(BrushLibrary.airbrush))
    }
}
