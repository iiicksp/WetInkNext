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
    /**
     * Soft, low-flow airbrush. Kept in the stamp pipeline because its look is
     * built from many translucent dabs, rather than a single ribbon contour.
     */
    val airbrush = BrushPreset(
        id = "airbrush",
        settings = BrushSettings(
            name = "Airbrush",
            category = "Paint",
            renderMode = BrushRenderMode.STAMP,
            baseRadiusPx = 80f,
            opacity = 0.18f,
            flow = 0.08f,
            spacing = 0.035f,
            spacingUsesDiameter = true,
            pressureToSize = false,
            pressureToOpacity = true,
            pressureGamma = 1.15f,
            hardness = 0f,
            falloff = DabFalloff.AIRBRUSH,
            emissionUsesTime = true,
            emissionRateHz = 60f,
            antiAliasLevel = 3,
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
            useTempStrokeBuffer = true,
        ),
    )

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
            falloff = DabFalloff.SOFT,
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
            ribbon = RibbonSettings(
                autoCloseLoop = false,
            ),
        )
    )

    /** Baseline ink preset for checking the capsule path without geometric taper. */
    val pen = BrushPreset(
        id = "pen",
        settings = gPen.settings.copy(
            name = "Pen",
            ribbon = gPen.settings.ribbon.copy(
                taperStartPx = 0f,
                taperEndPx = 0f,
                autoCloseLoop = true,
            ),
        ),
    )

    val allCategories = listOf(
        BrushUiCategory(
            name = "Paint",
            icon = Icons.Default.Brush,
            brushes = listOf(airbrush),
        ),
        BrushUiCategory(
            name = "Pencil",
            icon = Icons.Default.Create,
            brushes = listOf(pencil6B)
        ),
        BrushUiCategory(
            name = "Ink",
            icon = Icons.Default.Brush,
            brushes = listOf(gPen, pen)
        ),
        BrushUiCategory(
            name = "Favorites",
            icon = Icons.Default.Star,
            brushes = emptyList()
        )
    )
}
