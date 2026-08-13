package com.wetinknext.engine.brush

import kotlinx.serialization.Serializable

@Serializable
enum class BrushRenderMode { STAMP, RIBBON, WET }

@Serializable
enum class BlendPolicy { NORMAL_BUILDUP, NON_BUILDUP }

@Serializable
enum class DabFalloff {
    /** Near-binary edge, only AA fringe softness. Pixel-art and hard stamps. */
    HARD,
    /** Cubic smootherstep rolloff. Pencils, charcoal, chalk. */
    SOFT,
    /** Gaussian bell curve. Soft round brushes, blending. */
    GAUSSIAN,
    /** Inverted cubic buildup toward center. Spray / airbrush. */
    AIRBRUSH,
    /** Flat plateau at full coverage until ~85 % radius, then sharp cutoff. Markers, highlighters. */
    FLAT_MARKER,
}

@Serializable
enum class RibbonCap { ROUND, BUTT }

@Serializable
enum class RibbonJoin { MITER, ROUND, BEVEL }

@Serializable
data class RibbonSettings(
    val minWidthRatio: Float = 0.06f,
    val taperStartPx: Float = 8f,
    val taperEndPx: Float = 14f,
    val miterLimit: Float = 3f,
    /** Join used when a miter is parallel, non-finite, or exceeds [miterLimit]. */
    val miterFallback: RibbonJoin = RibbonJoin.BEVEL,
    val aaWidthPx: Float = 1f,
    val cap: RibbonCap = RibbonCap.ROUND,
    val join: RibbonJoin = RibbonJoin.ROUND,
    val minPointDistancePx: Float = 0.5f,
    val autoCloseLoop: Boolean = true,
)

@Serializable
data class WetSettings(val wetness: Float = 0f, val spread: Float = 0f, val bleed: Float = 0f)

