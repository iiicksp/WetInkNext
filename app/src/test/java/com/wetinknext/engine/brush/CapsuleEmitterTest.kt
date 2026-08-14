package com.wetinknext.engine.brush

import com.wetinknext.engine.input.InputAction
import com.wetinknext.engine.input.InputBatch
import com.wetinknext.engine.input.PointerTool
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleEmitterTest {

    @Test
    fun finishFlushesTheLastInterpolatedSection() {
        val settings = BrushSettings(
            renderMode = BrushRenderMode.RIBBON,
            baseRadiusPx = 10f,
            smoothing = 0.5f,
            streamline = 0f,
        )
        val emitter = CapsuleEmitter(settings)
        // We need a dummy renderer to receive segments
        // Since we can't easily mock GL context here, we just use a small maxSegments
        val renderer = CapsuleStrokeRenderer(maxSegments = 128)

        emitter.begin(batch(InputAction.DOWN, 0f, 0f, 0L), renderer)
        emitter.append(batch(InputAction.MOVE, 100f, 0f, 16_000_000L), renderer)
        emitter.append(batch(InputAction.MOVE, 200f, 30f, 32_000_000L), renderer)
        
        val segmentsBeforeFinish = renderer.segmentCount
        emitter.finish(renderer, cancel = false)
        val segmentsAfterFinish = renderer.segmentCount

        assertTrue("Should have some segments after append", segmentsBeforeFinish > 0)
        assertTrue("Finish should have flushed more segments", segmentsAfterFinish > segmentsBeforeFinish)
        assertTrue(emitter.hasStroke)
    }

    @Test
    fun openStrokeDoesNotCloseLoop() {
        val settings = BrushSettings(
            renderMode = BrushRenderMode.RIBBON,
            ribbon = RibbonSettings(autoCloseLoop = false)
        )
        val emitter = CapsuleEmitter(settings)
        val renderer = CapsuleStrokeRenderer(maxSegments = 128)

        emitter.begin(batch(InputAction.DOWN, 0f, 0f, 0L), renderer)
        emitter.append(batch(InputAction.MOVE, 100f, 0f, 16_000_000L), renderer)
        emitter.append(batch(InputAction.MOVE, 0f, 0f, 32_000_000L), renderer) // Return to start
        
        emitter.finish(renderer, cancel = false)

        assertTrue(emitter.hasStroke)
        assertTrue("Loop should remain open because autoCloseLoop is false", !emitter.closedLoop)
    }

    @Test
    fun closedStrokeRecognizesLoopIfEnabled() {
        val settings = BrushSettings(
            renderMode = BrushRenderMode.RIBBON,
            ribbon = RibbonSettings(autoCloseLoop = true)
        )
        val emitter = CapsuleEmitter(settings)
        val renderer = CapsuleStrokeRenderer(maxSegments = 128)

        emitter.begin(batch(InputAction.DOWN, 0f, 0f, 0L), renderer)
        emitter.append(batch(InputAction.MOVE, 100f, 0f, 16_000_000L), renderer)
        emitter.append(batch(InputAction.MOVE, 100f, 100f, 32_000_000L), renderer)
        emitter.append(batch(InputAction.MOVE, 0f, 100f, 48_000_000L), renderer)
        // Return exactly to start to force closure
        emitter.append(batch(InputAction.MOVE, 0f, 0f, 64_000_000L), renderer)
        
        emitter.finish(renderer, cancel = false)

        assertTrue(emitter.hasStroke)
        assertTrue("Loop should close since stroke returned to start and autoCloseLoop is true", emitter.closedLoop)
    }

    private fun batch(action: InputAction, x: Float, y: Float, t: Long) = InputBatch(8).apply {
        begin(action)
        addSample(x, y, .5f, 0f, 0f, 0f, t, 0, PointerTool.STYLUS, false)
    }
    @Test
    fun openRibbonNeverClosesWhenAutoCloseFalse() {
        val settings = BrushSettings(
            renderMode = BrushRenderMode.RIBBON,
            ribbon = RibbonSettings(autoCloseLoop = false)
        )
        val emitter = CapsuleEmitter(settings)
        val renderer = CapsuleStrokeRenderer(maxSegments = 128)

        emitter.begin(batch(InputAction.DOWN, 0f, 0f, 0L), renderer, settings)
        emitter.append(batch(InputAction.MOVE, 100f, 0f, 16_000_000L), renderer)
        emitter.append(batch(InputAction.MOVE, 200f, 30f, 32_000_000L), renderer)
        emitter.finish(renderer, cancel = false)

        org.junit.Assert.assertFalse(renderer.isClosedLoop)
    }

    @Test
    fun closedRibbonClosesOnlyWhenTrue() {
        val settings = BrushSettings(
            renderMode = BrushRenderMode.RIBBON,
            ribbon = RibbonSettings(autoCloseLoop = true)
        )
        val emitter = CapsuleEmitter(settings)
        val renderer = CapsuleStrokeRenderer(maxSegments = 128)

        emitter.begin(batch(InputAction.DOWN, 0f, 0f, 0L), renderer, settings)
        emitter.append(batch(InputAction.MOVE, 100f, 0f, 16_000_000L), renderer)
        // Closing back near the start
        emitter.append(batch(InputAction.MOVE, 5f, 5f, 32_000_000L), renderer)
        emitter.finish(renderer, cancel = false)

        org.junit.Assert.assertTrue(renderer.isClosedLoop)
    }
}