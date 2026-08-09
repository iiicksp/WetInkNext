package com.wetinknext.ui.color

import androidx.compose.ui.graphics.Color

data class ColorCollection(
    val id: Long,
    val name: String,
    val colors: List<Int>,
    val builtin: Boolean = false,
)

fun ColorCollection.composeColors(): List<Color> = colors.map { Color(it) }

private fun hex(s: String): Int {
    val v = s.removePrefix("#").trim()
    val r = v.substring(0, 2).toInt(16)
    val g = v.substring(2, 4).toInt(16)
    val b = v.substring(4, 6).toInt(16)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun palette(vararg hex: String): List<Int> = hex.map { hex(it) }

object BuiltinCollections {
    val all: List<ColorCollection> = listOf(
        ColorCollection(
            id = -1L,
            name = "Стандарт",
            colors = palette(
                "#FFF3B0", "#FFD6A5", "#FFC2B0", "#FFCCD5", "#F1C6F2",
                "#D8C7F5", "#C5D7FF", "#B8E5FF", "#B6F0EE", "#C9F2D8",
                "#FFE066", "#FFB87A", "#FF9F8B", "#FF9CB0", "#E59FE6",
                "#B89BE8", "#9CB6F5", "#86CFF2", "#7FD9D5", "#8AD9A8",
                "#F7C548", "#F09870", "#E07A6E", "#D9748E", "#C383C6",
                "#9678D0", "#7798E0", "#5DB1E6", "#5CB8B5", "#65BD8A",
            ),
            builtin = true,
        ),
        ColorCollection(
            id = -2L,
            name = "Пастель",
            colors = palette(
                "#F9E79F", "#F7D08A", "#F5B187", "#F0998A", "#EC9090",
                "#E68F9C", "#D88BAE", "#C58CC0", "#B391CD", "#9C95D6",
                "#A8D5B6", "#9FD2C9", "#9CCFD8", "#A0C9E0", "#A8C0E3",
                "#B5BBE3", "#C0B8DE", "#CCB5D3", "#D6B3C5", "#DBB3B6",
                "#C7E1A1", "#B7DAA3", "#A8D2AE", "#9CCABD", "#94C0CB",
                "#94B5D2", "#9DA9D1", "#B0A0CB", "#C19BBE", "#CC9AAB",
            ),
            builtin = true,
        ),
        ColorCollection(
            id = -3L,
            name = "Персонажи",
            colors = palette(
                "#F4C9B5", "#F0D4BC", "#E8B89A", "#D89C7E", "#C0805F",
                "#9C5F3F", "#74442B", "#4F2C1C", "#311A11", "#1B0F0A",
                "#FBE3D4", "#F8DCC9", "#F2C5A5", "#E2A77F", "#C58557",
                "#A36739", "#7A4824", "#552E14", "#321A0A", "#FFFAF0",
                "#E8C7C1", "#D49C9C", "#B97070", "#8C4F4F", "#5C3030",
                "#C7BFB1", "#9C9484", "#6E6757", "#A0B0C4", "#5A6878",
            ),
            builtin = true,
        ),
        ColorCollection(
            id = -4L,
            name = "Пейзаж",
            colors = palette(
                "#AEDCF0", "#7FBFE1", "#56A0CB", "#3D80B0", "#2E608E",
                "#244670", "#1B335A", "#142545", "#0D1830", "#070D1E",
                "#CFE3B0", "#A8CF8C", "#7FB36A", "#5C9651", "#3F7A3F",
                "#2E5E33", "#214728", "#16321C", "#5C7B4A", "#3F5732",
                "#F4D58D", "#E3B26E", "#D08A4A", "#B5642E", "#923F1C",
                "#6E2A14", "#8B5A2B", "#5C3A1A", "#A38560", "#6F5B3D",
            ),
            builtin = true,
        ),
        ColorCollection(
            id = -5L,
            name = "Анимация",
            colors = palette(
                "#111318", "#3B414A", "#7B8491", "#B8C0CC", "#FFFFFF",
                "#4A90E2", "#35B9D5", "#42B883", "#A5D646", "#F4D44D",
                "#F5A23D", "#F26B4F", "#E6536F", "#E66FAD", "#A36BDB",
                "#6D8CFF", "#64D4E8", "#76D7A6", "#D8EA72", "#FFE58A",
                "#245EA8", "#167E98", "#20794F", "#6B8F20", "#B58B08",
                "#B65A12", "#B53832", "#A62E58", "#A23E86", "#633B9B",
            ),
            builtin = true,
        ),
    )
}
