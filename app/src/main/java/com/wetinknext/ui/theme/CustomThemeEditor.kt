package com.wetinknext.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.engine.core.CanvasBackdropMode
import kotlin.random.Random

private enum class ThemeColorSlot(val label: String) {
    AppBackground("Рабочая область"), CanvasBackground("За холстом"), Grid("Сетка"),
    Panel("Панели"), Accent("Акцент"), Text("Текст"), Danger("Удаление")
}

@Composable
fun CustomThemeEditor(
    colors: CustomThemeColors,
    theme: AppTheme,
    backdropMode: CanvasBackdropMode,
    onChange: (CustomThemeColors) -> Unit,
    onBackdropModeChange: (CanvasBackdropMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var slot by remember { mutableStateOf(ThemeColorSlot.Accent) }
    val current = colors.color(slot)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Своя тема", color = theme.textPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text("Случайная", color = theme.textSecondary, fontSize = 12.sp,
                modifier = Modifier.clickable { onChange(randomThemeColors()) }.padding(6.dp))
            Text("Готово", color = theme.accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp))
        }
        ThemeLivePreview(colors)
        Text("Фон за холстом", color = theme.textSecondary, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                CanvasBackdropMode.GRID to "Сетка",
                CanvasBackdropMode.CHECKERBOARD to "Клетки",
                CanvasBackdropMode.SOLID to "Сплошной",
            ).forEach { (mode, label) ->
                val selected = mode == backdropMode
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (selected) theme.accent.copy(alpha = .16f) else theme.panelInset)
                        .border(1.dp, if (selected) theme.accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onBackdropModeChange(mode) }.padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = theme.textPrimary, fontSize = 10.sp, maxLines = 1) }
            }
        }
        ThemeColorSlot.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    val active = item == slot
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (active) theme.accent.copy(alpha = .14f) else theme.panelInset)
                            .border(1.dp, if (active) theme.accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { slot = item }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(colors.color(item))).border(1.dp, theme.panelStroke, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(item.label, color = theme.textPrimary, fontSize = 11.sp, maxLines = 1)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        ColorControls(slot.label, current, theme) { onChange(colors.withColor(slot, it)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Сбросить", color = theme.textSecondary, modifier = Modifier.weight(1f).clickable { onChange(CustomThemeColors()) }.padding(8.dp))
            Text("Применяется сразу", color = theme.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable private fun ColorControls(label: String, color: Int, theme: AppTheme, update: (Int) -> Unit) {
    var hex by remember(color) { mutableStateOf("%06X".format(color and 0xFFFFFF)) }
    val r = color ushr 16 and 0xFF; val g = color ushr 8 and 0xFF; val b = color and 0xFF
    val hsv = FloatArray(3).also { AndroidColor.RGBToHSV(r, g, b, it) }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(theme.panelInset).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Цвет: $label", color = theme.textPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        BasicTextField(hex, { raw ->
            val clean = raw.removePrefix("#").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6).uppercase()
            hex = clean; clean.toIntOrNull(16)?.takeIf { clean.length == 6 }?.let { update(0xFF000000.toInt() or it) }
        }, textStyle = TextStyle(color = theme.textPrimary, fontSize = 14.sp), cursorBrush = SolidColor(theme.accent),
            decorationBox = { inner -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(theme.panelBg).padding(8.dp)) { Text("#", color = theme.textSecondary); inner() } })
        Channel("R", r, Color.Red, theme) { update(argb(it, g, b)) }
        Channel("G", g, Color(0xFF38C878), theme) { update(argb(r, it, b)) }
        Channel("B", b, Color(0xFF568DFF), theme) { update(argb(r, g, it)) }
        PercentageChannel("Н", hsv[1], theme.accent, theme) { saturation ->
            hsv[1] = saturation
            update(AndroidColor.HSVToColor(hsv))
        }
    }
}

@Composable private fun Channel(name: String, value: Int, color: Color, theme: AppTheme, update: (Int) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = theme.textSecondary, modifier = Modifier.width(18.dp), fontSize = 11.sp)
        Slider(value.toFloat(), { update(it.toInt()) }, valueRange = 0f..255f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = theme.panelBg))
        Text(value.toString(), color = theme.textSecondary, modifier = Modifier.width(28.dp), fontSize = 11.sp)
    }

@Composable private fun PercentageChannel(name: String, value: Float, color: Color, theme: AppTheme, update: (Float) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = theme.textSecondary, modifier = Modifier.width(18.dp), fontSize = 11.sp)
        Slider(value, update, valueRange = 0f..1f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = theme.panelBg))
        Text("${(value * 100).toInt()}%", color = theme.textSecondary, modifier = Modifier.width(28.dp), fontSize = 11.sp)
    }

@Composable private fun ThemeLivePreview(colors: CustomThemeColors) =
    Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(colors.appBackground)).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(7.dp)).background(Color(colors.panel)))
        Spacer(Modifier.width(9.dp)); Column { Box(Modifier.width(112.dp).height(7.dp).clip(CircleShape).background(Color(colors.text))); Spacer(Modifier.height(6.dp)); Box(Modifier.width(72.dp).height(5.dp).clip(CircleShape).background(Color(colors.text).copy(alpha = .5f))) }
        Spacer(Modifier.weight(1f)); Box(Modifier.size(27.dp).clip(CircleShape).background(Color(colors.accent)))
    }

private fun CustomThemeColors.color(slot: ThemeColorSlot) = when (slot) { ThemeColorSlot.AppBackground -> appBackground; ThemeColorSlot.CanvasBackground -> canvasBackground; ThemeColorSlot.Grid -> grid; ThemeColorSlot.Panel -> panel; ThemeColorSlot.Accent -> accent; ThemeColorSlot.Text -> text; ThemeColorSlot.Danger -> danger }
private fun CustomThemeColors.withColor(slot: ThemeColorSlot, value: Int) = when (slot) { ThemeColorSlot.AppBackground -> copy(appBackground = value); ThemeColorSlot.CanvasBackground -> copy(canvasBackground = value); ThemeColorSlot.Grid -> copy(grid = value); ThemeColorSlot.Panel -> copy(panel = value); ThemeColorSlot.Accent -> copy(accent = value); ThemeColorSlot.Text -> copy(text = value); ThemeColorSlot.Danger -> copy(danger = value) }
private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/** Creates a coherent palette rather than seven unrelated random colours. */
private fun randomThemeColors(): CustomThemeColors {
    val hue = Random.nextInt(360).toFloat()
    val dark = Random.nextBoolean()
    fun hsv(h: Float, s: Float, v: Float) = AndroidColor.HSVToColor(floatArrayOf((h + 360f) % 360f, s, v))
    val baseValue = if (dark) .12f else .94f
    val panelValue = if (dark) .18f else .99f
    val textValue = if (dark) .94f else .16f
    return CustomThemeColors(
        appBackground = hsv(hue, .22f, baseValue),
        canvasBackground = hsv(hue, .16f, if (dark) .09f else .89f),
        grid = hsv(hue, .28f, if (dark) .27f else .78f),
        panel = hsv(hue, .24f, panelValue),
        accent = hsv(hue + 28f, .72f, .92f),
        text = hsv(hue, .12f, textValue),
        danger = hsv(2f, .72f, .92f),
    )
}
