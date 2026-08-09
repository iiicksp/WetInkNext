package com.wetinknext.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class AppTheme(
    val id: String,
    val displayName: String,
    val isLight: Boolean,
    
    // Core Layout
    val appBg: Color,
    val gridLine: Color,
    val panelBg: Color,
    val panelBgSolid: Color,
    val panelStroke: Color,
    val panelInset: Color,
    val panelInsetSoft: Color,
    
    // Canvas
    val canvasBackdrop: Color,
    val canvasGrid: Color,
    
    // Typography & Icons
    val textPrimary: Color,
    val textSecondary: Color,
    val iconInactive: Color,
    
    // Accents
    val accent: Color,
    val accentSoft: Color,
    val accentMuted: Color,
    val danger: Color
) {
    val panelBgVariant: Color get() = panelInset
}

object AppThemes {
    val Dark = AppTheme(
        id = "dark", displayName = "Dark", isLight = false,
        appBg = Color(0xFF1A0C12), gridLine = Color(0xFF2A1418),
        panelBg = Color(0xFF2A141C), panelBgSolid = Color(0xFF2D1620),
        panelStroke = Color(0xFF3D1F2A), panelInset = Color(0xFF1F1015), panelInsetSoft = Color(0xFF2A1820),
        canvasBackdrop = Color(0xFF1A0C12), canvasGrid = Color(0xFF3A1C24),
        textPrimary = Color(0xFFF5E8EC), textSecondary = Color(0xFF9A7F8A), iconInactive = Color(0xFFC8B0B8),
        accent = Color(0xFFFF4D7A), accentSoft = Color(0xFFFF7AA0), accentMuted = Color(0x55FF4D7A), danger = Color(0xFFFF3B5C)
    )

    val Light = AppTheme(
        id = "light", displayName = "Light", isLight = true,
        appBg = Color(0xFFFFE4E9), gridLine = Color(0xFFF5D0D7),
        panelBg = Color(0xFFFFF1F4), panelBgSolid = Color(0xFFFFFFFF),
        panelStroke = Color(0xFFF0C8D0), panelInset = Color(0xFFFFE0E6), panelInsetSoft = Color(0xFFFFD0DA),
        canvasBackdrop = Color(0xFFFFE4E9), canvasGrid = Color(0xFFF5D0D7),
        textPrimary = Color(0xFF3D1B26), textSecondary = Color(0xFF8A5A6A), iconInactive = Color(0xFFB07A88),
        accent = Color(0xFFFF4D7A), accentSoft = Color(0xFFFFA0B7), accentMuted = Color(0x33FF4D7A), danger = Color(0xFFFF3B5C)
    )

    val Red = AppTheme(
        id = "red", displayName = "Red", isLight = false,
        appBg = Color(0xFF1A0606), gridLine = Color(0xFF2A0A0A),
        panelBg = Color(0xFF2A0C0C), panelBgSolid = Color(0xFF2D0E0E),
        panelStroke = Color(0xFF3D1414), panelInset = Color(0xFF1F0808), panelInsetSoft = Color(0xFF2A1010),
        canvasBackdrop = Color(0xFF1A0606), canvasGrid = Color(0xFF3A1414),
        textPrimary = Color(0xFFF5E8E8), textSecondary = Color(0xFF9A7F7F), iconInactive = Color(0xFFC8B0B0),
        accent = Color(0xFFFF3D3D), accentSoft = Color(0xFFFF7A7A), accentMuted = Color(0x55FF3D3D), danger = Color(0xFFFF8888)
    )

    val Vampire = AppTheme(
        id = "vampire", displayName = "Vampire", isLight = false,
        appBg = Color(0xFF1B1C1B), gridLine = Color(0xFF252525),
        panelBg = Color(0xFF262626), panelBgSolid = Color(0xFF2D2D2D),
        panelStroke = Color(0xFF4B2529), panelInset = Color(0xFF171717), panelInsetSoft = Color(0xFF2A1B1E),
        canvasBackdrop = Color(0xFF1B1C1B), canvasGrid = Color(0xFF2B2B2B),
        textPrimary = Color(0xFFF2ECEC), textSecondary = Color(0xFFCDA2A6), iconInactive = Color(0xFFE15761),
        accent = Color(0xFFFF3547), accentSoft = Color(0xFFFFA3A8), accentMuted = Color(0xAAFF3547), danger = Color(0xFFFF4A5F)
    )

