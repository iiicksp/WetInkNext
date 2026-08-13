package com.wetinknext.engine.brush

import com.wetinknext.engine.core.DirtyRect
import org.junit.Assert.*
import org.junit.Test

class DabBufferTest {
    @Test fun overflowIsCounted(){val b=DabBuffer(2);assertTrue(b.add(0f,0f,1f,0f,1f,1f,1f));assertTrue(b.add(1f,1f,1f,0f,1f,1f,1f));assertFalse(b.add(2f,2f,1f,0f,1f,1f,1f));assertEquals(1L,b.overflowCount)}
    @Test fun clearResets(){val b=DabBuffer(1);b.add(0f,0f,1f,0f,1f,1f,1f);b.clear();assertEquals(0,b.count)}
    @Test fun dirtyRectIncludesTextureMargin(){
        val buffer = DabBuffer(1)
        buffer.add(10f, 20f, 5f, 0f, 1f, 1f, 1f)
        val rect = DirtyRect()
        buffer.includeDirtyRect(rect, extraMargin = 4f)
        assertEquals(1f, rect.left, 0.001f)
        assertEquals(19f, rect.right, 0.001f)
        assertEquals(11f, rect.top, 0.001f)
        assertEquals(29f, rect.bottom, 0.001f)
    }
}
