package com.wetinknext.engine.selection

import kotlin.math.cos
import kotlin.math.sin

/**
 * Interact-holding state of the lasso transform: translate / rotate / scale /
 * flips around a fixed pivot (the selection bounds centre).
 *
 * The shader consumes the inverse of the user transform as a 2x3 affine
 * matrix: for a final canvas pixel it answers "which source pixel does this
 * transformed content come from".
 */
data class TransformState(
    var translateX: Float = 0f,
    var translateY: Float = 0f,
    var rotationRad: Float = 0f,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var flipX: Boolean = false,
    var flipY: Boolean = false,
    var uniform: Boolean = true,
) {
    var pivotX: Float = 0f
    var pivotY: Float = 0f

    fun reset() {
        translateX = 0f; translateY = 0f; rotationRad = 0f
        scaleX = 1f; scaleY = 1f
        flipX = false; flipY = false
    }

    fun flipHorizontal() {
        flipX = !flipX
        translateX = -translateX
    }

    fun flipVertical() {
        flipY = !flipY
        translateY = -translateY
    }

    /** Uniform-mode scale applied through both axes. */
    fun setUniformScale(value: Float) {
        scaleX = value.coerceIn(0.02f, 100f)
        scaleY = scaleX
    }

    /** Builds source(q) = affine * final(p), as [a,b,c,d,e,f] in [out]. */
    fun buildSourceAffine(out: FloatArray): FloatArray {
        require(out.size >= 6)
        val cosAngle = cos(rotationRad)
        val sinAngle = sin(rotationRad)
        val invSX = (if (flipX) -1f else 1f) / scaleX.coerceAtLeast(0.01f)
        val invSY = (if (flipY) -1f else 1f) / scaleY.coerceAtLeast(0.01f)
        val txc = translateX + pivotX
        val tyc = translateY + pivotY
        val a = invSX * cosAngle
        val b = invSX * sinAngle
        out[0] = a
        out[1] = b
        out[2] = pivotX - a * txc - b * tyc
        val d = -invSY * sinAngle
        val e = invSY * cosAngle
        out[3] = d
        out[4] = e
        out[5] = pivotY - d * txc - e * tyc
        return out
    }
}