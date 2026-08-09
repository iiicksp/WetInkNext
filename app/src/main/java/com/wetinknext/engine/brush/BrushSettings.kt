package com.wetinknext.engine.brush

import kotlinx.serialization.Serializable

@Serializable
enum class BrushRenderMode { STAMP, RIBBON, WET }

@Serializable
enum class BlendPolicy { NORMAL_BUILDUP, NON_BUILDUP }

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
    val aaWidthPx: Float = 1f,
    val cap: RibbonCap = RibbonCap.ROUND,
    val join: RibbonJoin = RibbonJoin.ROUND,
    val minPointDistancePx: Float = 0.5f,
)

@Serializable
data class WetSettings(val wetness: Float = 0f, val spread: Float = 0f, val bleed: Float = 0f)

@Serializable
data class BrushSettings(
    val name: String = "Debug Stamp",
    val category: String = "Debug",
    val renderMode: BrushRenderMode = BrushRenderMode.STAMP,
    val baseRadiusPx: Float = 14f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val spacing: Float = 0.16f,
    val spacingUsesDiameter: Boolean = true,
    val hardness: Float = 1f,
    val colorArgb: Long = 0xFF000000L,
    val smoothing: Float = 0f,
    val streamline: Float = 0f,
    val antiAliasLevel: Int = 2,
    val useTempStrokeBuffer: Boolean = false,
    val pressureToSize: Boolean = true,
    val pressureToOpacity: Boolean = true,
    val pressureGamma: Float = 1f,
    val minSizeRatio: Float = 0.05f,
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
    /** Returns a copy with dynamic properties resolved. Placeholder for now. */
    fun resolved(): BrushSettings = this
}
