package com.wetinknext.engine.selection

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlCheck
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Canvas-sized selection mask used by the lasso and transform preview. */
class SelectionMask(val width: Int, val height: Int) {
    private val pixels = ByteArray(width * height)
    private var textureId = 0
    private var textureDirty = false
    private var contourStarted = false
    private val contourPoints = ArrayList<FloatArray>(64)

    val selectionBounds = IntArray(4)
    var isEmpty: Boolean = true
        private set

    fun clear() {
        pixels.fill(0)
        textureDirty = true
        isEmpty = true
        contourStarted = false
        contourPoints.clear()
        selectionBounds.fill(0)
    }

    /** Adds a visible, thin live segment and records its centerline for final fill. */
    fun strokeCapsule(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
        if (!contourStarted) {
            contourStarted = true
            contourPoints += floatArrayOf(x0, y0)
        }
        if (contourPoints.isEmpty() || distanceSquared(contourPoints.last(), x1, y1) >= 0.25f) {
            contourPoints += floatArrayOf(x1, y1)
        }

        val steps = max(1, (hypot(x1 - x0, y1 - y0) / 1.5f).toInt())
        for (step in 0..steps) {
            val t = step.toFloat() / steps
            stampCircle(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, radius)
        }
    }

    fun stampCircle(cx: Float, cy: Float, radius: Float) {
        val r = radius.toInt().coerceAtLeast(1)
        val minX = (cx - r).toInt().coerceIn(0, width - 1)
        val maxX = (cx + r).toInt().coerceIn(0, width - 1)
        val minY = (cy - r).toInt().coerceIn(0, height - 1)
        val maxY = (cy + r).toInt().coerceIn(0, height - 1)
        val r2 = radius * radius
        for (y in minY..maxY) {
            val dy = y - cy
            for (x in minX..maxX) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) mark(x, y)
            }
        }
    }

    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int) {
        val left = min(x0, x1).coerceIn(0, width - 1)
        val right = max(x0, x1).coerceIn(0, width - 1)
        val top = min(y0, y1).coerceIn(0, height - 1)
        val bottom = max(y0, y1).coerceIn(0, height - 1)
        for (y in top..bottom) for (x in left..right) mark(x, y)
    }

    fun fillEllipse(x0: Int, y0: Int, x1: Int, y1: Int) {
        val left = min(x0, x1)
        val right = max(x0, x1)
        val top = min(y0, y1)
        val bottom = max(y0, y1)
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = max(1, (right - left) / 2)
        val ry = max(1, (bottom - top) / 2)
        val rx2 = rx.toFloat() * rx
        val ry2 = ry.toFloat() * ry
        for (y in top.coerceAtLeast(0)..bottom.coerceAtMost(height - 1)) {
            val dy = y - cy
            for (x in left.coerceAtLeast(0)..right.coerceAtMost(width - 1)) {
                val dx = x - cx
                if (dx * dx / rx2 + dy * dy / ry2 <= 1f) mark(x, y)
            }
        }
    }

    /**
     * Closes the recorded freehand centerline and fills its interior with the
     * same even-odd rule as the working legacy implementation. The live
     * capsule pixels are discarded so brush radius cannot distort the boundary.
     */
    fun fillContour() {
        if (contourPoints.size >= 3) {
            pixels.fill(0)
            textureDirty = true
            isEmpty = true
            selectionBounds.fill(0)
            rasterizeEvenOdd(contourPoints)
        }
        contourStarted = false
        contourPoints.clear()
    }

    private fun rasterizeEvenOdd(points: List<FloatArray>) {
        val intersections = FloatArray(points.size)
        for (y in 0 until height) {
            val scanY = y + 0.5f
            var count = 0
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                if ((a[1] <= scanY && b[1] > scanY) || (b[1] <= scanY && a[1] > scanY)) {
                    intersections[count++] = a[0] + (scanY - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
                }
            }
            intersections.sort(0, count)
            var i = 0
            while (i + 1 < count) {
                val start = ceil(intersections[i] - 0.5f).toInt().coerceIn(0, width - 1)
                val end = floor(intersections[i + 1] - 0.5f).toInt().coerceIn(0, width - 1)
                if (start <= end) for (x in start..end) mark(x, y)
                i += 2
            }
        }
    }

    private fun mark(x: Int, y: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val index = y * width + x
        if (pixels[index] == 0.toByte()) {
            pixels[index] = 255.toByte()
            textureDirty = true
            isEmpty = false
            include(x, y)
        }
    }

    private fun include(x: Int, y: Int) {
        if (selectionBounds[2] == 0) {
            selectionBounds[0] = x
            selectionBounds[1] = y
            selectionBounds[2] = x + 1
            selectionBounds[3] = y + 1
            return
        }
        selectionBounds[0] = min(selectionBounds[0], x)
        selectionBounds[1] = min(selectionBounds[1], y)
        selectionBounds[2] = max(selectionBounds[2], x + 1)
        selectionBounds[3] = max(selectionBounds[3], y + 1)
    }

    private fun distanceSquared(point: FloatArray, x: Float, y: Float): Float {
        val dx = point[0] - x
        val dy = point[1] - y
        return dx * dx + dy * dy
    }

    fun contourBounds(points: List<FloatArray>): FloatArray? {
        if (points.isEmpty()) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (p in points) {
            left = min(left, p[0])
            top = min(top, p[1])
            right = max(right, p[0])
            bottom = max(bottom, p[1])
        }
        return floatArrayOf(left, top, right, bottom)
    }

    fun uploadIfDirty(): Boolean {
        if (!textureDirty) return textureId != 0
        ensureTexture()
        if (textureId == 0) return false
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(pixels).order(ByteOrder.nativeOrder()),
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        textureDirty = false
        return true
    }

    fun texture(): Int = textureId

    private fun ensureTexture() {
        if (textureId != 0) return
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        GlCheck.noError("SelectionMask.release")
    }
}