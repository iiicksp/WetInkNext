package com.wetinknext.engine.brush

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CPU mirror of the `wetSimFragment` shader so the fluid math is unit-testable
 * without a GPU. The GPU path is the authoritative runtime; this reference is
 * kept in lock-step for verification and future CPU-offload experiments.
 *
 * Field layout: parallel FloatArrays of length `width * height`, row-major.
 * `pigR/G/B` hold premultiplied pigment dye (0..1), `water` holds 0..1.
 * Neighbour sampling clamps to the field edge, mirroring GL CLAMP_TO_EDGE.
 */
object WetSimMath {

    class Field(
        val width: Int,
        val height: Int,
        val pigR: FloatArray,
        val pigG: FloatArray,
        val pigB: FloatArray,
        val water: FloatArray,
    ) {
        init {
            require(width > 0 && height > 0)
            val n = width * height
            require(pigR.size == n && pigG.size == n && pigB.size == n && water.size == n)
        }

        val size: Int get() = width * height
    }

    private const val MIN_DT = 1f / 240f
    private const val MAX_DT = 1f / 10f
    private const val MAX_MOTION_UV = 8f
    /** Diffusion rate in 1/s; keeps the wash speed independent of deltaSeconds. */
    private const val DIFFUSION_RATE_HZ = 2f

