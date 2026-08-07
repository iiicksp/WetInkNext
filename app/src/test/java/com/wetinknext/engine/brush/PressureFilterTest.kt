package com.wetinknext.engine.brush

import org.junit.Assert.assertTrue
import org.junit.Test

class PressureFilterTest {
    @Test fun singleZeroPressureGlitchDoesNotCollapseWidth() {
        val filter = PressureFilter()
        filter.filter(0L, .6f)
        val filtered = filter.filter(16_000_000L, 0f)
        assertTrue("zero glitch must be ignored while stroke is active", filtered > .55f)
    }

    @Test fun realPressureChangeIsSmoothed() {
        val filter = PressureFilter()
        filter.filter(0L, .8f)
        val filtered = filter.filter(16_000_000L, .2f)
        assertTrue(filtered in .2f.. .8f)
    }
}
