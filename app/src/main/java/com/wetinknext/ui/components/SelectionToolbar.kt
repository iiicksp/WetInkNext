package com.wetinknext.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.ui.theme.AppTheme

enum class SelectionShapeUi {
    FREEHAND,
    RECTANGLE,
    ELLIPSE,
}

@Composable
fun SelectionToolbar(
    theme: AppTheme,
    currentShape: SelectionShapeUi,
    onShapeChange: (SelectionShapeUi) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onResetSelection: () -> Unit = {},
    onDeleteSelection: () -> Unit = {},
) {
    val panelBorder = BorderStroke(1.dp, theme.panelStroke)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Row 1 — shape modes
        Surface(
            color = theme.panelBg.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            border = panelBorder,
        ) {
            Row(
                modifier = Modifier
                    .width(340.dp)
                    .height(32.dp)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shapes = listOf(
                    SelectionShapeUi.FREEHAND to "От руки",
                    SelectionShapeUi.RECTANGLE to "Прямоугольник",
                    SelectionShapeUi.ELLIPSE to "Эллипс",
                )
                shapes.forEach { (shape, label) ->
                    val isSelected = currentShape == shape
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) theme.accent.copy(alpha = 0.4f) else Color.Transparent)
                            .clickable { onShapeChange(shape) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White.copy(alpha = 0.4f) else theme.textPrimary.copy(alpha = 0.4f),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Row 2 — Action icons (mostly disabled for now)
        Surface(
            color = theme.panelBg.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            border = panelBorder,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionIconButton(Icons.Default.Add, "Добавить", theme, isSelected = true, enabled = false)
                SelectionIconButton(Icons.Default.Delete, "Удалить", theme, enabled = false)
                SelectionIconButton(Icons.AutoMirrored.Filled.CallMerge, "Пересечь", theme, enabled = false)
                SelectionIconButton(Icons.Default.Flip, "Инверсия", theme, enabled = false)
                SelectionIconButton(Icons.Default.FormatColorFill, "Залить", theme, enabled = false)
                SelectionIconButton(Icons.Default.ContentCopy, "Копировать", theme, enabled = false)
                SelectionIconButton(Icons.Default.ContentCut, "Вырезать", theme, enabled = false)
                SelectionIconButton(Icons.Default.AddToPhotos, "Вставить", theme, enabled = false)
            }
        }

        // Row 3 — Reset / Delete / Done
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionRoundButton(Icons.Default.Refresh, "Сбросить", theme, onClick = onResetSelection)
            SelectionRoundButton(Icons.Default.Delete, "Удалить", theme, onClick = onDeleteSelection)
            Surface(
                color = theme.accent,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onDone() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, "Готово", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SelectionIconButton(
    icon: ImageVector,
    description: String,
    theme: AppTheme,
    isSelected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected && enabled) theme.accent.copy(alpha = 0.30f) else Color.Transparent)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (!enabled) theme.iconInactive.copy(alpha = 0.5f) else if (isSelected) theme.accent else theme.textPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SelectionRoundButton(
    icon: ImageVector,
    description: String,
    theme: AppTheme,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    Surface(
        color = if (enabled) theme.panelBg.copy(alpha = 0.9f) else theme.panelBg.copy(alpha = 0.4f),
        shape = CircleShape,
        border = BorderStroke(1.dp, if (enabled) theme.panelStroke else theme.panelStroke.copy(alpha = 0.4f)),
        modifier = Modifier.size(48.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, 
                description, 
                tint = if (enabled) theme.textPrimary else theme.textPrimary.copy(alpha = 0.4f)
            )
        }
    }
}
