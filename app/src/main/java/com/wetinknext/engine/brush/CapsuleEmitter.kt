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
    private var strokeSettings: BrushSettings? = null
    private val stabilizer = Stabilizer()
    private val pressureFilter = PressureFilter()
    private val resolvedDab = ResolvedDab()

    private var active = false
    private var pointerId = -1

    private var hasLastPoint = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastRadius = 0f
    private var lastCoverage = 1f
    // Reserved for a future oriented tip; the round capsule deliberately ignores it.
    private var lastOrientation = 0f
    private var lastTiltX = 0f
    private var lastTiltY = 0f

    // Окно точек для интерполяции P0, P1, P2, P3
    private var p0x = 0f; private var p0y = 0f; private var p0r = 0f
    private var p1x = 0f; private var p1y = 0f; private var p1r = 0f
    private var p2x = 0f; private var p2y = 0f; private var p2r = 0f
    private var p3x = 0f; private var p3y = 0f; private var p3r = 0f
    private var p0c = 1f; private var p1c = 1f
    private var p2c = 1f; private var p3c = 1f
    private var pointsInWindow = 0

    private var emittedSegments = 0

    var hasStroke: Boolean = false
        private set

    var totalLength: Float = 0f
        private set

    /** Physical input distance, retained for stroke diagnostics and future dynamics. */
    private var strokeDistance = 0f

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
        // Active stroke reads strokeSettings; this value is for the next begin().
        settings = value.resolved()
    }

    private fun activeSettings(): BrushSettings {
        check(active) { "Capsule emitter has no active stroke" }
        return strokeSettings ?: error("Missing stroke settings")
    }

    /**
     * Полный сброс текущего stroke.
     * overflowCount рендера здесь не сбрасывается.
     */
    fun reset() {
        active = false
        strokeSettings = null
        pointerId = -1

        hasLastPoint = false
        lastX = 0f
        lastY = 0f
        lastRadius = 0f
        lastCoverage = 1f
        lastOrientation = 0f
        lastTiltX = 0f
        lastTiltY = 0f

        pointsInWindow = 0

        emittedSegments = 0
        hasStroke = false
        totalLength = 0f
        strokeDistance = 0f

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
        strokeSettings: BrushSettings = settings,
    ) {
        reset()
        out.beginStroke()
        val resolvedSettings = strokeSettings.resolved()
        this.strokeSettings = resolvedSettings

        if (batch.sampleCount <= 0) return

        val sample = batch.samples[0]

        active = true
        pointerId = sample.pointerId

        stabilizer.strength = (
            resolvedSettings.smoothing * SMOOTHING_WEIGHT +
                resolvedSettings.streamline * STREAMLINE_WEIGHT
            ).coerceIn(0f, 1f)

        stabilizer.process(
            timestampNanos = sample.timestampNanos,
            rawX = sample.canvasX,
            rawY = sample.canvasY,
        )

        val x = stabilizer.x
        val y = stabilizer.y
        val filteredPressure = pressureFilter.filter(sample.timestampNanos, sample.pressure)
        BrushDynamics.resolve(resolvedSettings, filteredPressure, sample.tiltX, sample.tiltY, resolvedDab)
        val radius = resolvedDab.radius
        val coverage = resolvedDab.coverage

        lastX = x
        lastY = y
        lastRadius = radius
        lastCoverage = coverage
        lastOrientation = sample.orientationRad
        lastTiltX = sample.tiltX
        lastTiltY = sample.tiltY
        out.setStrokeRotation(lastOrientation)
        hasLastPoint = true
        hasStroke = true

        // Инициализация окна
        p0x = x; p0y = y; p0r = radius
        p1x = x; p1y = y; p1r = radius
        p2x = x; p2y = y; p2r = radius
        p0c = coverage; p1c = coverage; p2c = coverage
        pointsInWindow = 1

        // Вырожденный сегмент: круглый dab/tap.
        out.addSegment(
            x0 = x,
            y0 = y,
            radius0 = radius,
            coverage0 = coverage,
            x1 = x,
            y1 = y,
            radius1 = radius,
            coverage1 = coverage,
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
        val settings = activeSettings()

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
            val filteredPressure = pressureFilter.filter(sample.timestampNanos, sample.pressure)
            BrushDynamics.resolve(settings, filteredPressure, sample.tiltX, sample.tiltY, resolvedDab)
            val radius = resolvedDab.radius
            val coverage = resolvedDab.coverage
            lastOrientation = sample.orientationRad
            lastTiltX = sample.tiltX
            lastTiltY = sample.tiltY
            out.setStrokeRotation(lastOrientation)

            emitPoint(
                x = x,
                y = y,
                radius = radius,
                coverage = coverage,
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
        val settings = activeSettings()

        if (cancel) {
            reset()
            out.clearStrokeData()
            return
        }

        // Принудительно завершаем кривую, используя последнюю точку как P3
        if (pointsInWindow >= 2 && settings.smoothing > 1e-4f) {
            interpolate(
                p0x, p0y, p0r,
                p0c,
                p1x, p1y, p1r, p1c,
                p2x, p2y, p2r, p2c,
                p2x, p2y, p2r, p2c,
                out,
            )
        }

        // The end taper is only knowable after UP, when the complete mesh exists.
        // Preview stays untapered; commit redraws every segment with these final radii.
        out.applyTaper { distanceFromStart, meshLength ->
            taperScale(distanceFromStart, meshLength, settings)
        }

        active = false
        pointerId = -1
    }

    private fun emitPoint(
        x: Float,
        y: Float,
        radius: Float,
        coverage: Float,
        out: CapsuleStrokeRenderer,
    ) {
        val settings = activeSettings()
        val dx = x - lastX
        val dy = y - lastY
        val distance = hypot(dx, dy)
        strokeDistance += distance

        if (distance < minimumPointDistance()) {
            return
        }

        if (settings.smoothing <= 1e-4f) {
            // Raw mode: прямые сегменты без задержки на окно
            out.addSegment(lastX, lastY, lastRadius, lastCoverage, x, y, radius, coverage)
            emittedSegments++
            totalLength += distance
            includeBounds(lastX, lastY, lastRadius)
            includeBounds(x, y, radius)
        } else {
            // Interpolated mode: Catmull-Rom через окно
            when (pointsInWindow) {
                1 -> {
                    p2x = x; p2y = y; p2r = radius; p2c = coverage
                    pointsInWindow = 2
                }
                2 -> {
                    p3x = x; p3y = y; p3r = radius; p3c = coverage
                    interpolate(p0x, p0y, p0r, p0c, p1x, p1y, p1r, p1c, p2x, p2y, p2r, p2c, p3x, p3y, p3r, p3c, out)
                    // Сдвиг окна
                    p0x = p1x; p0y = p1y; p0r = p1r; p0c = p1c
                    p1x = p2x; p1y = p2y; p1r = p2r; p1c = p2c
                    p2x = p3x; p2y = p3y; p2r = p3r; p2c = p3c
                }
            }
        }

        hasStroke = true
        lastX = x
        lastY = y
        lastRadius = radius
        lastCoverage = coverage
    }

    private fun taperScale(
        distanceFromStart: Float,
        totalLength: Float,
        settings: BrushSettings,
    ): Float {
        val start = settings.ribbon.taperStartPx
        val end = settings.ribbon.taperEndPx

        val startFactor = if (start <= 0f) {
            1f
        } else {
            (distanceFromStart / start).coerceIn(0f, 1f)
        }

        val distanceToEnd = totalLength - distanceFromStart
        val endFactor = if (end <= 0f) {
            1f
        } else {
            (distanceToEnd / end).coerceIn(0f, 1f)
        }

        return minOf(startFactor, endFactor)
            .coerceIn(settings.ribbon.minWidthRatio, 1f)
    }

    /**
     * Адаптивная интерполяция сегмента P1-P2 с использованием P0 и P3 как контрольных точек.
     */
    private fun interpolate(
        p0x: Float, p0y: Float, p0r: Float, p0c: Float,
        p1x: Float, p1y: Float, p1r: Float, p1c: Float,
        p2x: Float, p2y: Float, p2r: Float, p2c: Float,
        p3x: Float, p3y: Float, p3r: Float, p3c: Float,
        out: CapsuleStrokeRenderer
    ) {
        val dist = hypot(p2x - p1x, p2y - p1y)
        if (dist < 1e-4f) return

        // Вычисляем контрольные точки для кубической кривой Безье (Catmull-Rom -> Bezier)
        val c1x = p1x + (p2x - p0x) / 6f
        val c1y = p1y + (p2y - p0y) / 6f
        val c2x = p2x - (p3x - p1x) / 6f
        val c2y = p2y - (p3y - p1y) / 6f

        // Адаптивное количество шагов: примерно каждые 0.25..0.5 радиуса
        val avgRadius = (p1r + p2r) * 0.5f
        val stepPx = max(1f, avgRadius * INTERPOLATION_STEP_RATIO)
        val steps = max(1, (dist / stepPx).toInt().coerceAtMost(MAX_INTERPOLATION_STEPS))

        var prevX = p1x
        var prevY = p1y
        var prevR = p1r
        var prevCoverage = p1c

        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val mt = 1f - t
            
            // Кубическое Безье для позиции
            val x = mt * mt * mt * p1x + 3f * mt * mt * t * c1x + 3f * mt * t * t * c2x + t * t * t * p2x
            val y = mt * mt * mt * p1y + 3f * mt * mt * t * c1y + 3f * mt * t * t * c2y + t * t * t * p2y
            
            // Линейная интерполяция радиуса
            val r = p1r + (p2r - p1r) * t
            val coverage = p1c + (p2c - p1c) * t

            out.addSegment(prevX, prevY, prevR, prevCoverage, x, y, r, coverage)
            emittedSegments++
            totalLength += hypot(x - prevX, y - prevY)
            includeBounds(x, y, r)

            prevX = x
            prevY = y
            prevR = r
            prevCoverage = coverage
        }
    }

    /**
     * Для капсул не нужен такой плотный шаг, как для stamp-кисти:
     * segment shader сам покрывает весь отрезок между A и B.
     */
    private fun minimumPointDistance(): Float {
        val settings = activeSettings()
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

        private const val INTERPOLATION_STEP_RATIO = 0.4f
        private const val MAX_INTERPOLATION_STEPS = 64
    }
}
