package com.wetinknext.engine.brush

object BrushLibrary {
    val pencil6B = BrushPreset(
        id = "pencil_6b",
        settings = BrushSettings(
            name = "Pencil 6B",
            category = "Pencil",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 8f,
            opacity = 0.82f,
            flow = 0.65f,
            spacing = 0.08f,
            spacingUsesDiameter = true,
            hardness = 0.72f,
            smoothing = 0.08f,
            streamline = 0.04f,
            pressureToSize = true,
            pressureToOpacity = true,
            pressureGamma = 1.25f,
            minSizeRatio = 0.30f,
            tiltToSize = 0.55f,
            grainAssetPath = "asset:brush/pencil_6b_grain.png",
            grainCanvasLocked = true,
            grainScale = 5.5f,
            textureDepth = 0.65f,
            textureContrast = 1.35f,
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
        ),
    )

    val gPen = BrushPreset(
        id = "g_pen",
        settings = BrushSettings(
            name = "G-Pen",
            category = "Ink",
            renderMode = BrushRenderMode.RIBBON,
            baseRadiusPx = 4.5f,
            opacity = 1f,
            flow = 1f,
            smoothing = 0.35f,
            streamline = 0.22f,
            pressureToSize = true,
            pressureToOpacity = false,
            pressureGamma = 1.7f,
            minSizeRatio = 0.06f,
            blendPolicy = BlendPolicy.NON_BUILDUP,
        )
    )
}