    val Olive = AppTheme(
        id = "olive", displayName = "Olive", isLight = false,
        appBg = Color(0xFF171B0D), gridLine = Color(0xFF252A16),
        panelBg = Color(0xFF202711), panelBgSolid = Color(0xFF263014),
        panelStroke = Color(0xFF566032), panelInset = Color(0xFF121806), panelInsetSoft = Color(0xFF2B3418),
        canvasBackdrop = Color(0xFF171B0D), canvasGrid = Color(0xFF2F351C),
        textPrimary = Color(0xFFF1E7D0), textSecondary = Color(0xFFC7A77E), iconInactive = Color(0xFFE3A15E),
        accent = Color(0xFFFF982F), accentSoft = Color(0xFFFFC46A), accentMuted = Color(0x88FF982F), danger = Color(0xFFFF624D)
    )

    val Blue = AppTheme(
        id = "blue", displayName = "Blue", isLight = false,
        appBg = Color(0xFF06101A), gridLine = Color(0xFF0A1828),
        panelBg = Color(0xFF0C1A2A), panelBgSolid = Color(0xFF0E1D2E),
        panelStroke = Color(0xFF14283D), panelInset = Color(0xFF08141F), panelInsetSoft = Color(0xFF101F2A),
        canvasBackdrop = Color(0xFF06101A), canvasGrid = Color(0xFF14283A),
        textPrimary = Color(0xFFE8EEF5), textSecondary = Color(0xFF7F8C9A), iconInactive = Color(0xFFB0BBC8),
        accent = Color(0xFF4D7AFF), accentSoft = Color(0xFF7AA0FF), accentMuted = Color(0x554D7AFF), danger = Color(0xFFFF3B5C)
    )

    val Studio = AppTheme(
        id = "studio", displayName = "Studio", isLight = true,
        appBg = Color(0xFFE1E1E1), gridLine = Color(0xFFD1D6DA),
        panelBg = Color(0xFFF0F2F4), panelBgSolid = Color(0xFFFBFCFD),
        panelStroke = Color(0xFFD4DAE0), panelInset = Color(0xFFE7EBEF), panelInsetSoft = Color(0xFFDCE8F3),
        canvasBackdrop = Color(0xFFDADADA), canvasGrid = Color(0xFFCFCFCF),
        textPrimary = Color(0xFF20252A), textSecondary = Color(0xFF65737F), iconInactive = Color(0xFF5C8DB3),
        accent = Color(0xFF4A9AD8), accentSoft = Color(0xFF7FBCEB), accentMuted = Color(0x334A9AD8), danger = Color(0xFFE65C73)
    )

    val Neon = AppTheme(
        id = "neon", displayName = "Neon", isLight = false,
        appBg = Color(0xFF2B123E), gridLine = Color(0xFF32184A),
        panelBg = Color(0xFF4A2366), panelBgSolid = Color(0xFF522A70),
        panelStroke = Color(0xFF6C3A8D), panelInset = Color(0xFF321344), panelInsetSoft = Color(0xFF3C1853),
        canvasBackdrop = Color(0xFF2B123E), canvasGrid = Color(0xFF3C1D55),
        textPrimary = Color(0xFFF3EAFE), textSecondary = Color(0xFFC6A8D8), iconInactive = Color(0xFF2FE0EA),
        accent = Color(0xFF25DDE8), accentSoft = Color(0xFF73F3FA), accentMuted = Color(0x5525DDE8), danger = Color(0xFFFF6E9C)
    )

    val Cyan = AppTheme(
        id = "cyan", displayName = "Cyan", isLight = false,
        appBg = Color(0xFF06181A), gridLine = Color(0xFF0A2628),
        panelBg = Color(0xFF0C282A), panelBgSolid = Color(0xFF0E2C2E),
        panelStroke = Color(0xFF143C3D), panelInset = Color(0xFF081F20), panelInsetSoft = Color(0xFF102A2A),
        canvasBackdrop = Color(0xFF06181A), canvasGrid = Color(0xFF143A3A),
        textPrimary = Color(0xFFE8F5F5), textSecondary = Color(0xFF7F9A9A), iconInactive = Color(0xFFB0C8C8),
        accent = Color(0xFF26D9D9), accentSoft = Color(0xFF7AE0E0), accentMuted = Color(0x5526D9D9), danger = Color(0xFFFF3B5C)
    )

