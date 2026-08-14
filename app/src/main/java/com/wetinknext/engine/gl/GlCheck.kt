package com.wetinknext.engine.gl

import android.opengl.GLES30

/** GL error checks intended for setup boundaries, never the render hot path. */
object GlCheck {
    @Volatile
    private var glThread: Thread? = null

    val hasFramebufferFetch: Boolean by lazy {
        android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_EXTENSIONS)?.contains("GL_EXT_shader_framebuffer_fetch") == true
    }


    fun setGlThread(thread: Thread) {
        glThread = thread
    }

    fun checkOnGlThread() {
        val current = Thread.currentThread()
        val expected = glThread
        check(current === expected) {
            "GL resource accessed outside GLSurfaceView render thread: current=$current, expected=$expected"
        }
    }

    fun noError(label: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "$label: GL error 0x${error.toString(16)}" }
    }

    fun framebufferComplete(label: String) {
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "$label: framebuffer incomplete (0x${status.toString(16)})"
        }
    }
}
