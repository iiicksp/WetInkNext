package com.wetinknext.engine.brush

import com.wetinknext.engine.input.InputBatch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
    private var maxHalfWidth = 0f

    /** Штрих не сдвинулся дальше собственного радиуса: рисуем диск, а не ленту. */
    val isDot: Boolean get() = samples.isNotEmpty() && length <= maxHalfWidth * DOT_SPAN_RATIO

    fun updateSettings(value: BrushSettings) { settings = value }
    fun reset() { active = false; pointerId = -1; lastX = 0f; lastY = 0f; length = 0f; closedLoop = false; lastClosureDistance = -1f; lastClosureThreshold = -1f; firstX = 0f; firstY = 0f; firstHalfWidth = 0f; maxHalfWidth = 0f; stabilizer.reset(); pressureFilter.reset(); samples.clear(); arcs.clear() }
    fun begin(batch: InputBatch) {
        reset(); if (batch.isEmpty()) return
        stabilizer.strength = (settings.smoothing * .7f + settings.streamline * .3f).coerceIn(0f, 1f)
        val s = batch.samples[0]; active = true; pointerId = s.pointerId
        stabilizer.process(s.timestampNanos, s.canvasX, s.canvasY)
        lastX = stabilizer.x; lastY = stabilizer.y; firstHalfWidth = width(pressureFilter.filter(s.timestampNanos, s.pressure)); maxHalfWidth = firstHalfWidth; firstX = lastX; firstY = lastY; samples += RibbonSample(lastX, lastY, firstHalfWidth); arcs += 0f
    }
    fun append(batch: InputBatch) { if (!active) return; for (i in 0 until batch.sampleCount) { val s = batch.samples[i]; if (s.pointerId == pointerId) { stabilizer.process(s.timestampNanos, s.canvasX, s.canvasY); add(stabilizer.x, stabilizer.y, pressureFilter.filter(s.timestampNanos, s.pressure)) } } }
    fun finish(cancel: Boolean) {
        if (!active) return
        closedLoop = false
        lastClosureDistance = -1f
        lastClosureThreshold = -1f

        val longEnoughForLoop = length >= maxHalfWidth * MIN_LOOP_LENGTH_TO_WIDTH
        if (!cancel && samples.size >= MIN_LOOP_SAMPLES && longEnoughForLoop) {
            val last = samples.last()
            val distance = hypot(last.x - firstX, last.y - firstY)
            val threshold = max(2f * stepPx(), min(MAX_CLOSURE_PX, length * CLOSURE_LENGTH_RATIO))
            lastClosureDistance = distance
            lastClosureThreshold = threshold
            if (distance <= threshold) {
                if (distance <= stepPx() && samples.size > MIN_LOOP_SAMPLES) {
                    val dropped = samples.removeAt(samples.lastIndex)
                    arcs.removeAt(arcs.lastIndex)
                    val previous = samples.last()
                    length -= hypot(dropped.x - previous.x, dropped.y - previous.y)
                }
                closedLoop = samples.size >= 3
            }
            ClosureDebug.publish(distance, threshold, closedLoop, samples.size)
        }
        active = false
    }
    fun samples(): List<RibbonSample> = samples
    fun arcLengths(): FloatArray = arcs.toFloatArray()
    fun totalLength(): Float = length
    private fun add(x: Float, y: Float, pressure: Float) {
        val d = hypot(x - lastX, y - lastY)
        if (d < stepPx()) return
        val w = width(pressure)
        if (w > maxHalfWidth) maxHalfWidth = w
        length += d
        samples += RibbonSample(x, y, w)
        arcs += length
        lastX = x
        lastY = y
    }

    /** На кисти 132 px шаг 1.25 px = направление сегмента считается по шуму сенсора. */
    private fun stepPx(): Float {
        val base = settings.ribbon.minPointDistancePx.coerceIn(.05f, 32f)
        return max(base, settings.baseRadiusPx * STEP_TO_RADIUS)
    }
    private fun width(pressure: Float): Float { val shaped = pressure.coerceIn(0f, 1f).pow(settings.pressureGamma.coerceAtLeast(.01f)); val factor = if (settings.pressureToSize) settings.minSizeRatio + (1f - settings.minSizeRatio) * shaped else 1f; return (settings.baseRadiusPx * factor).coerceAtLeast(.25f) }

    private companion object {
        const val STEP_TO_RADIUS = .12f
        const val DOT_SPAN_RATIO = .75f
        const val MIN_LOOP_SAMPLES = 10
        const val MIN_LOOP_LENGTH_TO_WIDTH = 6f
        const val MAX_CLOSURE_PX = 24f
        const val CLOSURE_LENGTH_RATIO = .15f
    }
}
