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

    fun build(samples: List<RibbonSample>, cap: RibbonCap, join: RibbonJoin, miterLimit: Float, aaWidthPx: Float = 0f, closed: Boolean = false): RibbonOutline {
        val clean = dedupe(samples)
        if (clean.isEmpty()) return RibbonOutline(emptyList(), FloatArray(0), emptyList(), emptyList())

        val isClosed = closed && clean.size >= 3
        if (clean.size == 1) {
            val point = clean.first(); val width = point.halfWidth.coerceAtLeast(0f)
            return RibbonOutline(listOf(RVec2(point.x, point.y)), floatArrayOf(width), fullCircle(point.x, point.y, width), emptyList())
        }

        // A stylus tap may contain a few distinct samples, but its path is much
        // shorter than the brush radius.  Rendering such a path as a thin
        // segment plus two direction-dependent half-caps is numerically
        // unstable and can produce a wedge instead of a dot.  Treat open
        // micro-strokes as one round dab.  Closed loops intentionally bypass
        // this branch and retain their sweep geometry.
        val maxWidth = clean.maxOf { it.halfWidth.coerceAtLeast(0f) }
        val pathLength = pathLength(clean)
        if (!isClosed && pathLength < maxWidth * SHORT_STROKE_RADIUS_RATIO) {
            var sumX = 0f
            var sumY = 0f
            for (sample in clean) {
                sumX += sample.x
                sumY += sample.y
            }
            val centerX = sumX / clean.size
            val centerY = sumY / clean.size
            return RibbonOutline(
                centers = listOf(RVec2(centerX, centerY)),
                widths = floatArrayOf(maxWidth),
                startCap = fullCircle(centerX, centerY, maxWidth),
                endCap = emptyList(),
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
        var length = 0f
        for (i in 1 until samples.size) {
            length += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
        }
        return length
    }
    private fun segmentDirection(samples: List<RibbonSample>, index: Int): RVec2 { val dx = samples[index + 1].x - samples[index].x; val dy = samples[index + 1].y - samples[index].y; val length = hypot(dx, dy).coerceAtLeast(1e-5f); return RVec2(dx / length, dy / length) }
    private fun endArc(sample: RibbonSample, direction: RVec2, radius: Float, tail: Boolean): List<RVec2> { if (radius <= 0f) return emptyList(); val base = if (tail) atan2(-direction.x, direction.y) else atan2(direction.x, -direction.y); val sweep = if (tail) -kotlin.math.PI.toFloat() else kotlin.math.PI.toFloat(); return List(CAP_SEGMENTS + 1) { i -> val angle = base + sweep * i / CAP_SEGMENTS; RVec2(sample.x + radius * cos(angle), sample.y + radius * sin(angle)) } }
    private fun fullCircle(x: Float, y: Float, radius: Float) = if (radius <= 0f) emptyList() else List(CAP_SEGMENTS + 1) { i -> val angle = 2f * kotlin.math.PI.toFloat() * i / CAP_SEGMENTS; RVec2(x + radius * cos(angle), y + radius * sin(angle)) }

    private const val SHORT_STROKE_RADIUS_RATIO = 0.5f
}