@Serializable
data class BrushSettings(
    val name: String = "Debug Stamp",
    val category: String = "Debug",
    val renderMode: BrushRenderMode = BrushRenderMode.STAMP,
    /**
     * Internal brush radius in document pixels.
     * UI displays diameter = baseRadiusPx * 2.
     */
    val baseRadiusPx: Float = 14f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val spacing: Float = 0.16f,
    val spacingUsesDiameter: Boolean = true,
    val hardness: Float = 1f,
    val falloff: DabFalloff = DabFalloff.SOFT,
    val colorArgb: Long = 0xFF000000L,
    val smoothing: Float = 0f,
    val streamline: Float = 0f,
    val antiAliasLevel: Int = 2,
    val useTempStrokeBuffer: Boolean = false,
    val pressureToSize: Boolean = true,
    val pressureToOpacity: Boolean = true,
    val pressureGamma: Float = 1f,
    val minSizeRatio: Float = 0.05f,
    val emissionUsesTime: Boolean = false,
    val emissionRateHz: Float = 60f,
    val velocityToSize: Float = 0f,
    val velocityToOpacity: Float = 0f,
    val tiltToSize: Float = 0f,
    val tiltToOpacity: Float = 0f,
    val tiltToRotation: Float = 0f,
    val rotationJitter: Float = 0f,
    val sizeJitter: Float = 0f,
    val opacityJitter: Float = 0f,
    val scatter: Float = 0f,
    val shapeAssetPath: String? = null,
    val grainAssetPath: String? = null,
    val grainScale: Float = 1f,
    val grainCanvasLocked: Boolean = true,
    val rgbToAlpha: Boolean = false,
    val textureContrast: Float = 1f,
    val textureDepth: Float = 1f,
    val pixelPen: Boolean = false,
    val squareStroke: Boolean = false,
    val noAntialias: Boolean = false,
    val blendPolicy: BlendPolicy = BlendPolicy.NORMAL_BUILDUP,
    val ribbon: RibbonSettings = RibbonSettings(),
    val wet: WetSettings = WetSettings(),
) {
    /** Maps the persisted brush policy to the explicit runtime stroke mode. */
    val strokeRenderMode: StrokeRenderMode
        get() = when (blendPolicy) {
            BlendPolicy.NORMAL_BUILDUP -> StrokeRenderMode.NORMAL_BUILDUP
            BlendPolicy.NON_BUILDUP -> StrokeRenderMode.NON_BUILDUP
        }

    /** Current engine-supported runtime stroke mode for this brush. */
    val effectiveStrokeRenderMode: StrokeRenderMode
        get() = strokeRenderMode

    /** Returns a settings copy safe for every engine path. */
    fun resolved(): BrushSettings {
        val safeRadius = baseRadiusPx.coerceIn(0.25f, 4096f)
        val safeOpacity = opacity.coerceIn(0f, 1f)
        val safeFlow = flow.coerceIn(0f, 1f)
        val safeSpacing = spacing.coerceIn(0.001f, 4f)
        val safeGamma = pressureGamma.coerceIn(0.05f, 8f)
        val safeMinSize = minSizeRatio.coerceIn(0.01f, 1f)

        val safeVelocityToSize = velocityToSize.coerceIn(0f, 1f)
        val safeVelocityToOpacity = velocityToOpacity.coerceIn(0f, 1f)
        val safeTiltToSize = tiltToSize.coerceIn(0f, 1f)
        val safeTiltToOpacity = tiltToOpacity.coerceIn(0f, 1f)
        val safeTiltToRotation = tiltToRotation.coerceIn(0f, 1f)
        val safeSizeJitter = sizeJitter.coerceIn(0f, 1f)
        val safeOpacityJitter = opacityJitter.coerceIn(0f, 1f)
        val safeRotationJitter = rotationJitter.coerceIn(0f, 1f)
        val safeScatter = scatter.coerceAtLeast(0f)

        val safeGrainScale = grainScale.coerceIn(0.0001f, 256f)
        val safeTextureDepth = textureDepth.coerceIn(0f, 1f)
        val safeTextureContrast = textureContrast.coerceIn(0f, 4f)
        val safeAa = antiAliasLevel.coerceIn(0, 3)

        val safeRibbon = ribbon.copy(
            minWidthRatio = ribbon.minWidthRatio.coerceIn(0.01f, 1f),
            taperStartPx = ribbon.taperStartPx.coerceAtLeast(0f),
            taperEndPx = ribbon.taperEndPx.coerceAtLeast(0f),
            miterLimit = ribbon.miterLimit.coerceIn(1f, 32f),
            aaWidthPx = ribbon.aaWidthPx.coerceIn(0f, 16f),
            minPointDistancePx = ribbon.minPointDistancePx.coerceIn(0.05f, 32f),
        )
        val safeWet = wet.copy(
            wetness = wet.wetness.coerceIn(0f, 1f),
            spread = wet.spread.coerceIn(0f, 1f),
            bleed = wet.bleed.coerceIn(0f, 1f),
        )

        return copy(
            baseRadiusPx = safeRadius,
            opacity = safeOpacity,
            flow = safeFlow,
            spacing = safeSpacing,
            pressureGamma = safeGamma,
            minSizeRatio = safeMinSize,
            velocityToSize = safeVelocityToSize,
            velocityToOpacity = safeVelocityToOpacity,
            tiltToSize = safeTiltToSize,
            tiltToOpacity = safeTiltToOpacity,
            tiltToRotation = safeTiltToRotation,
            sizeJitter = safeSizeJitter,
            opacityJitter = safeOpacityJitter,
            rotationJitter = safeRotationJitter,
            scatter = safeScatter,
            grainScale = safeGrainScale,
            textureDepth = safeTextureDepth,
            textureContrast = safeTextureContrast,
            antiAliasLevel = safeAa,
            ribbon = safeRibbon,
            wet = safeWet,
        )
    }
}
