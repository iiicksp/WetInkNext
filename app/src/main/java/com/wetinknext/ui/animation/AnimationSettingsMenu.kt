package com.wetinknext.ui.animation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.domain.animation.AnimationDocument
import com.wetinknext.domain.animation.AnimationPlaybackMode
import com.wetinknext.domain.animation.OnionSkinSettings
import com.wetinknext.ui.theme.AppTheme

@Composable
fun AnimationSettingsMenu(
    theme: AppTheme,
    document: AnimationDocument,
    onDocumentChange: (AnimationDocument) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(280.dp)
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(theme.panelBg.copy(alpha = 0.98f))
            .border(1.dp, theme.panelStroke, RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Настройки анимации", color = theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

            // Playback Mode
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Режим", color = theme.textSecondary, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlaybackModeButton("Цикл", AnimationPlaybackMode.LOOP, document.playbackMode, theme) {
                        onDocumentChange(document.copy(playbackMode = it))
                    }
                    PlaybackModeButton("Туда-сюда", AnimationPlaybackMode.PING_PONG, document.playbackMode, theme) {
                        onDocumentChange(document.copy(playbackMode = it))
                    }
                    PlaybackModeButton("Один раз", AnimationPlaybackMode.ONE_SHOT, document.playbackMode, theme) {
                        onDocumentChange(document.copy(playbackMode = it))
                    }
                }
            }

            // FPS
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Скорость (FPS)", color = theme.textSecondary, fontSize = 12.sp)
                    Text("${document.framesPerSecond}", color = theme.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = document.framesPerSecond.toFloat(),
                    onValueChange = { onDocumentChange(document.copy(framesPerSecond = it.toInt())) },
                    valueRange = 1f..60f,
                    colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
                )
            }

            HorizontalDivider(color = theme.panelStroke)

            // Onion Skin Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Калька (Onion Skin)", color = theme.textPrimary, fontSize = 14.sp)
                Switch(
                    checked = document.onionSkin.enabled,
                    onCheckedChange = { onDocumentChange(document.copy(onionSkin = document.onionSkin.copy(enabled = it))) },
                    colors = SwitchDefaults.colors(checkedThumbColor = theme.accent, checkedTrackColor = theme.accent.copy(alpha = 0.5f))
                )
            }

            if (document.onionSkin.enabled) {
                OnionCountSlider("Предыдущие", document.onionSkin.previousFrames, theme) {
                    onDocumentChange(document.copy(onionSkin = document.onionSkin.copy(previousFrames = it)))
                }
                OnionColorPicker(
                    label = "Цвет предыдущих",
                    selectedArgb = document.onionSkin.previousColorArgb,
                    theme = theme,
                ) { color ->
                    onDocumentChange(document.copy(onionSkin = document.onionSkin.copy(previousColorArgb = color.toArgb())))
                }
                OnionCountSlider("Следующие", document.onionSkin.nextFrames, theme) {
                    onDocumentChange(document.copy(onionSkin = document.onionSkin.copy(nextFrames = it)))
                }
                OnionColorPicker(
                    label = "Цвет следующих",
                    selectedArgb = document.onionSkin.nextColorArgb,
                    theme = theme,
                ) { color ->
                    onDocumentChange(document.copy(onionSkin = document.onionSkin.copy(nextColorArgb = color.toArgb())))
                }
                
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Прозрачность", color = theme.textSecondary, fontSize = 12.sp)
                    Slider(
                        value = document.onionSkin.opacity,
                        onValueChange = { onDocumentChange(document.copy(onionSkin = document.onionSkin.copy(opacity = it))) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.PlaybackModeButton(
    label: String,
    mode: AnimationPlaybackMode,
    current: AnimationPlaybackMode,
    theme: AppTheme,
    onClick: (AnimationPlaybackMode) -> Unit
) {
    val selected = mode == current
    Box(
        modifier = Modifier
            .weight(1f)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) theme.accent else theme.panelInsetSoft)
            .clickable { onClick(mode) },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else theme.textSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun OnionCountSlider(
    label: String,
    value: Int,
    theme: AppTheme,
    onValueChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = theme.textSecondary, fontSize = 11.sp)
            Text("$value", color = theme.textPrimary, fontSize = 11.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..12f,
            colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
        )
    }
}

@Composable
private fun OnionColorPicker(
    label: String,
    selectedArgb: Int,
    theme: AppTheme,
    onColorChange: (Color) -> Unit,
) {
    val palette = listOf(
        Color(0xFFFF4FA3),
        Color(0xFFFF7A59),
        Color(0xFFFFC857),
        Color(0xFF57D68D),
        Color(0xFF4A8DFF),
        Color(0xFFB079FF),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = theme.textSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            palette.forEach { color ->
                val selected = color.toArgb() == selectedArgb
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Color.White else theme.panelStroke,
                            shape = CircleShape,
                        )
                        .clickable { onColorChange(color) }
                )
            }
        }
    }
}
