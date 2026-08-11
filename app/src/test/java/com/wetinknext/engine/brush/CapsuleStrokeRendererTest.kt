package com.wetinknext.engine.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleStrokeRendererTest {

    @Test
    fun initiallyEmpty() {
        val renderer = CapsuleStrokeRenderer(maxSegments = 100)
        assertTrue(renderer.isEmpty)
        assertEquals(0, renderer.segmentCount)
    }

    @Test
    fun addsSegmentsWithinCapacity() {
        val renderer = CapsuleStrokeRenderer(maxSegments = 10)
        renderer.beginStroke()
        
        for (i in 0 until 10) {
            val added = renderer.addSegment(0f, 0f, 5f, 1f, 10f, 10f, 5f, 1f)
            assertTrue("Segment $i should be added", added)
        }
        
        assertEquals(10, renderer.segmentCount)
        assertFalse(renderer.isEmpty)
        
        val addedOverflow = renderer.addSegment(0f, 0f, 5f, 1f, 10f, 10f, 5f, 1f)
        assertFalse("Overflow segment should not be added", addedOverflow)
        assertEquals(1, renderer.overflowCount)
    }

    @Test
    fun clearResetsStateButNotOverflow() {
        val renderer = CapsuleStrokeRenderer(maxSegments = 10)
        renderer.beginStroke()
        renderer.addSegment(0f, 0f, 5f, 1f, 10f, 10f, 5f, 1f)
        
        // Overflow it
        for (i in 0 until 10) renderer.addSegment(0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f)
        assertEquals(1, renderer.overflowCount)
        
        renderer.clearStrokeData()
        assertTrue(renderer.isEmpty)
        assertEquals(0, renderer.segmentCount)
        assertEquals(1, renderer.overflowCount)
    }
}
