package com.wetinknext.engine.brush

import com.wetinknext.engine.input.*
import org.junit.Assert.assertEquals
import org.junit.Test

class StampEmitterTest {
    @Test
    fun movingStrokeStartsWithDownDab() {
        val e = StampEmitter(BrushSettings(baseRadiusPx = 10f, spacing = 1f, spacingUsesDiameter = false, pressureToSize = false, pressureToOpacity = false))
        val d = DabBuffer(16)
        val down = InputBatch(1).apply {
            begin(InputAction.DOWN)
            addSample(0f, 0f, 1f, 0f, 0f, 0f, 0, 0, PointerTool.STYLUS, false)
        }
        val move = InputBatch(1).apply {
            begin(InputAction.MOVE)
            addSample(20f, 0f, 1f, 0f, 0f, 0f, 1, 0, PointerTool.STYLUS, false)
        }
        e.begin(down, d, BrushSettings(baseRadiusPx = 10f, spacing = 1f, spacingUsesDiameter = false, pressureToSize = false, pressureToOpacity = false))
        e.append(move, d)
        e.finish(d, false)
        assertEquals(3, d.count)
        assertEquals(0f, d.floats.get(0), 0.001f) // verify xAt(0) is 0
    }

    @Test fun tapProducesExactlyOneDab(){val e=StampEmitter(BrushSettings());val d=DabBuffer(16);val down=InputBatch(1).apply{begin(InputAction.DOWN);addSample(5f,5f,1f,0f,0f,0f,0,0,PointerTool.STYLUS,false)};e.begin(down,d);e.finish(d,false);assertEquals(1,d.count)}

    @Test
    fun activeStrokeKeepsItsSettingsAfterUiBrushChanges() {
        val strokeSettings = BrushSettings(
            baseRadiusPx = 5f,
            spacing = 1f,
            spacingUsesDiameter = false,
            smoothing = 0f,
            pressureToSize = false,
            pressureToOpacity = false,
        )
        val emitter = StampEmitter(strokeSettings)
        val dabs = DabBuffer(16)
        val down = InputBatch(1).apply {
            begin(InputAction.DOWN)
            addSample(0f, 0f, 1f, 0f, 0f, 0f, 0L, 0, PointerTool.STYLUS, false)
        }
        val move = InputBatch(1).apply {
            begin(InputAction.MOVE)
            addSample(20f, 0f, 1f, 0f, 0f, 0f, 1L, 0, PointerTool.STYLUS, false)
        }

        emitter.begin(down, dabs, strokeSettings)
        emitter.updateSettings(strokeSettings.copy(baseRadiusPx = 20f))
        emitter.append(move, dabs)

        assertEquals(5f, dabs.floats.get(2), 0.001f)
    }
}
