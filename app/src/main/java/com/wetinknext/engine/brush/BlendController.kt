package com.wetinknext.engine.brush

import android.opengl.GLES30

class BlendController {

    fun begin(policy: BlendPolicy) {
        GLES30.glEnable(GLES30.GL_BLEND)

        when (policy) {
            BlendPolicy.NORMAL_BUILDUP -> {
                GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
                GLES30.glBlendFunc(
                    GLES30.GL_ONE,
                    GLES30.GL_ONE_MINUS_SRC_ALPHA,
                )
            }

            BlendPolicy.NON_BUILDUP -> {
                /*
                 * Временная реализация для текущего P7.
                 * Не считать GL_MAX универсальной моделью всех non-buildup кистей.
                 * Для некоторых кистей позже понадобится отдельный shader/pass.
                 */
                GLES30.glBlendEquation(GLES30.GL_MAX)
                GLES30.glBlendFunc(
                    GLES30.GL_ONE,
                    GLES30.GL_ONE,
                )
            }
        }
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
