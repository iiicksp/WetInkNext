package com.wetinknext.engine.brush

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class RVec2(val x: Float, val y: Float)
data class RibbonSample(val x: Float, val y: Float, val halfWidth: Float)

/** Axis and widths only. Segment sweep and joins are triangulated separately. */
data class RibbonOutline(
    val centers: List<RVec2>,
    val widths: FloatArray,
    val startCap: List<RVec2>,
    val endCap: List<RVec2>,
    val closed: Boolean = false,
) { val isEmpty: Boolean get() = centers.isEmpty() }

object RibbonGeometry {
    private const val CAP_SEGMENTS = 48
    private const val DOT_SPAN_RATIO = 0.75f

    fun build(samples: List<RibbonSample>, cap: RibbonCap, join: RibbonJoin, miterLimit: Float, aaWidthPx: Float = 0f, closed: Boolean = false): RibbonOutline {
        val clean = dedupe(samples)
        if (clean.isEmpty()) return RibbonOutline(emptyList(), FloatArray(0), emptyList(), emptyList())

        val isClosed = closed && clean.size >= 3
        if (clean.size == 1) {
            val point = clean.first(); val width = point.halfWidth.coerceAtLeast(0f)
            return RibbonOutline(listOf(RVec2(point.x, point.y)), floatArrayOf(width), fullCircle(point.x, point.y, width), emptyList())
        }

        // 2-5 сэмплов внутри собственного радиуса: sweep даст сектор/треугольник. Рисуем диск.
        var widest = 0f
        for (s in clean) if (s.halfWidth > widest) widest = s.halfWidth
        if (widest > 0f && pathLength(clean) <= widest * DOT_SPAN_RATIO) {
            val p = clean.last()
            return RibbonOutline(
                listOf(RVec2(p.x, p.y)),
                floatArrayOf(widest),
                fullCircle(p.x, p.y, widest),
                emptyList(),
            )
        }

        val centers = ArrayList<RVec2>(clean.size); val widths = FloatArray(clean.size)
        for (i in clean.indices) { centers += RVec2(clean[i].x, clean[i].y); widths[i] = clean[i].halfWidth.coerceAtLeast(0f) }
        val start = if (!isClosed && cap == RibbonCap.ROUND) endArc(clean.first(), segmentDirection(clean, 0), widths.first(), true) else emptyList()
        val end = if (!isClosed && cap == RibbonCap.ROUND) endArc(clean.last(), segmentDirection(clean, clean.lastIndex - 1), widths.last(), false) else emptyList()
        return RibbonOutline(centers, widths, start, end, isClosed)
    }

    private fun dedupe(samples: List<RibbonSample>): List<RibbonSample> {
        if (samples.isEmpty()) return samples
        val result = ArrayList<RibbonSample>(samples.size); result += samples.first()
        for (i in 1 until samples.size) if (hypot(samples[i].x - result.last().x, samples[i].y - result.last().y) >= 1e-4f) result += samples[i]
        return result
    }
    private fun pathLength(samples: List<RibbonSample>): Float {
        var total = 0f
        for (i in 1 until samples.size) total += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
        return total
    }
    private fun segmentDirection(samples: List<RibbonSample>, index: Int): RVec2 { val dx = samples[index + 1].x - samples[index].x; val dy = samples[index + 1].y - samples[index].y; val length = hypot(dx, dy).coerceAtLeast(1e-5f); return RVec2(dx / length, dy / length) }
    private fun endArc(sample: RibbonSample, direction: RVec2, radius: Float, tail: Boolean): List<RVec2> {
        if (radius <= 0f) return emptyList()
        val base = if (tail) atan2(-direction.x, direction.y) else atan2(direction.x, -direction.y)
        val sweep = -kotlin.math.PI.toFloat()
        return List(CAP_SEGMENTS + 1) { i ->
            val angle = base + sweep * i / CAP_SEGMENTS
            RVec2(sample.x + radius * cos(angle), sample.y + radius * sin(angle))
        }
    }
    private fun fullCircle(x: Float, y: Float, radius: Float) = if (radius <= 0f) emptyList() else List(CAP_SEGMENTS + 1) { i -> val angle = 2f * kotlin.math.PI.toFloat() * i / CAP_SEGMENTS; RVec2(x + radius * cos(angle), y + radius * sin(angle)) }
}
