package com.wetinknext.engine.brush

import com.wetinknext.engine.input.InputBatch
import kotlin.math.sqrt

class StampEmitter(initialSettings: BrushSettings) {
    var settings: BrushSettings = initialSettings
        private set
    private var strokeSettings: BrushSettings? = null

    fun updateSettings(newSettings: BrushSettings) {
        // Active stroke reads strokeSettings; this value is for the next begin().
        settings = newSettings.resolved()
    }

    private val stabilizer = Stabilizer()
    private val pressureFilter = PressureFilter()
    private val resolvedDab = ResolvedDab()
    private var active = false
    private var pointerId = -1
    private var hasLast = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 0f
    private var lastTiltX = 0f
    private var lastTiltY = 0f
    private var carriedDistance = 0f
    private var movedDuringStroke = false
    private var pointCount = 0
    private var p0x = 0f; private var p0y = 0f; private var p0p = 0f
    private var p1x = 0f; private var p1y = 0f; private var p1p = 0f
    private var p2x = 0f; private var p2y = 0f; private var p2p = 0f
    private var p3x = 0f; private var p3y = 0f; private var p3p = 0f

    private fun activeSettings(): BrushSettings {
        check(active) { "Stamp emitter has no active stroke" }
        return strokeSettings ?: error("Missing stroke settings")
    }

    fun reset() {
        active = false
        pointerId = -1
        hasLast = false
        carriedDistance = 0f
        movedDuringStroke = false
        pointCount = 0
        strokeSettings = null
        stabilizer.reset()
        pressureFilter.reset()
    }

    fun begin(
        batch: InputBatch,
        out: DabBuffer,
        strokeSettings: BrushSettings = settings,
    ) {
        reset()
        val resolvedSettings = strokeSettings.resolved()
        this.strokeSettings = resolvedSettings
        if (batch.isEmpty()) return
        stabilizer.strength = (resolvedSettings.smoothing * .7f + resolvedSettings.streamline * .3f).coerceIn(0f, 1f)
        val s = batch.samples[0]
        active = true
        pointerId = s.pointerId
        stabilizer.process(s.timestampNanos, s.canvasX, s.canvasY)
        lastX = stabilizer.x
        lastY = stabilizer.y
        lastPressure = pressureFilter.filter(s.timestampNanos, s.pressure)
        lastTiltX = s.tiltX
        lastTiltY = s.tiltY
        hasLast = true
        p0x = lastX; p0y = lastY; p0p = lastPressure
        p1x = lastX; p1y = lastY; p1p = lastPressure
        p2x = lastX; p2y = lastY; p2p = lastPressure
        p3x = lastX; p3y = lastY; p3p = lastPressure
        pointCount = 1
    }

    fun append(batch: InputBatch, out: DabBuffer) {
        if (!active) return
        val settings = activeSettings()
        for (i in 0 until batch.sampleCount) {
            val s = batch.samples[i]
            if (s.pointerId == pointerId) {
                stabilizer.process(s.timestampNanos, s.canvasX, s.canvasY)
                val pressure = pressureFilter.filter(s.timestampNanos, s.pressure)
                if (settings.smoothing <= .0001f) {
                    addPoint(stabilizer.x, stabilizer.y, pressure, s.tiltX, s.tiltY, out)
                } else {
                    appendSmoothedPoint(stabilizer.x, stabilizer.y, pressure, out)
                }
            }
        }
    }

    fun finish(out: DabBuffer, cancel: Boolean) {
        if (!active) return
        val settings = activeSettings()
        if (cancel) {
            reset()
            return
        }
        if (settings.smoothing > .0001f && pointCount >= 3) {
            flushSmoothedTail(out)
        }
        if (!movedDuringStroke || carriedDistance > .001f) {
            addDab(
                x = lastX,
                y = lastY,
                pressure = lastPressure,
                tiltX = lastTiltX,
                tiltY = lastTiltY,
                out = out,
            )
        }
        reset()
    }

    private fun appendSmoothedPoint(x: Float, y: Float, pressure: Float, out: DabBuffer) {
        p0x = p1x; p0y = p1y; p0p = p1p
        p1x = p2x; p1y = p2y; p1p = p2p
        p2x = p3x; p2y = p3y; p2p = p3p
        p3x = x; p3y = y; p3p = pressure
        pointCount = (pointCount + 1).coerceAtMost(4)

        when (pointCount) {
            2 -> addPoint(x, y, pressure, lastTiltX, lastTiltY, out)
            4 -> interpolateCatmullRom(out)
        }
    }

