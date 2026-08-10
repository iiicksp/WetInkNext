package com.wetinknext.engine.brush

import android.opengl.GLES30

class BlendController {

    fun begin(policy: BlendPolicy) {
        GLES30.glEnable(GLES30.GL_BLEND)
        // NON_BUILDUP needs a dedicated accumulation mask. Until that pass is
        // introduced, both policies use the correct premultiplied source-over.
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )
    }

    fun end() {
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES30.glDisable(GLES30.GL_BLEND)
    }
}
