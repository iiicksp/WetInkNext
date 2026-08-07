package com.wetinknext.engine.brush

import android.util.Log
import com.wetinknext.BuildConfig

/** One debug log per finished stroke; never used from the render hot path. */
object ClosureDebug {
    const val TAG = "RibbonClosure"
    fun publish(distance: Float, threshold: Float, closed: Boolean, samples: Int) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            Log.d(TAG, "dist=%.2f thr=%.2f closed=%s n=%d".format(distance, threshold, closed, samples))
        }
    }
}