    val Green = AppTheme(
        id = "green", displayName = "Green", isLight = false,
        appBg = Color(0xFF071A0C), gridLine = Color(0xFF0A2814),
        panelBg = Color(0xFF0C2A14), panelBgSolid = Color(0xFF0E2D16),
        panelStroke = Color(0xFF143D1F), panelInset = Color(0xFF081F0C), panelInsetSoft = Color(0xFF102A14),
        canvasBackdrop = Color(0xFF071A0C), canvasGrid = Color(0xFF143A1F),
        textPrimary = Color(0xFFE8F5EC), textSecondary = Color(0xFF7F9A88), iconInactive = Color(0xFFB0C8B8),
        accent = Color(0xFF3DDB6E), accentSoft = Color(0xFF7AE0A0), accentMuted = Color(0x553DDB6E), danger = Color(0xFFFF3B5C)
    )

    val Yellow = AppTheme(
        id = "yellow", displayName = "Yellow", isLight = true,
        appBg = Color(0xFFFFF8E0), gridLine = Color(0xFFF5E5A0),
        panelBg = Color(0xFFFFF4D0), panelBgSolid = Color(0xFFFFFCF0),
        panelStroke = Color(0xFFE6CC60), panelInset = Color(0xFFFFEFB8), panelInsetSoft = Color(0xFFFFE890),
        canvasBackdrop = Color(0xFFFFF8E0), canvasGrid = Color(0xFFF0DA80),
        textPrimary = Color(0xFF3D2E08), textSecondary = Color(0xFF8A7A40), iconInactive = Color(0xFFB09860),
        accent = Color(0xFFF5B800), accentSoft = Color(0xFFFFD050), accentMuted = Color(0x55F5B800), danger = Color(0xFFFF3B5C)
    )

    val Orange = AppTheme(
        id = "orange", displayName = "Orange", isLight = true,
        appBg = Color(0xFFFFE8D5), gridLine = Color(0xFFF5C898),
        panelBg = Color(0xFFFFE0C5), panelBgSolid = Color(0xFFFFF1E5),
        panelStroke = Color(0xFFE69C58), panelInset = Color(0xFFFFD8B5), panelInsetSoft = Color(0xFFFFC890),
        canvasBackdrop = Color(0xFFFFE8D5), canvasGrid = Color(0xFFF0BC80),
        textPrimary = Color(0xFF3D1E08), textSecondary = Color(0xFF8A5A30), iconInactive = Color(0xFFB07A50),
        accent = Color(0xFFFF8800), accentSoft = Color(0xFFFFB050), accentMuted = Color(0x55FF8800), danger = Color(0xFFFF3B5C)
    )

    val Gray = AppTheme(
        id = "gray", displayName = "Gray", isLight = false,
        appBg = Color(0xFF1A1A1A), gridLine = Color(0xFF2A2A2A),
        panelBg = Color(0xFF2A2A2A), panelBgSolid = Color(0xFF303030),
        panelStroke = Color(0xFF404040), panelInset = Color(0xFF1F1F1F), panelInsetSoft = Color(0xFF2B2B2B),
        canvasBackdrop = Color(0xFF1A1A1A), canvasGrid = Color(0xFF3A3A3A),
        textPrimary = Color(0xFFF0F0F0), textSecondary = Color(0xFF909090), iconInactive = Color(0xFFB8B8B8),
        accent = Color(0xFFB0B0B0), accentSoft = Color(0xFFD0D0D0), accentMuted = Color(0x55B0B0B0), danger = Color(0xFFFF3B5C)
    )

    val White = AppTheme(
        id = "white", displayName = "White", isLight = true,
        appBg = Color(0xFFFFFFFF), gridLine = Color(0xFFE8E8E8),
        panelBg = Color(0xFFF5F5F5), panelBgSolid = Color(0xFFFFFFFF),
        panelStroke = Color(0xFFD8D8D8), panelInset = Color(0xFFEEEEEE), panelInsetSoft = Color(0xFFE0E0E0),
        canvasBackdrop = Color(0xFFFFFFFF), canvasGrid = Color(0xFFE0E0E0),
        textPrimary = Color(0xFF202020), textSecondary = Color(0xFF707070), iconInactive = Color(0xFF989898),
        accent = Color(0xFF606060), accentSoft = Color(0xFF888888), accentMuted = Color(0x55606060), danger = Color(0xFFFF3B5C)
    )

    val all = listOf(
        Dark, Light, Red, Vampire, Olive, Blue, Studio, Neon, Cyan, Green, Yellow, Orange, Gray, White
    )

    val default = Gray
}

enum class UiFont { Classic, Italic }
