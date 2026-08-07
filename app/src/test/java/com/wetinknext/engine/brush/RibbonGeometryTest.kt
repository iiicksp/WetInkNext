package com.wetinknext.engine.brush

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonGeometryTest {
    private fun sample(x: Float, y: Float, width: Float) = RibbonSample(x, y, width)
    @Test fun horizontalStrokeKeepsAllCenters() {
        val outline = RibbonGeometry.build(listOf(sample(0f,0f,5f), sample(100f,0f,5f), sample(200f,0f,5f)), RibbonCap.BUTT, RibbonJoin.ROUND, 3f, 1f)
        assertEquals(3, outline.centers.size); assertEquals(5f, outline.widths[0], .01f); assertTrue(outline.startCap.isEmpty())
    }
    @Test fun tapBuildsCircleCap() {
        val outline = RibbonGeometry.build(listOf(sample(10f,20f,7f)), RibbonCap.ROUND, RibbonJoin.ROUND, 3f)
        assertTrue(outline.startCap.size >= 8); outline.startCap.forEach { assertEquals(7f, hypot(it.x-10f,it.y-20f),.01f) }
    }
    @Test fun shortOpenStrokeBuildsOneRoundDab() {
        val outline = RibbonGeometry.build(
            listOf(sample(10f, 20f, 12f), sample(14f, 20f, 12f)),
            RibbonCap.ROUND,
            RibbonJoin.ROUND,
            3f,
        )

        assertEquals(1, outline.centers.size)
        assertEquals(12f, outline.widths[0], .01f)
        assertTrue(outline.startCap.size >= 8)
        assertTrue(outline.endCap.isEmpty())

        val mesh = RibbonTriangulation.build(outline, aaWidthPx = 1f)
        assertTrue(mesh.triangleCount > 0)
    }
    @Test fun closedLoopIsNotReducedToDab() {
        val outline = RibbonGeometry.build(
            listOf(sample(0f, 0f, 12f), sample(2f, 0f, 12f), sample(1f, 2f, 12f)),
            RibbonCap.ROUND,
            RibbonJoin.ROUND,
            3f,
            closed = true,
        )

        assertTrue(outline.closed)
        assertEquals(3, outline.centers.size)
    }
}
