package com.wetinknext.engine.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushLibraryTest {

    @Test
    fun `pencil is a sketching stamp`() {
        val s = BrushLibrary.hb_pencil.settings
        assertEquals("Sketching", s.category)
        assertEquals(BrushRenderMode.STAMP, s.renderMode)
    }

    @Test
    fun `technical pen is an inking ribbon`() {
        val s = BrushLibrary.technical_pen.settings
        assertEquals("Inking", s.category)
        assertEquals(BrushRenderMode.RIBBON, s.renderMode)
    }

    @Test
    fun `airbrush is a soft low-flow stamp`() {
        val s = BrushLibrary.soft_airbrush.settings
        assertEquals("Airbrush", s.category)
        assertEquals(BrushRenderMode.STAMP, s.renderMode)
        assertTrue(s.flow < 0.2f)
        assertTrue(s.spacingUsesDiameter)
    }

    @Test
    fun `watercolor is a wet brush with sane sim parameters`() {
        val s = BrushLibrary.water_color.settings
        assertEquals("Painting", s.category)
        assertEquals(BrushRenderMode.WET, s.renderMode)
        assertEquals(BlendPolicy.NORMAL_BUILDUP, s.blendPolicy)
        val w = s.wet
        assertTrue(w.wetness in 0f..1f)
        assertTrue(w.spread in 0f..1f)
        assertTrue(w.bleed in 0f..1f)
        assertTrue(w.coagulation in 0f..1f)
        assertTrue(w.advection in 0f..1f)
        assertTrue(w.evaporation in 0f..1f)
    }

    @Test
    fun `every preset resolves to in-range settings`() {
        val presets = listOf(
            BrushLibrary.hb_pencil,
            BrushLibrary.charcoal,
            BrushLibrary.chalk,
            BrushLibrary.technical_pen,
            BrushLibrary.studio_pen,
            BrushLibrary.ink_bleed,
            BrushLibrary.acrylic,
            BrushLibrary.oil_filbert,
            BrushLibrary.dry_brush,
            BrushLibrary.round_soft,
            BrushLibrary.water_color,
            BrushLibrary.soft_airbrush,
            BrushLibrary.hard_airbrush,
            BrushLibrary.marker,
            BrushLibrary.fine_liner,
            BrushLibrary.sponge,
            BrushLibrary.splatter,
            BrushLibrary.cracks,
            BrushLibrary.leaf,
            BrushLibrary.fiber,
        )
        for (p in presets) {
            val r = p.settings.resolved()
            assertTrue("radius ${r.baseRadiusPx}", r.baseRadiusPx in 0.25f..4096f)
            assertTrue("opacity ${r.opacity}", r.opacity in 0f..1f)
            assertTrue("flow ${r.flow}", r.flow in 0f..1f)
            if (r.renderMode == BrushRenderMode.WET) {
                assertTrue("wetness ${r.wet.wetness}", r.wet.wetness in 0f..1f)
                assertTrue("spread ${r.wet.spread}", r.wet.spread in 0f..1f)
                assertTrue("coagulation ${r.wet.coagulation}", r.wet.coagulation in 0f..1f)
            }
        }
    }
}