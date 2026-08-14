package com.wetinknext.engine.brush

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

data class BrushUiCategory(
    val name: String,
    val icon: ImageVector,
    val brushes: List<BrushPreset>,
)

object BrushLibrary {

    val hb_pencil = BrushPreset(
        id = "hb_pencil",
        settings = BrushSettings(
            name = "HB Pencil",
            category = "Sketching",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 4.0f,
            opacity = 0.8f,
            flow = 0.7f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.039f, 0.089f, 0.145f, 0.205f, 0.268f, 0.333f, 0.401f, 0.470f, 0.542f, 0.615f, 0.689f, 0.765f, 0.842f, 0.921f, 1.000f)),
            hardness = 1.0f,
            falloff = DabFalloff.HARD,
            smoothing = 0.05f,
            streamline = 0.02f,
            minSizeRatio = 0.3f,
            tiltToSize = 0.8f,
            grainAssetPath = "asset:brush/paper_cold_press.png",
            grainCanvasLocked = true,
            grainScale = 4.0f,
            
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 1.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.2f
            
        ),
    )


    val charcoal = BrushPreset(
        id = "charcoal",
        settings = BrushSettings(
            name = "Charcoal Block",
            category = "Sketching",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 15.0f,
            opacity = 0.9f,
            flow = 0.6f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.017f, 0.049f, 0.089f, 0.138f, 0.192f, 0.253f, 0.319f, 0.389f, 0.465f, 0.544f, 0.628f, 0.716f, 0.807f, 0.902f, 1.000f)),
            hardness = 0.5f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.02f,
            streamline = 0.0f,
            minSizeRatio = 0.4f,
            tiltToSize = 1.2f,
            grainAssetPath = "asset:brush/noise_coarse.png",
            grainCanvasLocked = true,
            grainScale = 2.0f,
            shapeAssetPath = "asset:brush/charcoal_stick.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 0.05f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val chalk = BrushPreset(
        id = "chalk",
        settings = BrushSettings(
            name = "Chalk",
            category = "Sketching",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 12.0f,
            opacity = 0.8f,
            flow = 0.7f,
            spacing = 0.08f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.030f, 0.073f, 0.123f, 0.179f, 0.240f, 0.304f, 0.371f, 0.442f, 0.515f, 0.590f, 0.668f, 0.748f, 0.830f, 0.914f, 1.000f)),
            hardness = 0.2f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.05f,
            streamline = 0.0f,
            minSizeRatio = 0.2f,
            tiltToSize = 0.0f,
            grainAssetPath = "asset:brush/noise_fine.png",
            grainCanvasLocked = true,
            grainScale = 1.5f,
            
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 1.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val technical_pen = BrushPreset(
        id = "technical_pen",
        settings = BrushSettings(
            name = "Technical Pen",
            category = "Inking",
            renderMode = BrushRenderMode.RIBBON,
            baseRadiusPx = 3.0f,
            opacity = 1.0f,
            flow = 1.0f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = false,
            pressureToOpacity = false,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.067f, 0.133f, 0.200f, 0.267f, 0.333f, 0.400f, 0.467f, 0.533f, 0.600f, 0.667f, 0.733f, 0.800f, 0.867f, 0.933f, 1.000f)),
            hardness = 1.0f,
            falloff = DabFalloff.HARD,
            smoothing = 0.3f,
            streamline = 0.1f,
            minSizeRatio = 1.0f,
            tiltToSize = 0.0f,
            
            
            
            
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NON_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 0.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            , ribbon = RibbonSettings(autoCloseLoop = false)
        ),
    )


    val studio_pen = BrushPreset(
        id = "studio_pen",
        settings = BrushSettings(
            name = "Studio Pen",
            category = "Inking",
            renderMode = BrushRenderMode.RIBBON,
            baseRadiusPx = 6.0f,
            opacity = 1.0f,
            flow = 1.0f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = false,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.017f, 0.049f, 0.089f, 0.138f, 0.192f, 0.253f, 0.319f, 0.389f, 0.465f, 0.544f, 0.628f, 0.716f, 0.807f, 0.902f, 1.000f)),
            hardness = 1.0f,
            falloff = DabFalloff.HARD,
            smoothing = 0.4f,
            streamline = 0.2f,
            minSizeRatio = 0.1f,
            tiltToSize = 0.0f,
            
            
            
            
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NON_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 0.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            , ribbon = RibbonSettings(autoCloseLoop = false)
        ),
    )


    val ink_bleed = BrushPreset(
        id = "ink_bleed",
        settings = BrushSettings(
            name = "Bleeding Ink",
            category = "Inking",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 10.0f,
            opacity = 1.0f,
            flow = 1.0f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = false,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.008f, 0.027f, 0.055f, 0.093f, 0.138f, 0.192f, 0.254f, 0.323f, 0.399f, 0.482f, 0.572f, 0.669f, 0.773f, 0.883f, 1.000f)),
            hardness = 0.5f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.2f,
            streamline = 0.1f,
            minSizeRatio = 0.1f,
            tiltToSize = 0.0f,
            grainAssetPath = "asset:brush/paper_hot_press.png",
            grainCanvasLocked = true,
            grainScale = 2.0f,
            
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 1.0f,
            sizeJitter = 0.1f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val acrylic = BrushPreset(
        id = "acrylic",
        settings = BrushSettings(
            name = "Acrylic Flat",
            category = "Painting",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 20.0f,
            opacity = 1.0f,
            flow = 0.9f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = false,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.017f, 0.049f, 0.089f, 0.138f, 0.192f, 0.253f, 0.319f, 0.389f, 0.465f, 0.544f, 0.628f, 0.716f, 0.807f, 0.902f, 1.000f)),
            hardness = 0.8f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.1f,
            streamline = 0.05f,
            minSizeRatio = 0.5f,
            tiltToSize = 0.0f,
            grainAssetPath = "asset:brush/canvas_linen.png",
            grainCanvasLocked = true,
            grainScale = 3.0f,
            shapeAssetPath = "asset:brush/flat_brush.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 0.02f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val oil_filbert = BrushPreset(
        id = "oil_filbert",
        settings = BrushSettings(
            name = "Oil Filbert",
            category = "Painting",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 25.0f,
            opacity = 0.9f,
            flow = 0.8f,
            spacing = 0.03f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.039f, 0.089f, 0.145f, 0.205f, 0.268f, 0.333f, 0.401f, 0.470f, 0.542f, 0.615f, 0.689f, 0.765f, 0.842f, 0.921f, 1.000f)),
            hardness = 0.7f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.1f,
            streamline = 0.05f,
            minSizeRatio = 0.4f,
            tiltToSize = 0.0f,
            grainAssetPath = "asset:brush/canvas_linen.png",
            grainCanvasLocked = true,
            grainScale = 3.0f,
            shapeAssetPath = "asset:brush/filbert.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 0.05f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val dry_brush = BrushPreset(
        id = "dry_brush",
        settings = BrushSettings(
            name = "Dry Brush",
            category = "Painting",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 30.0f,
            opacity = 0.8f,
            flow = 0.5f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.017f, 0.049f, 0.089f, 0.138f, 0.192f, 0.253f, 0.319f, 0.389f, 0.465f, 0.544f, 0.628f, 0.716f, 0.807f, 0.902f, 1.000f)),
            hardness = 0.3f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.1f,
            streamline = 0.0f,
            minSizeRatio = 0.3f,
            tiltToSize = 0.0f,
            
            
            
            shapeAssetPath = "asset:brush/dry_brush.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 1.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val round_soft = BrushPreset(
        id = "round_soft",
        settings = BrushSettings(
            name = "Soft Round",
            category = "Painting",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 30.0f,
            opacity = 1.0f,
            flow = 0.8f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.039f, 0.089f, 0.145f, 0.205f, 0.268f, 0.333f, 0.401f, 0.470f, 0.542f, 0.615f, 0.689f, 0.765f, 0.842f, 0.921f, 1.000f)),
            hardness = 0.0f,
            falloff = DabFalloff.AIRBRUSH,
            smoothing = 0.1f,
            streamline = 0.05f,
            minSizeRatio = 0.2f,
            tiltToSize = 0.0f,
            
            
            
            shapeAssetPath = "asset:brush/round_soft.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 1.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val water_color = BrushPreset(
        id = "water_color",
        settings = BrushSettings(
            name = "Watercolor Soft",
            category = "Painting",
            renderMode = BrushRenderMode.WET,
            wet = WetSettings(
                wetness = 0.45f,
                spread = 0.3f,
                bleed = 0.55f,
                edgeDarkening = 0.22f,
                advection = 0.35f,
                coagulation = 0.35f,
                evaporation = 0.04f,
            ),
            baseRadiusPx = 40.0f,
            opacity = 0.7f,
            flow = 0.5f,
            spacing = 0.04f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.051f, 0.109f, 0.170f, 0.234f, 0.299f, 0.365f, 0.432f, 0.501f, 0.570f, 0.640f, 0.711f, 0.782f, 0.854f, 0.927f, 1.000f)),
            hardness = 0.1f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.2f,
            streamline = 0.05f,
            minSizeRatio = 0.3f,
            tiltToSize = 0.0f,
            grainAssetPath = "asset:brush/paper_cold_press.png",
            grainCanvasLocked = true,
            grainScale = 3.0f,
            
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 1.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val soft_airbrush = BrushPreset(
        id = "soft_airbrush",
        settings = BrushSettings(
            name = "Soft Airbrush",
            category = "Airbrush",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 100.0f,
            opacity = 0.1f,
            flow = 0.05f,
            spacing = 0.03f,
            spacingUsesDiameter = true,
            pressureToSize = false,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.051f, 0.109f, 0.170f, 0.234f, 0.299f, 0.365f, 0.432f, 0.501f, 0.570f, 0.640f, 0.711f, 0.782f, 0.854f, 0.927f, 1.000f)),
            hardness = 0.0f,
            falloff = DabFalloff.AIRBRUSH,
            smoothing = 0.1f,
            streamline = 0.0f,
            minSizeRatio = 1.0f,
            tiltToSize = 0.0f,
            
            
            
            
            emissionUsesTime = true,
            emissionRateHz = 60f,
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 1.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val hard_airbrush = BrushPreset(
        id = "hard_airbrush",
        settings = BrushSettings(
            name = "Hard Airbrush",
            category = "Airbrush",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 80.0f,
            opacity = 0.3f,
            flow = 0.2f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.039f, 0.089f, 0.145f, 0.205f, 0.268f, 0.333f, 0.401f, 0.470f, 0.542f, 0.615f, 0.689f, 0.765f, 0.842f, 0.921f, 1.000f)),
            hardness = 0.5f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.1f,
            streamline = 0.0f,
            minSizeRatio = 0.2f,
            tiltToSize = 0.0f,
            
            
            
            
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 1.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val marker = BrushPreset(
        id = "marker",
        settings = BrushSettings(
            name = "Broad Marker",
            category = "Markers",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 15.0f,
            opacity = 0.7f,
            flow = 1.0f,
            spacing = 0.04f,
            spacingUsesDiameter = true,
            pressureToSize = false,
            pressureToOpacity = false,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.067f, 0.133f, 0.200f, 0.267f, 0.333f, 0.400f, 0.467f, 0.533f, 0.600f, 0.667f, 0.733f, 0.800f, 0.867f, 0.933f, 1.000f)),
            hardness = 1.0f,
            falloff = DabFalloff.HARD,
            smoothing = 0.1f,
            streamline = 0.05f,
            minSizeRatio = 1.0f,
            tiltToSize = 0.0f,
            
            
            
            shapeAssetPath = "asset:brush/flat_brush.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.MULTIPLY,
            
            scatter = 0.0f,
            rotationJitter = 0.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val fine_liner = BrushPreset(
        id = "fine_liner",
        settings = BrushSettings(
            name = "Fine Liner",
            category = "Markers",
            renderMode = BrushRenderMode.RIBBON,
            baseRadiusPx = 2.0f,
            opacity = 1.0f,
            flow = 1.0f,
            spacing = 0.05f,
            spacingUsesDiameter = true,
            pressureToSize = false,
            pressureToOpacity = false,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.067f, 0.133f, 0.200f, 0.267f, 0.333f, 0.400f, 0.467f, 0.533f, 0.600f, 0.667f, 0.733f, 0.800f, 0.867f, 0.933f, 1.000f)),
            hardness = 1.0f,
            falloff = DabFalloff.HARD,
            smoothing = 0.2f,
            streamline = 0.1f,
            minSizeRatio = 1.0f,
            tiltToSize = 0.0f,
            
            
            
            
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NON_BUILDUP,
            
            scatter = 0.0f,
            rotationJitter = 0.0f,
            sizeJitter = 0.0f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            , ribbon = RibbonSettings(autoCloseLoop = false)
        ),
    )


    val sponge = BrushPreset(
        id = "sponge",
        settings = BrushSettings(
            name = "Sponge",
            category = "Textures",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 60.0f,
            opacity = 0.8f,
            flow = 0.6f,
            spacing = 0.3f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.017f, 0.049f, 0.089f, 0.138f, 0.192f, 0.253f, 0.319f, 0.389f, 0.465f, 0.544f, 0.628f, 0.716f, 0.807f, 0.902f, 1.000f)),
            hardness = 0.2f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.0f,
            streamline = 0.0f,
            minSizeRatio = 0.5f,
            tiltToSize = 0.0f,
            
            
            
            shapeAssetPath = "asset:brush/sponge.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.2f,
            rotationJitter = 1.0f,
            sizeJitter = 0.2f,
            opacityJitter = 0.2f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val splatter = BrushPreset(
        id = "splatter",
        settings = BrushSettings(
            name = "Splatter",
            category = "Textures",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 80.0f,
            opacity = 1.0f,
            flow = 1.0f,
            spacing = 0.6f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.067f, 0.133f, 0.200f, 0.267f, 0.333f, 0.400f, 0.467f, 0.533f, 0.600f, 0.667f, 0.733f, 0.800f, 0.867f, 0.933f, 1.000f)),
            hardness = 0.5f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.0f,
            streamline = 0.0f,
            minSizeRatio = 0.1f,
            tiltToSize = 0.0f,
            
            
            
            shapeAssetPath = "asset:brush/splatter.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.5f,
            rotationJitter = 1.0f,
            sizeJitter = 0.5f,
            opacityJitter = 0.2f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val cracks = BrushPreset(
        id = "cracks",
        settings = BrushSettings(
            name = "Cracks",
            category = "Textures",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 50.0f,
            opacity = 1.0f,
            flow = 1.0f,
            spacing = 0.8f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = false,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.067f, 0.133f, 0.200f, 0.267f, 0.333f, 0.400f, 0.467f, 0.533f, 0.600f, 0.667f, 0.733f, 0.800f, 0.867f, 0.933f, 1.000f)),
            hardness = 0.5f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.0f,
            streamline = 0.0f,
            minSizeRatio = 0.3f,
            tiltToSize = 0.0f,
            
            
            
            shapeAssetPath = "asset:brush/crack.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.1f,
            rotationJitter = 1.0f,
            sizeJitter = 0.3f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val leaf = BrushPreset(
        id = "leaf",
        settings = BrushSettings(
            name = "Scattering Leaves",
            category = "Textures",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 40.0f,
            opacity = 1.0f,
            flow = 1.0f,
            spacing = 1.5f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = false,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.067f, 0.133f, 0.200f, 0.267f, 0.333f, 0.400f, 0.467f, 0.533f, 0.600f, 0.667f, 0.733f, 0.800f, 0.867f, 0.933f, 1.000f)),
            hardness = 1.0f,
            falloff = DabFalloff.HARD,
            smoothing = 0.0f,
            streamline = 0.0f,
            minSizeRatio = 0.4f,
            tiltToSize = 0.0f,
            
            
            
            shapeAssetPath = "asset:brush/leaf.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 1.0f,
            rotationJitter = 1.0f,
            sizeJitter = 0.4f,
            opacityJitter = 0.0f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val fiber = BrushPreset(
        id = "fiber",
        settings = BrushSettings(
            name = "Fibers",
            category = "Textures",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 40.0f,
            opacity = 0.8f,
            flow = 0.8f,
            spacing = 0.2f,
            spacingUsesDiameter = true,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureCurve = DynamicsCurve(listOf(0.000f, 0.067f, 0.133f, 0.200f, 0.267f, 0.333f, 0.400f, 0.467f, 0.533f, 0.600f, 0.667f, 0.733f, 0.800f, 0.867f, 0.933f, 1.000f)),
            hardness = 0.3f,
            falloff = DabFalloff.SOFT,
            smoothing = 0.0f,
            streamline = 0.0f,
            minSizeRatio = 0.2f,
            tiltToSize = 0.0f,
            
            
            
            shapeAssetPath = "asset:brush/fiber.png",
            emissionUsesTime = false,
            
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            
            scatter = 0.15f,
            rotationJitter = 1.0f,
            sizeJitter = 0.1f,
            opacityJitter = 0.1f,
            velocityToSize = 0.0f,
            velocityToOpacity = 0.0f
            
        ),
    )


    val allCategories = listOf(
        BrushUiCategory("Sketching", androidx.compose.material.icons.Icons.Default.Create, listOf(hb_pencil, charcoal, chalk)),
        BrushUiCategory("Inking", androidx.compose.material.icons.Icons.Default.Create, listOf(technical_pen, studio_pen, ink_bleed)),
        BrushUiCategory("Painting", androidx.compose.material.icons.Icons.Default.Brush, listOf(acrylic, oil_filbert, dry_brush, round_soft, water_color)),
        BrushUiCategory("Airbrush", androidx.compose.material.icons.Icons.Default.Brush, listOf(soft_airbrush, hard_airbrush)),
        BrushUiCategory("Markers", androidx.compose.material.icons.Icons.Default.Create, listOf(marker, fine_liner)),
        BrushUiCategory("Textures", androidx.compose.material.icons.Icons.Default.Star, listOf(sponge, splatter, cracks, leaf, fiber)),
    )

}
