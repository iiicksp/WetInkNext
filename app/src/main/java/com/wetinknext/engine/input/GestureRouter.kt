package com.wetinknext.engine.input

import android.view.MotionEvent
import com.wetinknext.engine.core.Camera
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Routes view touch input before it reaches the brush pipeline.
 *
 * One pointer draws. A second pointer cancels that stroke immediately and
 * takes ownership of the interaction for canvas navigation. Two- and
 * three-pointer taps trigger undo and redo respectively.
 */
class GestureRouter(
    private val camera: Camera,
    private val drawingInput: StrokeInputCapturer,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun cancelDrawing()
        fun undoGesture()
        fun redoGesture()
        fun resetCamera()
        fun requestRender()
    }

    private var mode = InputMode.IDLE
    private var firstX = 0f
    private var firstY = 0f
    private var secondX = 0f
    private var secondY = 0f
    private var previousDistance = 0f
    private var previousAngle = 0f
    private var gestureStartTime = 0L
    private var gestureMoved = false
    private var navigationPointerCount = 0
    private var drawingStartTime = 0L
    private var drawingStartX = 0f
    private var drawingStartY = 0f
    private var drawingMoved = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            mode = InputMode.DRAWING
            drawingStartTime = event.eventTime
            drawingStartX = event.x
            drawingStartY = event.y
            drawingMoved = false
            drawingInput.onTouchEvent(event)
            true
        }

        MotionEvent.ACTION_POINTER_DOWN -> {
            if (event.pointerCount >= 2) {
                beginNavigation(event)
                true
            } else {
                false
            }
        }

        MotionEvent.ACTION_MOVE -> when (mode) {
            InputMode.DRAWING -> {
                if (hypot(event.x - drawingStartX, event.y - drawingStartY) > TAP_MOVE_THRESHOLD_PX) {
                    drawingMoved = true
                    lastTapTime = 0L
                }
                drawingInput.onTouchEvent(event)
                true
            }

            InputMode.NAVIGATING -> {
                updateNavigation(event)
                true
            }

            else -> false
        }

        MotionEvent.ACTION_POINTER_UP -> {
            if (mode == InputMode.NAVIGATING) {
                handleNavigationPointerUp(event)
                true
            } else {
                drawingInput.onTouchEvent(event)
                true
            }
        }

        MotionEvent.ACTION_UP -> {
            when (mode) {
                InputMode.DRAWING -> {
                    drawingInput.onTouchEvent(event)
                    val duration = event.eventTime - drawingStartTime
                    if (!drawingMoved && duration <= TAP_MAX_DURATION_MS) {
                        handleSingleFingerTap(event)
                    }
                }
                InputMode.NAVIGATING -> finishNavigation(event)
                else -> Unit
            }
            mode = InputMode.IDLE
            true
        }

        MotionEvent.ACTION_CANCEL -> {
            drawingInput.cancelActiveStroke()
            callbacks.cancelDrawing()
            mode = InputMode.CANCELLED
            callbacks.requestRender()
            true
        }

        else -> false
    }

    private fun beginNavigation(event: MotionEvent) {
        lastTapTime = 0L
        if (mode == InputMode.DRAWING) {
            // The queued CANCEL keeps the brush pipeline coherent; the callback
            // clears an already-rendered preview on the GL thread.
            drawingInput.cancelActiveStroke()
            callbacks.cancelDrawing()
        }

        mode = InputMode.NAVIGATING
        navigationPointerCount = event.pointerCount
        gestureStartTime = event.eventTime
        gestureMoved = false
        captureNavigationPointers(event)
        callbacks.requestRender()
    }

    private fun updateNavigation(event: MotionEvent) {
        if (event.pointerCount < 2) return

        val newFirstX = event.getX(0)
        val newFirstY = event.getY(0)
        val newSecondX = event.getX(1)
        val newSecondY = event.getY(1)
        val oldCenterX = (firstX + secondX) * .5f
        val oldCenterY = (firstY + secondY) * .5f
        val newCenterX = (newFirstX + newSecondX) * .5f
        val newCenterY = (newFirstY + newSecondY) * .5f
        val newDistance = distance(newFirstX, newFirstY, newSecondX, newSecondY)
        val zoomFactor = if (previousDistance > MIN_DISTANCE) newDistance / previousDistance else 1f
        val newAngle = angle(newFirstX, newFirstY, newSecondX, newSecondY)
        val rotationDelta = normalizeAngle(newAngle - previousAngle)
        val panX = newCenterX - oldCenterX
        val panY = newCenterY - oldCenterY

        camera.set(
            camera.snapshot().applyGestureDelta(
                panX = panX,
                panY = panY,
                anchorX = newCenterX,
                anchorY = newCenterY,
                zoomFactor = zoomFactor,
                rotationDelta = rotationDelta,
            ),
        )

        if (hypot(panX, panY) > TAP_MOVE_THRESHOLD_PX ||
            kotlin.math.abs(zoomFactor - 1f) > TAP_ZOOM_THRESHOLD ||
            kotlin.math.abs(rotationDelta) > TAP_ROTATION_THRESHOLD_RAD
        ) {
            gestureMoved = true
        }

        firstX = newFirstX
        firstY = newFirstY
        secondX = newSecondX
        secondY = newSecondY
        previousDistance = newDistance
        previousAngle = newAngle
        callbacks.requestRender()
    }

    private fun handleNavigationPointerUp(event: MotionEvent) {
        // Once fewer than two pointers remain, navigation stays active until
        // ACTION_UP so a two/three-finger tap can be recognised reliably.
        if (event.pointerCount - 1 < 2) return
        captureNavigationPointers(event, event.actionIndex)
    }

    private fun finishNavigation(event: MotionEvent) {
        val duration = event.eventTime - gestureStartTime
        if (!gestureMoved && duration <= TAP_MAX_DURATION_MS) {
            when (navigationPointerCount) {
                2 -> callbacks.undoGesture()
                3 -> callbacks.redoGesture()
            }
        }
        callbacks.requestRender()
    }

    private fun handleSingleFingerTap(event: MotionEvent) {
        val now = event.eventTime
        val dx = event.x - lastTapX
        val dy = event.y - lastTapY
        val isDoubleTap =
            now - lastTapTime <= DOUBLE_TAP_TIMEOUT_MS &&
                hypot(dx, dy) <= DOUBLE_TAP_DISTANCE_PX
        if (isDoubleTap) {
            callbacks.resetCamera()
            lastTapTime = 0L
        } else {
            lastTapTime = now
            lastTapX = event.x
            lastTapY = event.y
        }
    }

    private fun captureNavigationPointers(event: MotionEvent, excludedIndex: Int = -1) {
        var firstIndex = -1
        var secondIndex = -1
        for (index in 0 until event.pointerCount) {
            if (index == excludedIndex) continue
            if (firstIndex < 0) firstIndex = index else {
                secondIndex = index
                break
            }
        }
        if (firstIndex < 0 || secondIndex < 0) return
        firstX = event.getX(firstIndex)
        firstY = event.getY(firstIndex)
        secondX = event.getX(secondIndex)
        secondY = event.getY(secondIndex)
        previousDistance = distance(firstX, firstY, secondX, secondY)
        previousAngle = angle(firstX, firstY, secondX, secondY)
    }

    private fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        hypot(x1 - x0, y1 - y0)

    private fun angle(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        atan2(y1 - y0, x1 - x0)

    private fun normalizeAngle(value: Float): Float {
        var result = value
        while (result > Math.PI) result -= (Math.PI * 2.0).toFloat()
        while (result < -Math.PI) result += (Math.PI * 2.0).toFloat()
        return result
    }

    private companion object {
        const val TAP_MAX_DURATION_MS = 220L
        const val MIN_DISTANCE = .001f
        const val TAP_MOVE_THRESHOLD_PX = 4f
        const val TAP_ZOOM_THRESHOLD = .02f
        const val TAP_ROTATION_THRESHOLD_RAD = .035f
        const val DOUBLE_TAP_TIMEOUT_MS = 280L
        const val DOUBLE_TAP_DISTANCE_PX = 48f
    }
}
