package com.wetinknext.engine.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonGeometryBuilderTest {
    @Test
    fun buildsWeldedBanksWithAaAndRoundCaps() {
        val builder = RibbonGeometryBuilder()
        builder.addPoint(0f, 0f, 8f, 1f)
        builder.addPoint(50f, 0f, 8f, 1f)
        builder.addPoint(50f, 50f, 8f, 1f)

        val mesh = requireNotNull(builder.build(RibbonSettings(cap = RibbonCap.ROUND, join = RibbonJoin.ROUND, aaWidthPx = 1f)))

        assertTrue(mesh.indices.isNotEmpty())
        assertEquals(0, mesh.vertices.size % 3)
        assertTrue("round caps and join must add more than strip triangles", mesh.indices.size / 3 > 12)
        assertTrue(mesh.bounds[0] < 0f)
        assertTrue(mesh.bounds[3] > 50f)
    }

    @Test
    fun miterIsClampedAtSharpCorner() {
        val builder = RibbonGeometryBuilder()
        builder.addPoint(0f, 0f, 10f, 1f)
        builder.addPoint(30f, 0f, 10f, 1f)
        builder.addPoint(30.2f, 30f, 10f, 1f)
        val mesh = requireNotNull(builder.build(RibbonSettings(join = RibbonJoin.MITER, miterLimit = 2f)))
        val width = mesh.bounds[2] - mesh.bounds[0]
        assertTrue("unbounded miter spike", width < 80f)
    }

    @Test
    fun pointLimitDecimatesWithoutTruncatingTheEndpoint() {
        val builder = RibbonGeometryBuilder(maxPoints = 8)
        repeat(40) { index ->
            builder.addPoint(index.toFloat(), 0f, 4f, 1f)
        }

        val mesh = requireNotNull(builder.build(RibbonSettings()))

        assertTrue(builder.count <= 8)
        assertTrue("long input must be decimated", builder.decimatedPoints > 0)
        assertTrue("latest endpoint must remain represented", mesh.bounds[2] >= 39f)
    }

    @Test
    fun closedLoopAddsWrapSegmentWithoutCaps() {
        val builder = RibbonGeometryBuilder()
        builder.addPoint(0f, 0f, 4f, 1f)
        builder.addPoint(40f, 0f, 4f, 1f)
        builder.addPoint(20f, 30f, 4f, 1f)

        val mesh = requireNotNull(
            builder.build(
                RibbonSettings(cap = RibbonCap.ROUND, join = RibbonJoin.MITER, aaWidthPx = 0f),
                closed = true,
            ),
        )

        // Three quads: the third is the last-to-first wrap. No cap fan exists.
        assertEquals(3 * 2 * 3, mesh.indices.size)
        assertFalse(mesh.vertices.any { !it.isFinite() })
    }

    @Test
    fun invalidMiterFallsBackWithoutNonFiniteVertices() {
        val builder = RibbonGeometryBuilder()
        builder.addPoint(0f, 0f, 10f, 1f)
        builder.addPoint(20f, 0f, 10f, 1f)
        builder.addPoint(0f, 0.001f, 10f, 1f)

        val mesh = requireNotNull(
            builder.build(
                RibbonSettings(
                    join = RibbonJoin.MITER,
                    miterLimit = 2f,
                    miterFallback = RibbonJoin.BEVEL,
                ),
            ),
        )

        assertTrue(mesh.vertices.all(Float::isFinite))
    }
}
