package com.wetinknext.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle

@Composable
fun WetInkTheme(
    theme: AppTheme,
    fontMode: UiFont,
    content: @Composable () -> Unit
) {
    val fontStyle = if (fontMode == UiFont.Italic) FontStyle.Italic else FontStyle.Normal
    val fontFamily = if (fontMode == UiFont.Italic) FontFamily.Serif else FontFamily.SansSerif
    
    val typography = Typography(
        bodyLarge = TextStyle(fontStyle = fontStyle, fontFamily = fontFamily),
        bodyMedium = TextStyle(fontStyle = fontStyle, fontFamily = fontFamily),
        bodySmall = TextStyle(fontStyle = fontStyle, fontFamily = fontFamily),
        titleLarge = TextStyle(fontStyle = fontStyle, fontFamily = fontFamily),
        titleMedium = TextStyle(fontStyle = fontStyle, fontFamily = fontFamily),
        titleSmall = TextStyle(fontStyle = fontStyle, fontFamily = fontFamily),
        labelLarge = TextStyle(fontStyle = fontStyle, fontFamily = fontFamily),
        labelMedium = TextStyle(fontStyle = fontStyle, fontFamily = fontFamily),
        labelSmall = TextStyle(fontStyle = fontStyle, fontFamily = fontFamily),
    )

    val scheme = if (!theme.isLight) {
        darkColorScheme(
            primary = theme.accent,
            onPrimary = theme.textPrimary,
            background = theme.appBg, // Цвет стола
            onBackground = theme.textPrimary,
            surface = theme.panelBg, // Цвет панелей
            onSurface = theme.textPrimary,
            surfaceVariant = theme.panelBgVariant,
            onSurfaceVariant = theme.textSecondary,
        )
    } else {
        lightColorScheme(
            primary = theme.accent,
            onPrimary = Color.White,
            background = theme.appBg,
            onBackground = theme.textPrimary,
            surface = theme.panelBg,
            onSurface = theme.textPrimary,
            surfaceVariant = theme.panelBgVariant,
            onSurfaceVariant = theme.textSecondary,
        )
    }

    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content,
        )
    }
}
