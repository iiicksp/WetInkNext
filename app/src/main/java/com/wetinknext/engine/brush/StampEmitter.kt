package com.wetinknext.engine.brush

import com.wetinknext.engine.input.InputBatch
import kotlin.math.pow
import kotlin.math.sqrt

class StampEmitter(initialSettings: BrushSettings) {
    var settings: BrushSettings = initialSettings
        private set

    fun updateSettings(newSettings: BrushSettings) {
        settings = newSettings
    }

    private val stabilizer = Stabilizer()
    private val resolvedDab = ResolvedDab()
    private var active = false
    private var pointerId = -1
    private var hasLast = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 0f
    private var lastTiltX = 0f
    private var lastTiltY = 0f
    private var carried = 0f
    private var spacingPx = 1f

    fun reset() {
        active = false
        pointerId = -1
        hasLast = false
        carried = 0f
        stabilizer.reset()
    }

    fun begin(batch: InputBatch, out: DabBuffer) {
        reset()
        if (batch.isEmpty()) return
        stabilizer.strength = (settings.smoothing * .7f + settings.streamline * .3f).coerceIn(0f, 1f)
        spacingPx = (if (settings.spacingUsesDiameter) settings.baseRadiusPx * 2f * settings.spacing else settings.spacing).coerceIn(.75f, 256f)
        val s = batch.samples[0]
        active = true
        pointerId = s.pointerId
        stabilizer.process(s.timestampNanos, s.canvasX, s.canvasY)
        lastX = stabilizer.x
        lastY = stabilizer.y
        lastPressure = s.pressure
        lastTiltX = s.tiltX
        lastTiltY = s.tiltY
        hasLast = true
        addDab(lastX, lastY, lastPressure, lastTiltX, lastTiltY, out)
    }

    fun append(batch: InputBatch, out: DabBuffer) {
        if (!active) return
        for (i in 0 until batch.sampleCount) {
            val s = batch.samples[i]
            if (s.pointerId == pointerId) {
                stabilizer.process(s.timestampNanos, s.canvasX, s.canvasY)
                addPoint(stabilizer.x, stabilizer.y, s.pressure, s.tiltX, s.tiltY, out)
            }
        }
    }

    fun finish(out: DabBuffer, cancel: Boolean) {
        if (active && !cancel && carried > .001f) addDab(lastX, lastY, lastPressure, lastTiltX, lastTiltY, out)
        reset()
    }

    private fun addPoint(x: Float, y: Float, p: Float, tx: Float, ty: Float, out: DabBuffer) {
        val dx = x - lastX
        val dy = y - lastY
        val dist = sqrt(dx * dx + dy * dy)
        if (!hasLast || dist < 1e-5f) {
            lastX = x
            lastY = y
            lastPressure = p
            lastTiltX = tx
            lastTiltY = ty
            hasLast = true
            return
        }
        var travelled = 0f
        var remaining = dist
        while (carried + remaining >= spacingPx) {
            val step = spacingPx - carried
            travelled += step
            remaining -= step
            val t = travelled / dist
            addDab(
                lastX + dx * t,
                lastY + dy * t,
                lastPressure + (p - lastPressure) * t,
                lastTiltX + (tx - lastTiltX) * t,
                lastTiltY + (ty - lastTiltY) * t,
                out
            )
            carried = 0f
        }
        carried += remaining
        lastX = x
        lastY = y
        lastPressure = p
        lastTiltX = tx
        lastTiltY = ty
    }

    private fun addDab(x: Float, y: Float, p: Float, tx: Float, ty: Float, out: DabBuffer) {
        BrushDynamics.resolve(settings, p, tx, ty, resolvedDab)
        out.add(x, y, resolvedDab.radius, 0f, resolvedDab.opacity)
    }
}
