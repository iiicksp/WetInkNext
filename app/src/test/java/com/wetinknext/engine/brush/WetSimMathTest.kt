package com.wetinknext.engine.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WetSimMathTest {

    private fun field(
        w: Int = 8,
        h: Int = 8,
        pig: FloatArray? = null,
        water: FloatArray? = null,
    ): WetSimMath.Field {
        val n = w * h
        val zero = FloatArray(n)
        return WetSimMath.Field(
            width = w,
            height = h,
            pigR = pig?.copyOf() ?: zero.copyOf(),
            pigG = FloatArray(n),
            pigB = FloatArray(n),
            water = if (water != null) water.copyOf() else FloatArray(n),
        )
    }

    private val spreadSettings = WetSettings(wetness = 0.5f, spread = 0.5f)

    @Test
    fun `diffusion spreads water outward and settles toward the mean`() {
        val water = FloatArray(64)
        water[3 * 8 + 3] = 1f
        val f = field(water = water)
        val initialCenter = f.water[3 * 8 + 3]
        WetSimMath.step(f, spreadSettings, deltaSeconds = 1f / 60f)
        assertTrue(initialCenter > f.water[3 * 8 + 3]) // peak settles down
        assertTrue(f.water[3 * 8 + 2] > 0f)           // neighbours pick some up
        assertTrue(f.water[3 * 8 + 4] > 0f)
    }

    @Test
    fun `evaporation dries water and never produces negative`() {
        val f = field(water = FloatArray(64) { 0.9f })
        val dry = WetSettings(wetness = 0f, spread = 0f, evaporation = 1f)
        for (i in 0 until 120) WetSimMath.step(f, dry, deltaSeconds = 1f / 60f)
        f.water.forEach { w ->
            assertTrue(w >= 0f)
            assertTrue(w <= 1f)
        }
        assertTrue(f.water.all { it < 0.01f })
    }

    @Test
    fun `bleed controls how much pigment is carried by water`() {
        // A small wet pigment dot at the centre.
        val n = 64
        val pig = FloatArray(n)
        pig[3 * 8 + 3] = 1f
        val wet = FloatArray(n)
        wet[3 * 8 + 3] = 1f

        val noBleed = field(pig = pig, water = wet)
        val fullBleed = field(pig = pig, water = wet)
        WetSimMath.step(noBleed, WetSettings(wetness = 0.6f, spread = 0.8f, bleed = 0f), 1f / 60f)
        WetSimMath.step(fullBleed, WetSettings(wetness = 0.6f, spread = 0.8f, bleed = 1f), 1f / 60f)

        // Pigment that travels with water decays faster at high bleed.
        val neighbourNo = noBleed.pigR[3 * 8 + 4]
        val neighbourYes = fullBleed.pigR[3 * 8 + 4]
        assertTrue(
            "pigment should be richer at a wet neighbour when bleed is high (got $neighbourNo vs $neighbourYes)",
            neighbourYes > neighbourNo,
        )
    }

    @Test
    fun `coagulation darkens the wet rim`() {
        // Vertical water step: left half wet, right half dry, pigment on the wet side.
        val n = 64
        val pig = FloatArray(n)
        val wet = FloatArray(n)
        for (y in 0 until 8) {
            for (x in 0 until 4) {
                val i = y * 8 + x
                wet[i] = 1f
                pig[i] = 0.5f
            }
        }
        val noCoag = field(pig = pig, water = wet)
        val coag = field(pig = pig, water = wet)
        WetSimMath.step(noCoag, WetSettings(wetness = 0.4f, spread = 0.6f, coagulation = 0f), 1f / 60f)
        WetSimMath.step(coag, WetSettings(wetness = 0.4f, spread = 0.6f, coagulation = 0.8f), 1f / 60f)
        // The boundary column (x=3, last wet pixel) gains pigment with coagulation.
        assertTrue(coag.pigR[4 * 8 + 3] > noCoag.pigR[4 * 8 + 3])
    }

    @Test
    fun `advection shifts wet fluid along the motion direction`() {
        val n = 64
        val water = FloatArray(n)
        water[7 * 8 + 0] = 1f
        val f = field(water = water)
        // Positive X motion moves the blob toward higher X (content pushed right).
        WetSimMath.step(
            f,
            WetSettings(wetness = 0f, spread = 0f, advection = 1f),
            deltaSeconds = 0.05f,
            motionUvPerSecondX = 10f,
        )
        // shiftPx = 10 * 0.05 * 1 * 8 = 4 -> blob lands at x=4.
        assertTrue(f.water[7 * 8 + 4] > 0.5f)
    }

    @Test
    fun `output stays in the unit range`() {
        val f = field(pig = FloatArray(64) { 2f }, water = FloatArray(64) { 3f })
        val s = WetSettings(wetness = 2f, spread = 2f, bleed = 2f, coagulation = 2f, evaporation = 2f)
        WetSimMath.step(f, s, deltaSeconds = 1f / 30f)
        for (i in 0 until 64) {
            assertTrue(f.water[i] in 0.0f..1.0f)
            assertTrue(f.pigR[i] in 0.0f..1.0f)
            assertTrue(f.pigG[i] in 0.0f..1.0f)
            assertTrue(f.pigB[i] in 0.0f..1.0f)
        }
    }

    @Test
    fun `WetSettings resolved clamps new parameters`() {
        val s = BrushSettings(
            wet = WetSettings(
                wetness = 5f,
                spread = -1f,
                bleed = 2f,
                edgeDarkening = -0.5f,
                advection = 4f,
                coagulation = -2f,
                evaporation = 9f,
            ),
        ).resolved().wet
        assertEquals(1f, s.wetness, 0f)
        assertEquals(0f, s.spread, 0f)
        assertEquals(1f, s.bleed, 0f)
        assertEquals(0f, s.edgeDarkening, 0f)
        assertEquals(1f, s.advection, 0f)
        assertEquals(0f, s.coagulation, 0f)
        assertEquals(1f, s.evaporation, 0f)
    }

    @Test
    fun `diffusion is independent of the frame rate`() {
        fun waterField(): WetSimMath.Field {
            val w = FloatArray(64)
            w[3 * 8 + 3] = 1f
            w[4 * 8 + 4] = 0.6f
            return field(water = w)
        }
        val s = WetSettings(wetness = 0.5f, spread = 0.5f)

        // 60 fps, 1 second of wash time.
        val sixtieth = waterField()
        for (i in 0 until 60) WetSimMath.step(sixtieth, s, 1f / 60f)

        // 30 fps, the same 1 second of wash time.
        val thirtieth = waterField()
        for (i in 0 until 30) WetSimMath.step(thirtieth, s, 1f / 30f)

        // Diffusion must converge to the same water field regardless of how the
        // wall-clock time is sliced into frames.
        for (i in 0 until 64) {
            assertEquals(sixtieth.water[i], thirtieth.water[i], 0.02f)
        }
    }
}
