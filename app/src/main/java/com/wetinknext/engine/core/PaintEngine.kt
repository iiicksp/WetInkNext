package com.wetinknext.engine.core

import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.engine.gl.RenderTarget

/** Document state that is exclusively owned by the GLSurfaceView render thread. */
class PaintEngine {
    val camera = Camera()
    val canvasTarget = RenderTarget()
    var canvasWidth = 1; private set
    var canvasHeight = 1; private set
    var initialized = false; private set

    fun create(caps: GlCaps, width: Int, height: Int) {
        require(width > 0 && height > 0)
        release()
        canvasWidth = width; canvasHeight = height
        val useHalfFloat = caps.supportsHalfFloatColorBuffer && canvasTarget.probeHalfFloatColorBuffer()
        canvasTarget.create(width, height, useHalfFloat)
        initialized = true
    }

    fun resize(caps: GlCaps, width: Int, height: Int) {
        if (!initialized || canvasWidth != width || canvasHeight != height) create(caps, width, height)
    }

    fun clearCanvas() {
        check(initialized)
        canvasTarget.clear(1f, 1f, 1f, 1f)
    }

    fun release() {
        canvasTarget.release(); initialized = false; canvasWidth = 1; canvasHeight = 1
    }
}
