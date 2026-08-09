package com.wetinknext.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.ui.theme.AppTheme
import kotlin.math.max

private data class CanvasPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val description: String,
)

private val QuickPresets = listOf(
    CanvasPreset("Экран", 2800, 1840, "2800 x 1840 px"),
    CanvasPreset("Квадрат", 2048, 2048, "2048 x 2048 px"),
    CanvasPreset("4K", 4096, 2160, "4096 x 2160 px"),
    CanvasPreset("A4", 2480, 3508, "210 x 297 мм"),
    CanvasPreset("1080P", 1920, 1080, "1920 x 1080 px"),
    CanvasPreset("9:16", 1080, 1920, "1080 x 1920 px"),
)

private val DpiOptions = listOf(72, 144, 300, 350, 600, 1200)
private val ColorProfiles = listOf("sRGB", "Display P3")

@Composable
fun NewCanvasDialog(
    theme: AppTheme,
    defaultWidth: Int,
    defaultHeight: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, width: Int, height: Int, dpi: Int, colorProfile: String) -> Unit,
) {
    val initialIndex = QuickPresets.indexOfFirst {
        it.width == defaultWidth && it.height == defaultHeight
    }
    var selected by remember { mutableIntStateOf(initialIndex) }
    var customW by remember { mutableStateOf(defaultWidth.toString()) }
    var customH by remember { mutableStateOf(defaultHeight.toString()) }
    var name by remember { mutableStateOf("Новый холст") }
    var dpi by remember { mutableIntStateOf(300) }
    var colorProfile by remember { mutableStateOf("sRGB") }
    var dpiExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }
    val isCustom = selected !in QuickPresets.indices

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Новый холст", color = theme.textPrimary, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text("Имя") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = theme.accent,
                        unfocusedBorderColor = theme.panelStroke,
                        focusedLabelColor = theme.accent,
                        unfocusedLabelColor = theme.textSecondary
                    )
                )
                Spacer(Modifier.height(12.dp))
                Text("Размеры", color = theme.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    QuickPresets.forEachIndexed { index, preset ->
                        QuickPresetCard(
                            preset = preset,
                            selected = selected == index,
                            theme = theme,
                            onClick = {
                                selected = index
                                customW = preset.width.toString()
                                customH = preset.height.toString()
                            },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { selected = -1 }) {
                    Text(
                        "Свой размер",
                        color = if (isCustom) theme.accent else theme.textSecondary,
                        fontWeight = if (isCustom) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp,
                    )
                }

                if (isCustom) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customW,
                            onValueChange = { v -> customW = v.filter { it.isDigit() }.take(5) },
                            label = { Text("Ширина") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = theme.accent,
                                unfocusedBorderColor = theme.panelStroke,
                                focusedLabelColor = theme.accent,
                                unfocusedLabelColor = theme.textSecondary
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("x", color = theme.textSecondary)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = customH,
                            onValueChange = { v -> customH = v.filter { it.isDigit() }.take(5) },
                            label = { Text("Высота") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = theme.accent,
                                unfocusedBorderColor = theme.panelStroke,
                                focusedLabelColor = theme.accent,
                                unfocusedLabelColor = theme.textSecondary
                            )
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                OptionRow(label = "DPI", value = "$dpi dpi", theme = theme, expanded = dpiExpanded, onExpand = { dpiExpanded = true }) {
                    DpiOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("$option dpi") },
                            onClick = {
                                dpi = option
                                dpiExpanded = false
                            },
                        )
                    }
                }
                OptionRow(label = "Цветовой профиль", value = colorProfile, theme = theme, expanded = profileExpanded, onExpand = { profileExpanded = true }) {
                    ColorProfiles.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                colorProfile = option
                                profileExpanded = false
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val (w, h) = if (isCustom) {
                        val cw = customW.toIntOrNull()?.coerceIn(MinDimension, MaxDimension) ?: return@TextButton
                        val ch = customH.toIntOrNull()?.coerceIn(MinDimension, MaxDimension) ?: return@TextButton
                        cw to ch
                    } else {
                        val preset = QuickPresets[selected]
                        preset.width to preset.height
                    }
                    onConfirm(name.trim().ifBlank { "Новый холст" }, w, h, dpi, colorProfile)
                },
            ) {
                Text("Создать", color = theme.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = theme.textSecondary)
            }
        },
        containerColor = theme.panelBgSolid,
    )
}

@Composable
private fun OptionRow(
    label: String,
    value: String,
    theme: AppTheme,
    expanded: Boolean,
    onExpand: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = theme.textPrimary, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = onExpand) {
                Text(value, color = theme.accent, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(
                expanded = expanded, 
                onDismissRequest = { /* onExpand is handled by parent */ },
                modifier = Modifier.background(theme.panelBg)
            ) {
                menu()
            }
        }
    }
}

@Composable
private fun QuickPresetCard(
    preset: CanvasPreset,
    selected: Boolean,
    theme: AppTheme,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) theme.accent.copy(alpha = 0.15f) else theme.panelBg)
            .border(1.5.dp, if (selected) theme.accent else theme.panelStroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    ) {
        PresetSwatch(preset.width, preset.height, selected, theme)
        Spacer(Modifier.height(8.dp))
        Text(preset.label, color = theme.textPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text(preset.description, color = theme.textSecondary, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun PresetSwatch(width: Int, height: Int, selected: Boolean, theme: AppTheme) {
    val scale = 40f / max(width.coerceAtLeast(1), height.coerceAtLeast(1))
    val previewW = (width * scale).coerceAtLeast(8f)
    val previewH = (height * scale).coerceAtLeast(8f)
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(previewW.dp)
                .height(previewH.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) theme.accent else Color(0xFF5A5A60))
                .border(1.dp, if (selected) Color.White else theme.panelStroke, RoundedCornerShape(4.dp)),
        )
    }
}

private const val MinDimension = 256
private const val MaxDimension = 8192
