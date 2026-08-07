package com.wetinknext.engine.brush

import com.wetinknext.engine.input.InputBatch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

class RibbonEmitter(private var settings: BrushSettings) {
    private var active = false; private var pointerId = -1; private var lastX = 0f; private var lastY = 0f; private var length = 0f
    private val stabilizer = Stabilizer()
    private val pressureFilter = PressureFilter()
    private val samples = ArrayList<RibbonSample>(); private val arcs = ArrayList<Float>()
    val hasStroke get() = samples.isNotEmpty()
    var closedLoop = false
        private set
    var lastClosureDistance = -1f
        private set
    var lastClosureThreshold = -1f
        private set
    private var firstX = 0f; private var firstY = 0f; private var firstHalfWidth = 0f

    fun updateSettings(value: BrushSettings) { settings = value }
    fun reset() { active = false; pointerId = -1; lastX = 0f; lastY = 0f; length = 0f; closedLoop = false; lastClosureDistance = -1f; lastClosureThreshold = -1f; firstX = 0f; firstY = 0f; firstHalfWidth = 0f; stabilizer.reset(); pressureFilter.reset(); samples.clear(); arcs.clear() }
    fun begin(batch: InputBatch) {
        reset(); if (batch.isEmpty()) return
        stabilizer.strength = (settings.smoothing * .7f + settings.streamline * .3f).coerceIn(0f, 1f)
        val s = batch.samples[0]; active = true; pointerId = s.pointerId
        stabilizer.process(s.timestampNanos, s.canvasX, s.canvasY)
        lastX = stabilizer.x; lastY = stabilizer.y; firstHalfWidth = width(pressureFilter.filter(s.timestampNanos, s.pressure)); firstX = lastX; firstY = lastY; samples += RibbonSample(lastX, lastY, firstHalfWidth); arcs += 0f
    }
    fun append(batch: InputBatch) { if (!active) return; for (i in 0 until batch.sampleCount) { val s = batch.samples[i]; if (s.pointerId == pointerId) { stabilizer.process(s.timestampNanos, s.canvasX, s.canvasY); add(stabilizer.x, stabilizer.y, pressureFilter.filter(s.timestampNanos, s.pressure)) } } }
    fun finish(cancel: Boolean) {
        if (!active) return
        if (!cancel && samples.size >= 3) {
            val last = samples.last(); val distance = hypot(last.x - firstX, last.y - firstY)
            val minDistance = settings.ribbon.minPointDistancePx.coerceIn(.05f, 32f)
            val threshold = max(2f * minDistance, firstHalfWidth + last.halfWidth)
            lastClosureDistance = distance
            lastClosureThreshold = threshold
            if (distance <= threshold) {
                if (distance <= minDistance && samples.size > 3) { val dropped = samples.removeAt(samples.lastIndex); arcs.removeAt(arcs.lastIndex); val previous = samples.last(); length -= hypot(dropped.x - previous.x, dropped.y - previous.y) }
                closedLoop = samples.size >= 3
            } else closedLoop = false
            ClosureDebug.publish(distance, threshold, closedLoop, samples.size)
        }
        else closedLoop = false
        active = false
    }
    fun samples(): List<RibbonSample> = samples
    fun arcLengths(): FloatArray = arcs.toFloatArray()
    fun totalLength(): Float = length
    private fun add(x: Float, y: Float, pressure: Float) { val d = hypot(x - lastX, y - lastY); if (d < settings.ribbon.minPointDistancePx.coerceIn(.05f, 32f)) return; length += d; samples += RibbonSample(x, y, width(pressure)); arcs += length; lastX = x; lastY = y }
    private fun width(pressure: Float): Float { val shaped = pressure.coerceIn(0f, 1f).pow(settings.pressureGamma.coerceAtLeast(.01f)); val factor = if (settings.pressureToSize) settings.minSizeRatio + (1f - settings.minSizeRatio) * shaped else 1f; return (settings.baseRadiusPx * factor).coerceAtLeast(.25f) }
}
