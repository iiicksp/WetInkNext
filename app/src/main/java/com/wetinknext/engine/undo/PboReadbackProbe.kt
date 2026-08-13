package com.wetinknext.engine.undo

import android.opengl.GLES30
import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget

/**
 * One-shot DEBUG verification for the Android GLES PBO binding.
 *
 * P2 depends on the offset overload of glReadPixels. This probe deliberately
 * reads only one pixel and executes once per GL context; it is not part of the
 * production Undo path and does not add a per-frame stall.
 */
object PboReadbackProbe {
    fun verify(target: RenderTarget) {
        if (!BuildConfig.DEBUG || target.framebufferId == 0) return
        GlCheck.checkOnGlThread()

        val ids = IntArray(1)
        var sync = 0L
        val byteCount = target.bytesPerPixel
        val pixelType = if (target.usesHalfFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        try {
            GLES30.glGenBuffers(1, ids, 0)
            if (ids[0] == 0) {
                Log.w(TAG, "PBO generation failed")
                return
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, byteCount, null, GLES30.GL_STREAM_READ)
            target.bind()
            // Android exposes this GLES30 overload exactly for a bound PBO.
            GLES30.glReadPixels(
                0, 0, 1, 1,
                GLES30.GL_RGBA,
                pixelType,
                0,
            )
            sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            GLES30.glFinish() // One startup-only wait: validates the complete path.
            val mapped = GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER,
                0,
                byteCount,
                GLES30.GL_MAP_READ_BIT,
            )
            check(mapped != null) { "PBO map returned null" }
            check(GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) { "PBO unmap failed" }
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "PBO readback error: 0x${error.toString(16)}" }
            Log.d(TAG, "supported: glReadPixels offset + fence + map")
        } catch (error: Throwable) {
            Log.e(TAG, "unsupported", error)
        } finally {
            if (sync != 0L) GLES30.glDeleteSync(sync)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (ids[0] != 0) GLES30.glDeleteBuffers(1, ids, 0)
        }
    }

    private const val TAG = "PboReadback"
}
