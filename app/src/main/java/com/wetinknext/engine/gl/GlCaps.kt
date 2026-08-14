package com.wetinknext.engine.gl

import android.opengl.GLES30

/** Capabilities queried only after a GLES 3 context is current. */
data class GlCaps(
    val renderer: String,
    val version: String,
    val supportsHalfFloatColorBuffer: Boolean,
) {
    companion object {
        /**
         * Hard cap for new document dimensions. Most mobile GPUs report
         * GL_MAX_TEXTURE_SIZE >= 8192; the cap keeps a user from creating a
         * canvas that cannot be rendered even before the GPU reports the
         * real limit.
         */
        const val MAX_CANVAS_DIMENSION = 8192
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
