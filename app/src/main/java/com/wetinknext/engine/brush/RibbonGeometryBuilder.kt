package com.wetinknext.engine.brush

import android.util.Log
import com.wetinknext.BuildConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds a welded ribbon mesh without allocating point work arrays per stroke.
 *
 * The body strip is mandatory. AA, joins and caps are best-effort decorations:
 * they are omitted before the body can exceed the renderer's hard mesh limits.
 */
class RibbonGeometryBuilder(
    private val maxPoints: Int = MAX_RIBBON_POINTS,
) {
    private val positions = FloatArray(maxPoints * 2)
    private val radii = FloatArray(maxPoints)
    private val coverage = FloatArray(maxPoints)
    private val rotations = FloatArray(maxPoints)
    private val cumulativeLength = FloatArray(maxPoints)

    // Reused build workspace: no per-UP tangent/bank/index-array allocation.
    private val tangentX = FloatArray(maxPoints)
    private val tangentY = FloatArray(maxPoints)
    private val leftX = FloatArray(maxPoints)
    private val leftY = FloatArray(maxPoints)
    private val rightX = FloatArray(maxPoints)
    private val rightY = FloatArray(maxPoints)
    private val effectiveRadius = FloatArray(maxPoints)
    private val leftInner = IntArray(maxPoints)
    private val rightInner = IntArray(maxPoints)
    private val leftOuter = IntArray(maxPoints)
    private val rightOuter = IntArray(maxPoints)
    private val miterFallbackRequired = BooleanArray(maxPoints)
    private val writer = RibbonMeshWriter(MAX_RIBBON_VERTICES, MAX_RIBBON_INDICES)

    private var decimationStride = 1
    private var pendingSkipped = 0
    private var loggedPointLimit = false

    var count: Int = 0
        private set
    var decimatedPoints: Int = 0
        private set

    fun clear() {
        count = 0
        decimationStride = 1
        pendingSkipped = 0
        decimatedPoints = 0
        loggedPointLimit = false
    }

    /**
     * Always keeps the newest endpoint once decimation begins. This deliberately
     * prefers a coarser continuous stroke over a silently truncated one.
     */
    fun addPoint(
        x: Float,
        y: Float,
        radius: Float,
        coverageValue: Float,
        rotationRad: Float = 0f,
    ): Boolean {
        if (count == 0) {
            appendPoint(x, y, radius, coverageValue, rotationRad)
            return true
        }

        val last = count - 1
        val previousOffset = last * 2
        if (hypot(x - positions[previousOffset], y - positions[previousOffset + 1]) < POINT_EPSILON) {
            updateLastPoint(x, y, radius, coverageValue, rotationRad)
            return true
        }

        if (count >= (maxPoints * 3) / 4) {
            decimationStride = 4
        } else if (count >= maxPoints / 2) {
            decimationStride = 2
        }
        if (decimationStride > 1) {
            pendingSkipped++
            if (pendingSkipped < decimationStride) {
                updateLastPoint(x, y, radius, coverageValue, rotationRad)
                decimatedPoints++
                return true
            }
            pendingSkipped = 0
        }

        if (count >= maxPoints) {
            updateLastPoint(x, y, radius, coverageValue, rotationRad)
            decimatedPoints++
            if (BuildConfig.DEBUG && !loggedPointLimit) {
                loggedPointLimit = true
                logDebugWarning("ribbon point limit reached; keeping newest endpoint")
            }
            return true
        }

        appendPoint(x, y, radius, coverageValue, rotationRad)
        return true
    }

    /** Vertex format: x, y, coverage. Indices are triangles. */
    data class Mesh(val vertices: FloatArray, val indices: IntArray, val bounds: FloatArray)

    /**
     * Builds an open ribbon by default. [closed] creates the wrap segment and
     * suppresses end caps; the caller owns loop recognition.
     */
    fun build(settings: RibbonSettings, closed: Boolean = false): Mesh? {
        if (count == 0) return null
        if (count == 1) return buildDot(settings)

        writer.reset()
        val isClosed = closed && count >= 3
        miterFallbackRequired.fill(false, 0, count)
        val total = cumulativeLength[count - 1].coerceAtLeast(POINT_EPSILON)
        for (i in 0 until count) {
            tangentAt(i, isClosed)
            effectiveRadius[i] = radii[i] * taperScale(cumulativeLength[i], total, settings)
        }
        for (i in 0 until count) {
            val p = i * 2
            val nx = -tangentY[i]
            val ny = tangentX[i]
            var offsetX = nx * effectiveRadius[i]
            var offsetY = ny * effectiveRadius[i]
            val isJoin = if (isClosed) true else i in 1 until count - 1
            if (isJoin && settings.join == RibbonJoin.MITER) {
                val miter = miterOffset(i, effectiveRadius[i], settings.miterLimit)
                if (miter != null) {
                    offsetX = miter.first
                    offsetY = miter.second
                } else {
                    miterFallbackRequired[i] = true
                }
            }
            leftX[i] = positions[p] + offsetX
            leftY[i] = positions[p + 1] + offsetY
            rightX[i] = positions[p] - offsetX
            rightY[i] = positions[p + 1] - offsetY
        }

        val aaRequested = settings.aaWidthPx.coerceAtLeast(0f)
        val bodyVertices = count * 2
        val segmentCount = if (isClosed) count else count - 1
        val bodyIndices = segmentCount * 6
        val aaVertices = if (aaRequested > 0f) count * 2 else 0
        val aaIndices = if (aaRequested > 0f) segmentCount * 12 else 0
        val useAa = aaRequested > 0f && writer.canFit(bodyVertices + aaVertices, bodyIndices + aaIndices)
        if (!writer.canFit(bodyVertices, bodyIndices)) {
            // This should be unreachable with the constants above, but avoids a
            // malformed partial mesh if those constants are changed later.
            if (BuildConfig.DEBUG) logDebugWarning("ribbon body exceeds mesh limits")
            return null
        }

        for (i in 0 until count) {
            val p = i * 2
            val nx = -tangentY[i]
            val ny = tangentX[i]
            leftInner[i] = writer.add(leftX[i], leftY[i], coverage[i])
            rightInner[i] = writer.add(rightX[i], rightY[i], coverage[i])
            if (useAa) {
                leftOuter[i] = writer.add(
                    positions[p] + nx * (effectiveRadius[i] + aaRequested),
                    positions[p + 1] + ny * (effectiveRadius[i] + aaRequested),
                    0f,
                )
                rightOuter[i] = writer.add(
                    positions[p] - nx * (effectiveRadius[i] + aaRequested),
                    positions[p + 1] - ny * (effectiveRadius[i] + aaRequested),
                    0f,
                )
            }
        }
        for (i in 0 until segmentCount) {
            val next = (i + 1) % count
            writer.triangle(leftInner[i], rightInner[i], leftInner[next])
            writer.triangle(rightInner[i], rightInner[next], leftInner[next])
            if (useAa) {
                writer.triangle(leftOuter[i], leftInner[i], leftOuter[next])
                writer.triangle(leftInner[i], leftInner[next], leftOuter[next])
                writer.triangle(rightInner[i], rightOuter[i], rightInner[next])
                writer.triangle(rightOuter[i], rightOuter[next], rightInner[next])
            }
        }

        if (!useAa && aaRequested > 0f && BuildConfig.DEBUG) {
            logDebugWarning("ribbon AA fringe skipped: mesh budget")
        }
        val joinStart = if (isClosed) 0 else 1
        val joinEndExclusive = if (isClosed) count else count - 1
        if (settings.join == RibbonJoin.ROUND) {
            for (i in joinStart until joinEndExclusive) {
                addRoundJoin(i, useAa, aaRequested)
            }
        } else if (settings.join == RibbonJoin.BEVEL) {
            for (i in joinStart until joinEndExclusive) {
                addBevelJoin(i, useAa)
            }
        }
        for (i in joinStart until joinEndExclusive) {
            if (!miterFallbackRequired[i]) continue
            when (settings.miterFallback) {
                RibbonJoin.ROUND -> addRoundJoin(i, useAa, aaRequested)
                RibbonJoin.BEVEL, RibbonJoin.MITER -> addBevelJoin(i, useAa)
            }
        }
        if (!isClosed && settings.cap == RibbonCap.ROUND) {
            addCap(0, true)
            addCap(count - 1, false)
        }
        if (BuildConfig.DEBUG && decimatedPoints > 0) {
            runCatching { Log.d(TAG, "ribbon decimated=$decimatedPoints points=$count") }
        }
        return writer.toMesh()
    }

    private fun buildDot(settings: RibbonSettings): Mesh {
        writer.reset()
        val x = positions[0]
        val y = positions[1]
        val radius = radii[0]
        val aa = settings.aaWidthPx.coerceAtLeast(0f)
        val center = writer.add(x, y, coverage[0])
        var previousInner = -1
        var previousOuter = -1
        for (i in 0..DOT_SEGMENTS) {
            val angle = i * 2f * PI.toFloat() / DOT_SEGMENTS
            val inner = writer.add(x + cos(angle) * radius, y + sin(angle) * radius, coverage[0])
            val outer = if (aa > 0f) writer.add(
                x + cos(angle) * (radius + aa),
                y + sin(angle) * (radius + aa),
                0f,
            ) else -1
            if (previousInner >= 0) {
                writer.triangle(center, previousInner, inner)
                if (aa > 0f) {
                    writer.triangle(previousInner, previousOuter, inner)
                    writer.triangle(inner, previousOuter, outer)
                }
            }
            previousInner = inner
            previousOuter = outer
        }
        return writer.toMesh()
    }

    private fun appendPoint(x: Float, y: Float, radius: Float, alpha: Float, rotation: Float) {
        val offset = count * 2
        positions[offset] = x
        positions[offset + 1] = y
        radii[count] = radius.coerceAtLeast(0.25f)
        coverage[count] = alpha.coerceIn(0f, 1f)
        rotations[count] = rotation
        cumulativeLength[count] = if (count == 0) 0f else cumulativeLength[count - 1] +
            hypot(x - positions[offset - 2], y - positions[offset - 1])
        count++
    }

    private fun updateLastPoint(x: Float, y: Float, radius: Float, alpha: Float, rotation: Float) {
        val index = count - 1
        val offset = index * 2
        positions[offset] = x
        positions[offset + 1] = y
        radii[index] = radius.coerceAtLeast(0.25f)
        coverage[index] = alpha.coerceIn(0f, 1f)
        rotations[index] = rotation
        if (index > 0) {
            val previous = offset - 2
            cumulativeLength[index] = cumulativeLength[index - 1] +
                hypot(x - positions[previous], y - positions[previous + 1])
        }
    }

    private fun tangentAt(index: Int, closed: Boolean) {
        val beforeIndex = if (closed) (index - 1 + count) % count else (index - 1).coerceAtLeast(0)
        val afterIndex = if (closed) (index + 1) % count else (index + 1).coerceAtMost(count - 1)
        val before = beforeIndex * 2
        val after = afterIndex * 2
        val dx = positions[after] - positions[before]
        val dy = positions[after + 1] - positions[before + 1]
        val distance = hypot(dx, dy).coerceAtLeast(POINT_EPSILON)
        tangentX[index] = dx / distance
        tangentY[index] = dy / distance
    }

    private fun miterOffset(index: Int, radius: Float, limit: Float): Pair<Float, Float>? {
        val previous = (index - 1 + count) % count
        val nax = -tangentY[previous]
        val nay = tangentX[previous]
        val nbx = -tangentY[index]
        val nby = tangentX[index]
        val mx = nax + nbx
        val my = nay + nby
        val length = hypot(mx, my)
        if (length < POINT_EPSILON) return null
        val ux = mx / length
        val uy = my / length
        val denominator = ux * nbx + uy * nby
        if (abs(denominator) < MITER_DENOMINATOR_EPSILON) return null
        val miterLength = radius / denominator
        if (!miterLength.isFinite() || abs(miterLength) > radius * limit) return null
        val result = Pair(ux * miterLength, uy * miterLength)
        return result.takeIf { it.first.isFinite() && it.second.isFinite() }
    }

    /** Wedge built from already-added bank vertices: no T-junction at a join. */
    private fun addBevelJoin(index: Int, useAa: Boolean) {
        val previous = (index - 1 + count) % count
        val cross = tangentX[previous] * tangentY[index] - tangentY[previous] * tangentX[index]
        if (abs(cross) < POINT_EPSILON) return
        val outerIsLeft = cross < 0f
        val inner = if (outerIsLeft) rightInner[index] else leftInner[index]
        val outer = if (outerIsLeft) leftInner[index] else rightInner[index]
        // The existing body shares [inner]/[outer]. This triangle only fills
        // the angular sector; it never creates a duplicate bank endpoint.
        if (writer.canFit(1, 3)) {
            val point = index * 2
            val center = writer.add(positions[point], positions[point + 1], coverage[index])
            writer.triangle(center, inner, outer)
        } else {
            logDecorationSkipped("bevel")
        }
        // AA outer endpoints are likewise shared; their strip was already
        // emitted, so no transparent geometry is placed over the body.
    }

    private fun addRoundJoin(index: Int, useAa: Boolean, aa: Float) {
        val previous = (index - 1 + count) % count
        val cross = tangentX[previous] * tangentY[index] - tangentY[previous] * tangentX[index]
        if (abs(cross) < POINT_EPSILON) return
        val sign = if (cross > 0f) -1f else 1f
        val start = atan2(tangentX[previous] * sign, -tangentY[previous] * sign)
        val end = atan2(tangentX[index] * sign, -tangentY[index] * sign)
        var delta = end - start
        while (delta > PI) delta -= (2 * PI).toFloat()
        while (delta < -PI) delta += (2 * PI).toFloat()
        val steps = ceil(abs(delta) / 0.35f).toInt().coerceIn(1, MAX_JOIN_STEPS)
        // Endpoints are shared bank vertices. Only the centre and interior arc
        // points are new; this is what prevents subpixel cracks at the seam.
        val verticesNeeded = 1 + (steps - 1) + if (useAa) (steps - 1) else 0
        val indicesNeeded = steps * 3 + if (useAa) steps * 6 else 0
        if (!writer.canFit(verticesNeeded, indicesNeeded)) {
            logDecorationSkipped("join")
            return
        }
        val point = index * 2
        val center = writer.add(positions[point], positions[point + 1], coverage[index])
        val outerIsLeft = cross < 0f
        val startInner = if (outerIsLeft) leftInner[index] else rightInner[index]
        val endInner = if (outerIsLeft) leftInner[index] else rightInner[index]
        val startOuter = if (outerIsLeft) leftOuter[index] else rightOuter[index]
        val endOuter = startOuter
        var previousInner = startInner
        var previousOuter = startOuter
        for (step in 0..steps) {
            if (step == 0) continue
            val angle = start + delta * step / steps
            val isLast = step == steps
            val inner = if (isLast) endInner else writer.add(
                positions[point] + cos(angle) * effectiveRadius[index],
                positions[point + 1] + sin(angle) * effectiveRadius[index],
                coverage[index],
            )
            writer.triangle(center, previousInner, inner)
            if (useAa) {
                val outer = if (isLast) endOuter else writer.add(
                    positions[point] + cos(angle) * (effectiveRadius[index] + aa),
                    positions[point + 1] + sin(angle) * (effectiveRadius[index] + aa),
                    0f,
                )
                writer.triangle(previousInner, previousOuter, inner)
                writer.triangle(inner, previousOuter, outer)
                previousOuter = outer
            }
            previousInner = inner
        }
    }

    private fun addCap(index: Int, start: Boolean) {
        val verticesNeeded = CAP_SEGMENTS + 2
        val indicesNeeded = CAP_SEGMENTS * 3
        if (!writer.canFit(verticesNeeded, indicesNeeded)) {
            logDecorationSkipped("cap")
            return
        }
        val point = index * 2
        val base = atan2(tangentY[index], tangentX[index]) + if (start) PI.toFloat() else 0f
        val center = writer.add(positions[point], positions[point + 1], coverage[index])
        var previous = -1
        for (step in 0..CAP_SEGMENTS) {
            val angle = base - PI.toFloat() / 2f + PI.toFloat() * step / CAP_SEGMENTS
            val vertex = writer.add(
                positions[point] + cos(angle) * effectiveRadius[index],
                positions[point + 1] + sin(angle) * effectiveRadius[index],
                coverage[index],
            )
            if (previous >= 0) writer.triangle(center, previous, vertex)
            previous = vertex
        }
    }

    private fun logDecorationSkipped(type: String) {
        if (BuildConfig.DEBUG) logDebugWarning("ribbon $type skipped: writer full")
    }

    private fun logDebugWarning(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun taperScale(distance: Float, total: Float, settings: RibbonSettings): Float {
        val start = if (settings.taperStartPx <= 0f) 1f else (distance / settings.taperStartPx).coerceIn(0f, 1f)
        val end = if (settings.taperEndPx <= 0f) 1f else ((total - distance) / settings.taperEndPx).coerceIn(0f, 1f)
        return min(start, end).coerceIn(settings.minWidthRatio, 1f)
    }

    private class RibbonMeshWriter(
        maxVertices: Int,
        maxIndices: Int,
    ) {
        private val vertices = FloatArray(maxVertices * FLOATS_PER_VERTEX)
        private val indices = IntArray(maxIndices)
        private var vertexCount = 0
        private var indexCount = 0
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY

        fun reset() {
            vertexCount = 0
            indexCount = 0
            minX = Float.POSITIVE_INFINITY
            minY = Float.POSITIVE_INFINITY
            maxX = Float.NEGATIVE_INFINITY
            maxY = Float.NEGATIVE_INFINITY
        }

        fun canFit(vertices: Int, indices: Int): Boolean =
            vertexCount + vertices <= this.vertices.size / FLOATS_PER_VERTEX &&
                indexCount + indices <= this.indices.size

        fun add(x: Float, y: Float, alpha: Float): Int {
            check(canFit(1, 0)) { "RibbonMeshWriter vertex overflow" }
            val offset = vertexCount * FLOATS_PER_VERTEX
            this.vertices[offset] = x
            this.vertices[offset + 1] = y
            this.vertices[offset + 2] = alpha
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            return vertexCount++
        }

        fun triangle(a: Int, b: Int, c: Int) {
            check(canFit(0, 3)) { "RibbonMeshWriter index overflow" }
            indices[indexCount++] = a
            indices[indexCount++] = b
            indices[indexCount++] = c
        }

        fun toMesh(): Mesh = Mesh(
            vertices = vertices.copyOf(vertexCount * FLOATS_PER_VERTEX),
            indices = indices.copyOf(indexCount),
            bounds = floatArrayOf(minX, minY, maxX, maxY),
        )
    }

    private companion object {
        const val MAX_RIBBON_POINTS = 8_192
        const val MAX_RIBBON_VERTICES = 65_536
        const val MAX_RIBBON_INDICES = 196_608
        const val FLOATS_PER_VERTEX = 3
        const val POINT_EPSILON = 0.0001f
        const val MITER_DENOMINATOR_EPSILON = 0.001f
        const val CAP_SEGMENTS = 24
        const val DOT_SEGMENTS = 32
        const val MAX_JOIN_STEPS = 12
        const val TAG = "RibbonMesh"
    }
}
