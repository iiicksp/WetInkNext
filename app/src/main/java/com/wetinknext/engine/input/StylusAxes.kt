package com.wetinknext.engine.input

import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.sin

class StylusAxes {

    fun readTilt(
        event: MotionEvent,
        pointerIndex: Int,
        historicalIndex: Int,
        tool: PointerTool,
        out: FloatArray,
    ) {
        if (tool == PointerTool.FINGER ||
            tool == PointerTool.UNKNOWN
        ) {
            out[0] = 0f
            out[1] = 0f
            return
        }

        val tilt = if (historicalIndex >= 0) {
            event.getHistoricalAxisValue(
                MotionEvent.AXIS_TILT,
                pointerIndex,
                historicalIndex,
            )
        } else {
            event.getAxisValue(
                MotionEvent.AXIS_TILT,
                pointerIndex,
            )
        }

        val orientation = if (historicalIndex >= 0) {
            event.getHistoricalAxisValue(
                MotionEvent.AXIS_ORIENTATION,
                pointerIndex,
                historicalIndex,
            )
        } else {
            event.getAxisValue(
                MotionEvent.AXIS_ORIENTATION,
                pointerIndex,
            )
        }

        val normalizedTilt =
            (tilt / (Math.PI.toFloat() * 0.5f))
                .coerceIn(0f, 1f)

        out[0] =
            (cos(orientation) * normalizedTilt)
                .coerceIn(-1f, 1f)

        out[1] =
            (sin(orientation) * normalizedTilt)
                .coerceIn(-1f, 1f)
    }

    fun readOrientation(
        event: MotionEvent,
        pointerIndex: Int,
        historicalIndex: Int,
        tool: PointerTool,
    ): Float {
        if (tool == PointerTool.FINGER || tool == PointerTool.UNKNOWN) return 0f
        return if (historicalIndex >= 0) {
            event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, historicalIndex)
        } else {
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
        }
    }
}
