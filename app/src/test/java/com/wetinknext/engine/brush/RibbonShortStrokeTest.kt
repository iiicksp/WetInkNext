package com.wetinknext.engine.brush

import com.wetinknext.engine.input.InputAction
import com.wetinknext.engine.input.InputBatch
import com.wetinknext.engine.input.PointerTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonShortStrokeTest {

    @Test fun bigBrushShortStrokeIsNotClosed() {
        val e = RibbonEmitter(
            BrushSettings(
                renderMode = BrushRenderMode.RIBBON, baseRadiusPx = 132f,
                smoothing = 0f, streamline = 0f,
                ribbon = RibbonSettings(minPointDistancePx = 1.25f),
            ),
        )
        e.begin(batch(InputAction.DOWN, 500f, 500f, 0L))
        e.append(batch(InputAction.MOVE, 560f, 520f, 16_000_000L))
        e.append(batch(InputAction.MOVE, 620f, 505f, 32_000_000L))
        e.append(batch(InputAction.UP, 630f, 500f, 48_000_000L))
        e.finish(cancel = false)
        assertFalse("короткий штрих толстой кистью не должен считаться петлёй", e.closedLoop)
    }

    @Test fun tapWithFewSamplesBecomesDisk() {
        val outline = RibbonGeometry.build(
            listOf(RibbonSample(10f, 10f, 60f), RibbonSample(12f, 11f, 60f), RibbonSample(13f, 10f, 60f)),
            RibbonCap.ROUND, RibbonJoin.ROUND, 3f, 1f,
        )
        assertEquals(1, outline.centers.size)
        assertTrue(outline.startCap.size >= 8)
    }

    @Test fun endCapPointsForwardNotIntoTheStroke() {
        val outline = RibbonGeometry.build(
            listOf(RibbonSample(0f, 0f, 5f), RibbonSample(100f, 0f, 5f)),
            RibbonCap.ROUND, RibbonJoin.ROUND, 3f, 1f,
        )
        assertTrue("end cap ушёл внутрь штриха", outline.endCap.any { it.x > 100.5f })
        assertTrue("start cap ушёл внутрь штриха", outline.startCap.any { it.x < -0.5f })
    }

    @Test
    fun finishFlushesTheLastInterpolatedSection() {
        val settings = BrushSettings(
            renderMode = BrushRenderMode.RIBBON,
            baseRadiusPx = 10f,
            smoothing = 0.5f,
            streamline = 0f,
        )
        val emitter = CapsuleEmitter(settings)
        val renderer = CapsuleStrokeRenderer(maxSegments = 128)

        emitter.begin(batch(InputAction.DOWN, 0f, 0f, 0L), renderer)
        emitter.append(batch(InputAction.MOVE, 100f, 0f, 16_000_000L), renderer)
        emitter.append(batch(InputAction.MOVE, 200f, 30f, 32_000_000L), renderer)
        emitter.finish(renderer, cancel = false)

        assertTrue(emitter.hasStroke)
        assertTrue(renderer.segmentCount > 1)
    }

    private fun batch(action: InputAction, x: Float, y: Float, t: Long) = InputBatch(8).apply {
        begin(action)
        addSample(x, y, .5f, 0f, 0f, 0f, t, 0, PointerTool.STYLUS, false)
    }
}
