package com.wetinknext.engine.gl

import android.opengl.GLES30

/** Capabilities queried only after a GLES 3 context is current. */
data class GlCaps(
    val renderer: String,
    val version: String,
    val supportsHalfFloatColorBuffer: Boolean,
) {
    companion object {
        fun query(): GlCaps {
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            val declared = extensions.contains("EXT_color_buffer_half_float") ||
                extensions.contains("EXT_color_buffer_float")
            // Extension string сам по себе не доказательство: проверяем реальный FBO 4x4.
            val supported = declared && RenderTarget().probeHalfFloatColorBuffer()
            return GlCaps(
                renderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty(),
                version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty(),
                supportsHalfFloatColorBuffer = supported,
            )
        }
    }
}
