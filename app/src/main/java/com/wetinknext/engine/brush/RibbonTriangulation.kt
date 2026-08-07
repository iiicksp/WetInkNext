package com.wetinknext.engine.brush

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class RibbonMesh(val vertices: FloatArray, val indices: IntArray, val coverage: FloatArray = FloatArray(vertices.size / 2) { 1f }, val alpha: FloatArray = FloatArray(vertices.size / 2) { 1f }) { val vertexCount get() = vertices.size / 2; val triangleCount get() = indices.size / 3; val isEmpty get() = indices.isEmpty() }

/** Disk sweep: quads follow segment normals, outer corners use a continuous fan. */
object RibbonTriangulation {
    fun build(outline: RibbonOutline, aaWidthPx: Float = 0f, arcLengths: FloatArray = FloatArray(0), totalLen: Float = 0f, taperStartPx: Float = 0f, taperEndPx: Float = 0f, taperOpacity: Boolean = false, join: RibbonJoin = RibbonJoin.ROUND, miterLimit: Float = 3f): RibbonMesh {
        if (outline.isEmpty) return RibbonMesh(FloatArray(0), IntArray(0))
        val centers = outline.centers; val widths = outline.widths; val n = centers.size
        val vertices = ArrayList<Float>(); val indices = ArrayList<Int>(); val coverage = ArrayList<Float>(); val alpha = ArrayList<Float>()
        fun add(x: Float, y: Float, cov: Float = 1f, arc: Float = -1f): Int { val id = vertices.size / 2; vertices += x; vertices += y; coverage += cov; alpha += if (taperOpacity && arc >= 0f && totalLen > 0f) taper(arc, totalLen, taperStartPx, taperEndPx) else 1f; return id }
        fun triangle(a: Int, b: Int, c: Int) { indices += a; indices += b; indices += c }
        fun arcAt(index: Int) = arcLengths.getOrElse(index) { -1f }
        if (n == 1) { fan(centers[0], outline.startCap, aaWidthPx, widths[0], arcAt(0), ::add, ::triangle); return RibbonMesh(vertices.toFloatArray(), indices.toIntArray(), coverage.toFloatArray(), alpha.toFloatArray()) }
        val segmentCount = if (outline.closed) n else n - 1
        val directions = Array(segmentCount) { i -> val j = (i + 1) % n; val dx = centers[j].x - centers[i].x; val dy = centers[j].y - centers[i].y; val length = hypot(dx, dy).coerceAtLeast(1e-5f); RVec2(dx / length, dy / length) }
        for (i in 0 until segmentCount) {
            val j = (i + 1) % n
            val normal = RVec2(-directions[i].y, directions[i].x); val a = add(centers[i].x + normal.x * widths[i], centers[i].y + normal.y * widths[i], 1f, arcAt(i)); val b = add(centers[i].x - normal.x * widths[i], centers[i].y - normal.y * widths[i], 1f, arcAt(i)); val d = add(centers[j].x + normal.x * widths[j], centers[j].y + normal.y * widths[j], 1f, arcAt(j)); val e = add(centers[j].x - normal.x * widths[j], centers[j].y - normal.y * widths[j], 1f, arcAt(j)); triangle(a,b,d); triangle(b,e,d)
            if (aaWidthPx > 0f) { val a2 = add(centers[i].x + normal.x * (widths[i]+aaWidthPx), centers[i].y + normal.y * (widths[i]+aaWidthPx), 0f, arcAt(i)); val d2 = add(centers[j].x + normal.x * (widths[j]+aaWidthPx), centers[j].y + normal.y * (widths[j]+aaWidthPx), 0f, arcAt(j)); val b2 = add(centers[i].x - normal.x * (widths[i]+aaWidthPx), centers[i].y - normal.y * (widths[i]+aaWidthPx), 0f, arcAt(i)); val e2 = add(centers[j].x - normal.x * (widths[j]+aaWidthPx), centers[j].y - normal.y * (widths[j]+aaWidthPx), 0f, arcAt(j)); triangle(a,a2,d2); triangle(a,d2,d); triangle(b,e2,b2); triangle(b,e,e2) }
        }
        if (join != RibbonJoin.MITER) for (i in 0 until n) {
            if (!outline.closed && (i == 0 || i == n - 1)) continue
            val previous = directions[(i - 1 + segmentCount) % segmentCount]; val next = directions[i % segmentCount]; val cross = previous.x * next.y - previous.y * next.x; if (abs(cross) < 1e-5f) continue
            val pn = RVec2(-previous.y, previous.x); val nn = RVec2(-next.y, next.x); var mx = pn.x + nn.x; var my = pn.y + nn.y; val ml = hypot(mx,my); if (ml < 1e-5f) continue; mx /= ml; my /= ml
            val width = widths[i]; val outerSign = if (cross < 0f) 1f else -1f; val outerA = RVec2(centers[i].x + outerSign * pn.x * width, centers[i].y + outerSign * pn.y * width); val outerB = RVec2(centers[i].x + outerSign * nn.x * width, centers[i].y + outerSign * nn.y * width); fan(centers[i], arcPoints(centers[i], outerA, outerB, width), aaWidthPx, width, arcAt(i), ::add, ::triangle)
            val limit = miterLimit.coerceAtLeast(1f); val cosHalf = (mx * pn.x + my * pn.y).coerceAtLeast(1f / limit); val scale = (1f / cosHalf).coerceAtMost(limit); val innerA = RVec2(centers[i].x - outerSign * pn.x * width, centers[i].y - outerSign * pn.y * width); val innerB = RVec2(centers[i].x - outerSign * nn.x * width, centers[i].y - outerSign * nn.y * width); val innerMiter = RVec2(centers[i].x - outerSign * mx * width * scale, centers[i].y - outerSign * my * width * scale); triangle(add(innerA.x,innerA.y,1f,arcAt(i)),add(innerMiter.x,innerMiter.y,1f,arcAt(i)),add(innerB.x,innerB.y,1f,arcAt(i)))
        }
        if (!outline.closed && outline.startCap.isNotEmpty()) fan(centers[0], outline.startCap, aaWidthPx, widths[0], arcAt(0), ::add, ::triangle)
        if (!outline.closed && outline.endCap.isNotEmpty()) fan(centers.last(), outline.endCap, aaWidthPx, widths.last(), arcAt(n-1), ::add, ::triangle)
        return RibbonMesh(vertices.toFloatArray(), indices.toIntArray(), coverage.toFloatArray(), alpha.toFloatArray())
    }
    private fun fan(center: RVec2, points: List<RVec2>, aa: Float, width: Float, arc: Float, add: (Float,Float,Float,Float)->Int, triangle: (Int,Int,Int)->Unit) { if(points.size<2)return; val c=add(center.x,center.y,1f,arc); val inner=IntArray(points.size){add(points[it].x,points[it].y,1f,arc)}; val outer=if(aa>0f) IntArray(points.size){i->val dx=points[i].x-center.x;val dy=points[i].y-center.y;val l=hypot(dx,dy).coerceAtLeast(1e-5f);add(center.x+dx/l*(width+aa),center.y+dy/l*(width+aa),0f,arc)} else null; for(i in 0 until points.lastIndex){triangle(c,inner[i],inner[i+1]);if(outer!=null){triangle(inner[i],outer[i],outer[i+1]);triangle(inner[i],outer[i+1],inner[i+1])}} }
    private fun arcPoints(center:RVec2,a:RVec2,b:RVec2,width:Float):List<RVec2>{val start=atan2(a.y-center.y,a.x-center.x);var delta=atan2(b.y-center.y,b.x-center.x)-start;while(delta>kotlin.math.PI.toFloat())delta-=2f*kotlin.math.PI.toFloat();while(delta< -kotlin.math.PI.toFloat())delta+=2f*kotlin.math.PI.toFloat();val steps=ceil(abs(delta)/.35f).toInt().coerceIn(1,12);return List(steps+1){i->val angle=start+delta*i/steps;RVec2(center.x+cos(angle)*width,center.y+sin(angle)*width)}}
    private fun taper(arc:Float,total:Float,start:Float,end:Float):Float{val a=if(start<=0f)1f else smooth(arc/start);val b=if(end<=0f)1f else smooth((total-arc)/end);return(a*b).coerceAtLeast(.01f)}
    private fun smooth(value:Float):Float{val x=value.coerceIn(0f,1f);return x*x*(3f-2f*x)}
}
