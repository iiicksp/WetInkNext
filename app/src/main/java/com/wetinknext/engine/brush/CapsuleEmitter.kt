package com.wetinknext.engine.brush

import com.wetinknext.engine.input.InputBatch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Преобразует поток InputSample в цепочку капсул.
 *
 * Один сегмент:
 *   A = x0, y0, radius0
 *   B = x1, y1, radius1
 *
 * Капсула с одинаковыми радиусами является обычной capsule.
 * При разном давлении shader строит round-cone.
 *
 * Важно:
 * - не хранит InputSample;
 * - не создаёт List/FloatArray на MOVE;
 * - reusable state принадлежит GL/render pipeline;
 * - pointerId фиксируется на DOWN;
 * - давление применяется к радиусу;
 * - opacity намеренно не применяется здесь, она применяется одним blit-pass.
 */
class CapsuleEmitter(
    private var settings: BrushSettings,
) {
    private val stabilizer = Stabilizer()
    private val pressureFilter = PressureFilter()

    private var active = false
    private var pointerId = -1

    private var hasLastPoint = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastRadius = 0f

    private var emittedSegments = 0

    var hasStroke: Boolean = false
        private set

    var totalLength: Float = 0f
        private set

    var minX: Float = 0f
        private set

    var minY: Float = 0f
        private set

    var maxX: Float = 0f
        private set

    var maxY: Float = 0f
        private set

    var hasBounds: Boolean = false
        private set

    fun updateSettings(value: BrushSettings) {
        settings = value
    }

    /**
     * Полный сброс текущего stroke.
     * overflowCount рендера здесь не сбрасывается.
     */
    fun reset() {
        active = false
        pointerId = -1

        hasLastPoint = false
        lastX = 0f
        lastY = 0f
        lastRadius = 0f

        emittedSegments = 0
        hasStroke = false
        totalLength = 0f

        minX = 0f
        minY = 0f
        maxX = 0f
        maxY = 0f
        hasBounds = false

        stabilizer.reset()
        pressureFilter.reset()
    }

    /**
     * Начало stroke.
     *
     * Первый sample добавляется как вырожденная капсула.
     * В fragment shader она превращается в чистый диск.
     */
    fun begin(
        batch: InputBatch,
        out: CapsuleStrokeRenderer,
    ) {
        reset()
        out.beginStroke()

        if (batch.sampleCount <= 0) return

        val sample = batch.samples[0]

        active = true
        pointerId = sample.pointerId

        stabilizer.strength = (
            settings.smoothing * SMOOTHING_WEIGHT +
                settings.streamline * STREAMLINE_WEIGHT
            ).coerceIn(0f, 1f)

        stabilizer.process(
            timestampNanos = sample.timestampNanos,
            rawX = sample.canvasX,
            rawY = sample.canvasY,
        )

        val x = stabilizer.x
        val y = stabilizer.y
        val radius = radiusForPressure(sample.pressure)

        lastX = x
        lastY = y
        lastRadius = radius
        hasLastPoint = true
        hasStroke = true

        // Вырожденный сегмент: круглый dab/tap.
        out.addSegment(
            x0 = x,
            y0 = y,
            radius0 = radius,
            x1 = x,
            y1 = y,
            radius1 = radius,
        )
        emittedSegments++

        includeBounds(x, y, radius)
    }

    /**
     * Обрабатывает MOVE batch.
     *
     * Historical samples уже должны находиться внутри batch
     * раньше текущего sample, это обеспечивает StrokeInputCapturer.
     */
    fun append(
        batch: InputBatch,
        out: CapsuleStrokeRenderer,
    ) {
        if (!active || batch.sampleCount <= 0) return

        for (index in 0 until batch.sampleCount) {
            val sample = batch.samples[index]

            if (sample.pointerId != pointerId) {
                continue
            }

            stabilizer.process(
                timestampNanos = sample.timestampNanos,
                rawX = sample.canvasX,
                rawY = sample.canvasY,
            )

            val x = stabilizer.x
            val y = stabilizer.y
            val radius = radiusForPressure(sample.pressure)

            emitPoint(
                x = x,
                y = y,
                radius = radius,
                out = out,
            )
        }
    }

    /**
     * Завершает stroke.
     *
     * Последний текущий sample должен быть передан через append()
     * до вызова finish().
     */
    fun finish(
        out: CapsuleStrokeRenderer,
        cancel: Boolean,
    ) {
        if (!active) return

        if (cancel) {
            reset()
            out.clearStrokeData()
            return
        }

        active = false
        pointerId = -1
    }

    private fun emitPoint(
        x: Float,
        y: Float,
        radius: Float,
        out: CapsuleStrokeRenderer,
    ) {
        if (!hasLastPoint) {
            lastX = x
            lastY = y
            lastRadius = radius
            hasLastPoint = true
            hasStroke = true

            out.addSegment(
                x0 = x,
                y0 = y,
                radius0 = radius,
                x1 = x,
                y1 = y,
                radius1 = radius,
            )
            emittedSegments++
            includeBounds(x, y, radius)
            return
        }

        val dx = x - lastX
        val dy = y - lastY
        val distance = hypot(dx, dy)

        /*
         * Не создаём пачку почти нулевых капсул от повторяющихся
         * historical/current samples. Но последующая капсула всё равно
         * протянется от lastPoint до следующего реального point.
         */
        if (distance < minimumPointDistance()) {
            return
        }

        out.addSegment(
            x0 = lastX,
            y0 = lastY,
            radius0 = lastRadius,
            x1 = x,
            y1 = y,
            radius1 = radius,
        )
        emittedSegments++

        totalLength += distance
        hasStroke = true

        includeBounds(lastX, lastY, lastRadius)
        includeBounds(x, y, radius)

        lastX = x
        lastY = y
        lastRadius = radius
    }

    /**
     * Радиус кисти.
     *
     * baseRadiusPx в текущем коде является радиусом,
     * поэтому UI-диаметр должен конвертироваться в EngineRenderer
     * до попадания сюда.
     */
    private fun radiusForPressure(pressure: Float): Float {
        val normalized = pressure.coerceIn(0f, 1f)

        val shapedPressure =
            if (settings.pressureGamma > 0f) {
                normalized.pow(settings.pressureGamma)
            } else {
                normalized
            }

        val sizeFactor =
            if (settings.pressureToSize) {
                settings.minSizeRatio +
                    (1f - settings.minSizeRatio) * shapedPressure
            } else {
                1f
            }

        return (
            settings.baseRadiusPx * sizeFactor
        ).coerceAtLeast(MIN_RADIUS_PX)
    }

    /**
     * Для капсул не нужен такой плотный шаг, как для stamp-кисти:
     * segment shader сам покрывает весь отрезок между A и B.
     */
    private fun minimumPointDistance(): Float {
        val configured = settings.ribbon.minPointDistancePx
            .coerceIn(MIN_POINT_DISTANCE_MIN, MAX_POINT_DISTANCE)

        val radiusBased = settings.baseRadiusPx * POINT_STEP_RADIUS_RATIO

        return max(
            configured,
            radiusBased.coerceAtMost(MAX_POINT_DISTANCE),
        )
    }

    private fun includeBounds(
        x: Float,
        y: Float,
        radius: Float,
    ) {
        val left = x - radius
        val top = y - radius
        val right = x + radius
        val bottom = y + radius

        if (!hasBounds) {
            minX = left
            minY = top
            maxX = right
            maxY = bottom
            hasBounds = true
            return
        }

        if (left < minX) minX = left
        if (top < minY) minY = top
        if (right > maxX) maxX = right
        if (bottom > maxY) maxY = bottom
    }

    companion object {
        private const val SMOOTHING_WEIGHT = 0.7f
        private const val STREAMLINE_WEIGHT = 0.3f

        private const val MIN_RADIUS_PX = 0.25f

        private const val MIN_POINT_DISTANCE_MIN = 0.05f
        private const val MAX_POINT_DISTANCE = 32f
        private const val POINT_STEP_RADIUS_RATIO = 0.08f
    }
}