    /** Processes the last delayed Catmull-Rom segment with a duplicated endpoint. */
    private fun flushSmoothedTail(out: DabBuffer) {
        p0x = p1x; p0y = p1y; p0p = p1p
        p1x = p2x; p1y = p2y; p1p = p2p
        p2x = p3x; p2y = p3y; p2p = p3p
        p3x = p2x; p3y = p2y; p3p = p2p
        pointCount = 4
        interpolateCatmullRom(out)
    }

    private fun interpolateCatmullRom(out: DabBuffer) {
        val settings = activeSettings()
        val dx = p2x - p1x
        val dy = p2y - p1y
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < .0001f) return

        val steps = (distance / maxOf(1f, settings.baseRadiusPx * .25f))
            .toInt()
            .coerceIn(2, 64)

        for (index in 1..steps) {
            val t = index.toFloat() / steps
            val t2 = t * t
            val t3 = t2 * t
            val x = .5f * (
                2f * p1x + (-p0x + p2x) * t +
                    (2f * p0x - 5f * p1x + 4f * p2x - p3x) * t2 +
                    (-p0x + 3f * p1x - 3f * p2x + p3x) * t3
                )
            val y = .5f * (
                2f * p1y + (-p0y + p2y) * t +
                    (2f * p0y - 5f * p1y + 4f * p2y - p3y) * t2 +
                    (-p0y + 3f * p1y - 3f * p2y + p3y) * t3
                )
            val pressure = p1p + (p2p - p1p) * t
            addPoint(x, y, pressure, lastTiltX, lastTiltY, out)
        }
    }

    private fun addPoint(x: Float, y: Float, p: Float, tx: Float, ty: Float, out: DabBuffer) {
        val settings = activeSettings()
        val dx = x - lastX
        val dy = y - lastY
        val distance = sqrt(dx * dx + dy * dy)
        if (distance <= .0001f) return
        if (distance > 1.5f) movedDuringStroke = true

        var travelled = 0f
        while (travelled < distance) {
            val tProbe = (travelled / distance).coerceIn(0f, 1f)
            val probePressure = lastPressure + (p - lastPressure) * tProbe
            val probeTiltX = lastTiltX + (tx - lastTiltX) * tProbe
            val probeTiltY = lastTiltY + (ty - lastTiltY) * tProbe

            BrushDynamics.resolve(
                settings = settings,
                pressure = probePressure,
                tiltX = probeTiltX,
                tiltY = probeTiltY,
                out = resolvedDab,
            )

            val step = spacingForRadius(resolvedDab.radius)
            // Radius can shrink with pressure, leaving more carried distance
            // than the new step. Emit at the current point and reset it.
            val needed = (step - carriedDistance).coerceAtLeast(0f)

            if (travelled + needed > distance) {
                carriedDistance += distance - travelled
                break
            }

            travelled += needed
            val t = (travelled / distance).coerceIn(0f, 1f)
            addDab(
                x = lastX + dx * t,
                y = lastY + dy * t,
                pressure = lastPressure + (p - lastPressure) * t,
                tiltX = lastTiltX + (tx - lastTiltX) * t,
                tiltY = lastTiltY + (ty - lastTiltY) * t,
                out = out,
            )
            carriedDistance = 0f
        }
        lastX = x
        lastY = y
        lastPressure = p
        lastTiltX = tx
        lastTiltY = ty
    }

    private fun spacingForRadius(radius: Float): Float {
        val settings = activeSettings()
        val unit = if (settings.spacingUsesDiameter) radius * 2f else radius
        return (unit * settings.spacing).coerceIn(.25f, 256f)
    }

    private fun addDab(
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        out: DabBuffer,
    ) {
        val settings = activeSettings()
        BrushDynamics.resolve(
            settings = settings,
            pressure = pressure,
            tiltX = tiltX,
            tiltY = tiltY,
            out = resolvedDab,
        )
        out.add(
            x = x,
            y = y,
            radius = resolvedDab.radius,
            rotation = 0f,
            coverage = resolvedDab.coverage,
            flow = settings.flow.coerceIn(0f, 1f),
        )
    }
}
