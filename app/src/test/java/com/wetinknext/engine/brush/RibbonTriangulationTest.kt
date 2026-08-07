package com.wetinknext.engine.brush

import kotlin.math.cos
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonTriangulationTest {
    private fun sample(x: Float, y: Float, width: Float) = RibbonSample(x,y,width)
    @Test fun stripContainsTriangles() { val m=RibbonTriangulation.build(RibbonGeometry.build(listOf(sample(0f,0f,5f),sample(100f,0f,5f)),RibbonCap.BUTT,RibbonJoin.ROUND,3f));assertTrue(m.triangleCount>=2);assertEquals(0,m.indices.size%3) }
    @Test fun aaAddsZeroCoverageVertices() { val m=RibbonTriangulation.build(RibbonGeometry.build(listOf(sample(0f,0f,5f),sample(100f,0f,5f)),RibbonCap.BUTT,RibbonJoin.ROUND,3f),1f);assertTrue(m.coverage.any{it==0f}) }
    @Test fun emptyOutlineIsEmptyMesh(){assertTrue(RibbonTriangulation.build(RibbonOutline(emptyList(),FloatArray(0),emptyList(),emptyList())).isEmpty)}
    @Test fun arcHasNoCenterlineChordsOnOuterBoundary() {
        val samples=(0..12).map { i -> val a=Math.toRadians(i*7.5); RibbonSample((200*cos(a)).toFloat(),(200*kotlin.math.sin(a)).toFloat(),10f) }
        val mesh=RibbonTriangulation.build(RibbonGeometry.build(samples,RibbonCap.ROUND,RibbonJoin.ROUND,3f,1f),1f,join=RibbonJoin.ROUND,miterLimit=3f)
        var outer=0; for(v in 0 until mesh.vertexCount){if(mesh.coverage[v]>=.999f){val d=hypot(mesh.vertices[v*2],mesh.vertices[v*2+1]);if(d>150f){outer++;assertTrue(d in 185f..215f)}}};assertTrue(outer>0);assertEquals(0,mesh.indices.size%3)
    }
}
