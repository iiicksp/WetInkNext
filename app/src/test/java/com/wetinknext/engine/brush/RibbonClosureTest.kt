package com.wetinknext.engine.brush

import com.wetinknext.engine.input.InputAction
import com.wetinknext.engine.input.InputBatch
import com.wetinknext.engine.input.PointerTool
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonClosureTest {
    private fun sample(x: Float, y: Float, width: Float) = RibbonSample(x, y, width)

    @Test fun closedOutlineHasNoCapsAndHasWrapSegment() {
        val points = (0 until 24).map { i ->
            val angle = 2.0 * Math.PI * i / 24
            sample(60f + 40f * cos(angle).toFloat(), 60f + 40f * sin(angle).toFloat(), 4f)
        }
        val outline = RibbonGeometry.build(points, RibbonCap.ROUND, RibbonJoin.ROUND, 2.5f, 1f, closed = true)
        assertTrue(outline.startCap.isEmpty() && outline.endCap.isEmpty())
        val mesh = RibbonTriangulation.build(outline, 1f, join = RibbonJoin.ROUND, miterLimit = 2.5f)
        assertTrue("wrap segment missing", mesh.triangleCount >= 2 * points.size)
    }

    @Test fun openStrokeKeepsCapsAndHasNoWrap() {
        val points = listOf(sample(0f, 0f, 4f), sample(40f, 15f, 4f), sample(80f, 0f, 4f))
        val outline = RibbonGeometry.build(points, RibbonCap.ROUND, RibbonJoin.ROUND, 2.5f, 1f, closed = false)
        assertTrue(outline.startCap.isNotEmpty() && outline.endCap.isNotEmpty())
        assertFalse(outline.closed)
    }

    @Test fun emitterRecognizesCircleReturnedToStart() {
        val emitter = RibbonEmitter(BrushSettings(renderMode = BrushRenderMode.RIBBON, baseRadiusPx = 9f, smoothing = 0f, streamline = 0f, ribbon = RibbonSettings(minPointDistancePx = .5f)))
        val down = batch(InputAction.DOWN, 100f, 60f, 0L)
        emitter.begin(down)
        val move = InputBatch(32).apply {
            begin(InputAction.MOVE)
            for (i in 1..24) {
                val a = 2.0 * Math.PI * i / 24
                addSample(60f + 40f * cos(a).toFloat(), 60f + 40f * sin(a).toFloat(), .5f, 0f, 0f, 0f, i * 16_000_000L, 0, PointerTool.STYLUS, i > 1)
            }
        }
        emitter.append(move)
        emitter.append(batch(InputAction.UP, 100f, 60f, 25 * 16_000_000L))
        emitter.finish(cancel = false)
        assertTrue(emitter.closedLoop)
        assertTrue(emitter.lastClosureDistance <= emitter.lastClosureThreshold)
    }

    @Test fun openStrokeIsNotClosed() {
        val emitter = RibbonEmitter(BrushSettings(renderMode = BrushRenderMode.RIBBON, baseRadiusPx = 9f, smoothing = 0f, streamline = 0f))
        emitter.begin(batch(InputAction.DOWN, 0f, 0f, 0L))
        emitter.append(batch(InputAction.MOVE, 120f, 80f, 16_000_000L))
        emitter.append(batch(InputAction.UP, 120f, 80f, 32_000_000L))
        emitter.finish(cancel = false)
        assertFalse(emitter.closedLoop)
    }

    private fun batch(action: InputAction, x: Float, y: Float, timestamp: Long) = InputBatch(8).apply {
        begin(action)
        addSample(x, y, .5f, 0f, 0f, 0f, timestamp, 0, PointerTool.STYLUS, false)
    }
}