    /**
     * One advection-diffusion-coagulation-evaporation step, applied in place.
     * Mirror semantics of [WetSimulationRenderer.step]: `motionUv*` are in
     * document UV per second and are scaled by deltaSeconds and advection.
     */
    fun step(
        field: Field,
        wet: WetSettings,
        deltaSeconds: Float,
        motionUvPerSecondX: Float = 0f,
        motionUvPerSecondY: Float = 0f,
    ) {
        require(deltaSeconds > 0f)
        val w = field.width
        val n = field.size

        val spread = wet.spread.coerceIn(0f, 1f)
        val wetness = wet.wetness.coerceIn(0f, 1f)
        val bleed = wet.bleed.coerceIn(0f, 1f)
        val advection = wet.advection.coerceIn(0f, 1f)
        val coagulation = wet.coagulation.coerceIn(0f, 1f)
        val evaporation = wet.evaporation.coerceIn(0f, 1f)
        val dt = deltaSeconds.coerceIn(MIN_DT, MAX_DT)

        // Pixel-space advection offset (UV shift * field width/height).
        val shiftXPx = motionUvPerSecondX.coerceIn(-MAX_MOTION_UV, MAX_MOTION_UV) * dt * advection * w
        val shiftYPx = motionUvPerSecondY.coerceIn(-MAX_MOTION_UV, MAX_MOTION_UV) * dt * advection * field.height

        val outR = FloatArray(n)
        val outG = FloatArray(n)
        val outB = FloatArray(n)
        val outW = FloatArray(n)

        for (y in 0 until field.height) {
            for (x in 0 until w) {
                val i = y * w + x

                // ---- advect: sample the shifted position (CLAMP_TO_EDGE) ----
                val sIdx =
                    ((anchorY(y - shiftYPx, field.height) * w) + anchorX(x - shiftXPx, w))

                val wetR = field.pigR[sIdx]
                val wetG = field.pigG[sIdx]
                val wetB = field.pigB[sIdx]
                val wetW = field.water[sIdx]

                // ---- 8-neighbour gather ----
                val eI = y * w + cx(x + 1, w)
                val wI = y * w + cx(x - 1, w)
                val nI = cy(y + 1, field.height) * w + x
                val sI = cy(y - 1, field.height) * w + x

                val nwI = cy(y + 1, field.height) * w + cx(x - 1, w)
                val neI = cy(y + 1, field.height) * w + cx(x + 1, w)
                val swI = cy(y - 1, field.height) * w + cx(x - 1, w)
                val seI = cy(y - 1, field.height) * w + cx(x + 1, w)

                val eW = field.water[eI]; val wW = field.water[wI]
                val nW = field.water[nI]; val sW = field.water[sI]
                val neW = field.water[neI]; val nwW = field.water[nwI]
                val seW = field.water[seI]; val swW = field.water[swI]

                val eR = field.pigR[eI]; val wR = field.pigR[wI]
                val nR = field.pigR[nI]; val sR = field.pigR[sI]
                val neR = field.pigR[neI]; val nwR = field.pigR[nwI]
                val seR = field.pigR[seI]; val swR = field.pigR[swI]

                val gR = field.pigG[eI]; val lG = field.pigG[wI]
                val uG = field.pigG[nI]; val dG = field.pigG[sI]
                val negR = field.pigG[neI]; val nwgR = field.pigG[nwI]
                val segR = field.pigG[seI]; val swgR = field.pigG[swI]

                val bR = field.pigB[eI]; val rbB = field.pigB[wI]
                val nbB = field.pigB[nI]; val sbB = field.pigB[sI]
                val nebB = field.pigB[neI]; val nwbB = field.pigB[nwI]
                val sebB = field.pigB[seI]; val swbB = field.pigB[swI]

                val wMean = (wW + eW + nW + sW + 0.5f * (nwW + neW + swW + seW)) / 6f
                val pMeanR = (wR + eR + nR + sR + 0.5f * (nwR + neR + swR + seR)) / 6f
                val pMeanG = (lG + gR + uG + dG + 0.5f * (nwgR + negR + swgR + segR)) / 6f
                val pMeanB = (rbB + bR + nbB + sbB + 0.5f * (nwbB + nebB + swbB + sebB)) / 6f

                // ---- diffusion: wetter regions bleed further; rate is per-second,
                // blended frame-rate-independently via 1-exp(-k*dt) ----
                val rateHz = spread * (0.35f + 0.65f * wetW) * (0.5f + 0.5f * wetness) * DIFFUSION_RATE_HZ
                val waterRate = 1f - kotlin.math.exp(-rateHz * dt)
                val water = wetW + (wMean - wetW) * waterRate

                val pigBlend = waterRate * (0.25f + 0.75f * bleed) // mix(0.25, 1.0, bleed)
                val pigR2 = wetR + (pMeanR - wetR) * pigBlend
                val pigG2 = wetG + (pMeanG - wetG) * pigBlend
                val pigB2 = wetB + (pMeanB - wetB) * pigBlend

                // ---- coagulation: pigment piles where water thins ----
                val gx = eW - wW
                val gy = nW - sW
                val grad = min(1f, sqrt(gx * gx + gy * gy))
                val rim = coagulation * grad
                val pigR3 = pigR2 * (1f + 0.6f * rim * rim)
                val pigG3 = pigG2 * (1f + 0.6f * rim * rim)
                val pigB3 = pigB2 * (1f + 0.6f * rim * rim)

                // ---- evaporation ----
                outR[i] = pigR3.coerceIn(0f, 1f)
                outG[i] = pigG3.coerceIn(0f, 1f)
                outB[i] = pigB3.coerceIn(0f, 1f)
                outW[i] = max(0f, water - evaporation * dt)
            }
        }

        outR.copyInto(field.pigR)
        outG.copyInto(field.pigG)
        outB.copyInto(field.pigB)
        outW.copyInto(field.water)
    }

/** Integer clamp-to-edge for neighbour offsets (no implicit Float conversion). */
    private fun cx(x: Int, width: Int): Int = x.coerceIn(0, width - 1)

    /** Integer clamp-to-edge for neighbour offsets (no implicit Float conversion). */
    private fun cy(y: Int, height: Int): Int = y.coerceIn(0, height - 1)

    private fun anchorX(x: Float, width: Int): Int =
        (x.toInt()).coerceIn(0, width - 1)

    private fun anchorY(y: Float, height: Int): Int =
        (y.toInt()).coerceIn(0, height - 1)
}