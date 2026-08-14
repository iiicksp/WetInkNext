package com.wetinknext.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.ui.theme.AppTheme

enum class TransformModeUi {
    FREEFORM,
    UNIFORM,
    DISTORT,
    WARP
}

interface TransformActions {
    fun reset()
    fun cancel()
    fun apply()
    fun flipHorizontal()
    fun flipVertical()
    fun setMode(mode: TransformModeUi)
    fun rotate45()
}

@Composable
fun TransformMenuView(
    theme: AppTheme,
    actions: TransformActions,
    currentMode: TransformModeUi,
    modifier: Modifier = Modifier
) {
    val panelBorder = BorderStroke(1.dp, theme.panelStroke)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Row 1: Modes
        Surface(
            color = theme.panelBg.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            border = panelBorder
        ) {
            Row(
                modifier = Modifier
                    .width(340.dp)
                    .height(32.dp)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TransformModeButton("Свободно", TransformModeUi.FREEFORM, currentMode, theme, actions::setMode)
                TransformModeButton("Пропорц.", TransformModeUi.UNIFORM, currentMode, theme, actions::setMode)
                TransformModeButton("Исказить", TransformModeUi.DISTORT, currentMode, theme, actions::setMode)
                TransformModeButton("Деформ.", TransformModeUi.WARP, currentMode, theme, actions::setMode)
            }
        }

        // Row 2: Actions
        Surface(
            color = theme.panelBg.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            border = panelBorder
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionIconButton(Icons.Default.Flip, "Отразить Г", theme) { actions.flipHorizontal() }
                ActionIconButton(Icons.Default.Flip, "Отразить В", theme, iconModifier = Modifier.rotate(90f)) { actions.flipVertical() }
                ActionIconButton(Icons.Default.RotateRight, "Поворот 45", theme) { actions.rotate45() }
                ActionIconButton(Icons.Default.FitScreen, "Вписать", theme) { actions.reset() }
                
                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp), color = theme.panelStroke)

                // Placeholder for interpolation/snapping
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LinkOff, null, tint = theme.textSecondary.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
                }
            }
        }

        // Row 3: Main Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reset
            TransformRoundButton(Icons.Default.Refresh, "Сбросить", theme) { actions.reset() }

            // Cancel
            TransformRoundButton(Icons.Default.Close, "Отмена", theme) { actions.cancel() }

            // Apply
            Surface(
                color = theme.accent,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { actions.apply() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, "Применить", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RowScope.TransformModeButton(
    label: String,
    mode: TransformModeUi,
    currentMode: TransformModeUi,
    theme: AppTheme,
    onClick: (TransformModeUi) -> Unit
) {
    val isSelected = mode == currentMode
    val enabled = true
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) theme.accent.copy(alpha = 0.4f) else Color.Transparent)
            .clickable(enabled = enabled) { onClick(mode) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White.copy(alpha = 0.4f) else theme.textPrimary.copy(alpha = 0.4f),
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    description: String,
    theme: AppTheme,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) theme.textPrimary else theme.textPrimary.copy(alpha = 0.4f),
            modifier = iconModifier.size(24.dp)
        )
    }
}

@Composable
private fun TransformRoundButton(
    icon: ImageVector,
    description: String,
    theme: AppTheme,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        color = if (enabled) theme.panelBg.copy(alpha = 0.9f) else theme.panelBg.copy(alpha = 0.4f),
        shape = CircleShape,
        border = BorderStroke(1.dp, if (enabled) theme.panelStroke else theme.panelStroke.copy(alpha = 0.4f)),
        modifier = Modifier.size(48.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, description, tint = if (enabled) theme.textPrimary else theme.textPrimary.copy(alpha = 0.4f))
        }
    }
}
