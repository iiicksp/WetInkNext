package com.wetinknext.engine.brush

import org.junit.Assert.*
import org.junit.Test

class DabBufferTest {
    @Test fun overflowIsCounted(){val b=DabBuffer(2);assertTrue(b.add(0f,0f,1f,0f,1f));assertTrue(b.add(1f,1f,1f,0f,1f));assertFalse(b.add(2f,2f,1f,0f,1f));assertEquals(1L,b.overflowCount)}
    @Test fun clearResets(){val b=DabBuffer(1);b.add(0f,0f,1f,0f,1f);b.clear();assertEquals(0,b.count)}
}
