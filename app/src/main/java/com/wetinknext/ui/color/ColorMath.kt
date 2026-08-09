package com.wetinknext.ui.color

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

data class Hsl(val h: Float, val s: Float, val l: Float)

fun rgbToHsl(c: Color): Hsl {
    val r = c.red
    val g = c.green
    val b = c.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return Hsl(0f, 0f, l)
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Hsl(((h % 360f) + 360f) % 360f, s, l)
}

fun hslToColor(h: Float, s: Float, l: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((hh / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r1, g1, b1) = when ((hh / 60f).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        (r1 + m).coerceIn(0f, 1f),
        (g1 + m).coerceIn(0f, 1f),
        (b1 + m).coerceIn(0f, 1f),
        1f,
    )
}

fun hslToColor(hsl: Hsl): Color = hslToColor(hsl.h, hsl.s, hsl.l)

fun Color.toHex6(): String {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}

enum class HarmonyType(
    val displayName: String,
    val offsets: FloatArray,
) {
    Complementary("Комплементарная", floatArrayOf(0f, 180f)),
    Analogous("Аналоговая", floatArrayOf(-30f, 0f, 30f)),
    Triadic("Триадная", floatArrayOf(0f, 120f, 240f)),
    SplitComplementary(
        "Сплит-комплементарная",
        floatArrayOf(0f, 150f, 210f),
    ),
    Tetradic("Тетрадная", floatArrayOf(0f, 60f, 180f, 240f)),
    Square("Квадратная", floatArrayOf(0f, 90f, 180f, 270f)),
    ;

    fun derive(base: Hsl): List<Hsl> {
        val h = base.h
        return offsets.map { off ->
            Hsl(((h + off) % 360f + 360f) % 360f, base.s, base.l)
        }
    }
}
