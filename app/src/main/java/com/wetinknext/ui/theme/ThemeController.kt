package com.wetinknext.ui.theme

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.pow

val LocalAppTheme = compositionLocalOf { AppThemes.Gray }

data class CustomThemeColors(
    val appBackground: Int = 0xFF101826.toInt(),
    val grid: Int = 0xFF38424C.toInt(),
    val panel: Int = 0xFF172033.toInt(),
    val accent: Int = 0xFF58A6FF.toInt(),
    val text: Int = 0xFFF4F7FB.toInt(),
)

class ThemeController(
    initialThemeId: String = "gray",
    initialFontMode: String = "Classic",
    initialCustomColors: CustomThemeColors = CustomThemeColors()
) {
    var current by mutableStateOf(resolveTheme(initialThemeId, initialCustomColors))
        private set
    var font by mutableStateOf(if (initialFontMode == "Italic") UiFont.Italic else UiFont.Classic)
        private set
    var customColors by mutableStateOf(initialCustomColors)
        private set
    val customTheme: AppTheme
        get() = createCustomTheme(customColors)

    fun select(theme: AppTheme) {
        current = if (theme.id == "custom") createCustomTheme(customColors) else theme
    }

    fun selectCustom() {
        current = createCustomTheme(customColors)
    }
    
    fun selectFont(f: UiFont) { font = f }

    fun updateCustomColors(newColors: CustomThemeColors) {
        customColors = newColors
        if (current.id == "custom") current = createCustomTheme(newColors)
    }

    private fun resolveTheme(id: String, colors: CustomThemeColors): AppTheme {
        if (id == "custom") return createCustomTheme(colors)
        return AppThemes.all.find { it.id == id } ?: AppThemes.Gray
    }

    private fun createCustomTheme(colors: CustomThemeColors): AppTheme {
        val appBg = Color(colors.appBackground)
        val panel = Color(colors.panel)
        val accent = Color(colors.accent)
        val text = Color(colors.text)
        val grid = Color(colors.grid)
        val isDark = luminanceOf(appBg) < 0.5f
        
        return AppTheme(
            id = "custom", displayName = "Своя", isLight = !isDark,
            appBg = appBg, gridLine = grid,
            panelBg = panel, panelBgSolid = panel.mix(if (isDark) Color.White else Color.Black, 0.08f),
            panelStroke = panel.mix(accent, 0.3f), 
            panelInset = panel.mix(if (isDark) Color.Black else Color.White, 0.2f),
            panelInsetSoft = panel.mix(accent, 0.1f),
            canvasBackdrop = appBg, canvasGrid = grid,
            textPrimary = text, textSecondary = text.copy(alpha = 0.6f),
            iconInactive = text.copy(alpha = 0.4f),
            accent = accent, accentSoft = accent.copy(alpha = 0.7f),
            accentMuted = accent.copy(alpha = 0.3f),
            danger = Color(0xFFFF4A5F)
        )
    }

    private fun Color.mix(target: Color, amount: Float): Color {
        return Color(
            red = red * (1f - amount) + target.red * amount,
            green = green * (1f - amount) + target.green * amount,
            blue = blue * (1f - amount) + target.blue * amount,
            alpha = alpha
        )
    }

    private fun luminanceOf(color: Color): Float {
        fun channel(value: Float): Float =
            if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
    }

    companion object {
        val Saver: Saver<ThemeController, *> = Saver(
            save = { listOf(it.current.id, it.font.name, it.customColors.appBackground, it.customColors.grid, it.customColors.panel, it.customColors.accent, it.customColors.text) },
            restore = { list ->
                val l = list as List<*>
                ThemeController(
                    initialThemeId = l[0] as String,
                    initialFontMode = l[1] as String,
                    initialCustomColors = CustomThemeColors(
                        l[2] as Int, l[3] as Int, l[4] as Int, l[5] as Int, l[6] as Int
                    )
                )
            }
        )
    }
}

@Composable
fun rememberThemeController(): ThemeController {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wetinknext_theme_prefs", Context.MODE_PRIVATE) }

    val controller = rememberSaveable(saver = ThemeController.Saver) {
        val themeId = prefs.getString("theme_id", "gray") ?: "gray"
        val fontMode = prefs.getString("font_mode", "Classic") ?: "Classic"
        val customColors = CustomThemeColors(
            appBackground = prefs.getInt("custom_bg", 0xFF101826.toInt()),
            grid = prefs.getInt("custom_grid", 0xFF38424C.toInt()),
            panel = prefs.getInt("custom_panel", 0xFF172033.toInt()),
            accent = prefs.getInt("custom_accent", 0xFF58A6FF.toInt()),
            text = prefs.getInt("custom_text", 0xFFF4F7FB.toInt())
        )
        ThemeController(themeId, fontMode, customColors)
    }

    LaunchedEffect(controller.current.id, controller.font, controller.customColors) {
        prefs.edit()
            .putString("theme_id", controller.current.id)
            .putString("font_mode", controller.font.name)
            .putInt("custom_bg", controller.customColors.appBackground)
            .putInt("custom_grid", controller.customColors.grid)
            .putInt("custom_panel", controller.customColors.panel)
            .putInt("custom_accent", controller.customColors.accent)
            .putInt("custom_text", controller.customColors.text)
            .apply()
    }

    return controller
}
