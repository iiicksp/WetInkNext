package com.wetinknext.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.wetinknext.R

private val ClassicFontFamily = FontFamily(
    Font(R.font.lora_variable, FontWeight.Normal),
    Font(R.font.lora_variable, FontWeight.Bold),
)

private val HandwrittenFontFamily = FontFamily(
    Font(R.font.caveat_variable, FontWeight.Normal),
    Font(R.font.caveat_variable, FontWeight.Bold),
)

@Composable
fun WetInkTheme(
    theme: AppTheme,
    fontMode: UiFont,
    content: @Composable () -> Unit
) {
    val fontFamily = if (fontMode == UiFont.Italic) HandwrittenFontFamily else ClassicFontFamily
    
    /*
     * One compact editorial scale for every editor panel. Individual controls
     * may still use a title style, but labels and segmented toolbars now share
     * the same readable base instead of each panel inventing its own scale.
     */
    val typography = Typography(
        bodyLarge = TextStyle(fontFamily = fontFamily, fontSize = 14.sp, lineHeight = 18.sp),
        bodyMedium = TextStyle(fontFamily = fontFamily, fontSize = 13.sp, lineHeight = 17.sp),
        bodySmall = TextStyle(fontFamily = fontFamily, fontSize = 12.sp, lineHeight = 16.sp),
        titleLarge = TextStyle(fontFamily = fontFamily, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontFamily = fontFamily, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
        titleSmall = TextStyle(fontFamily = fontFamily, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelLarge = TextStyle(fontFamily = fontFamily, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontFamily = fontFamily, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = fontFamily, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
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
