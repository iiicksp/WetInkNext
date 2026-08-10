package com.wetinknext.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.engine.brush.BrushLibrary
import com.wetinknext.engine.brush.BrushPreset
import com.wetinknext.engine.brush.BrushUiCategory
import com.wetinknext.ui.theme.AppTheme

private val BRUSH_SWIPE_REVEAL_WIDTH = 88.dp

@Composable
fun BrushPanel(
    currentBrush: BrushPreset,
    onBrushSelect: (BrushPreset) -> Unit,
    onBrushStudioOpen: (BrushPreset) -> Unit,
    theme: AppTheme,
    onDismiss: () -> Unit,
    onDuplicate: (BrushPreset) -> Unit,
    onDelete: (BrushPreset) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<BrushUiCategory> = BrushLibrary.allCategories,
    previews: Map<String, ImageBitmap> = emptyMap(),
    previewKey: (BrushPreset) -> String = { it.id },
    onRequestPreview: (BrushPreset) -> Unit = {},
    title: String = "Кисти",
) {
    fun categoryIndexForCurrentBrush(): Int {
        return categories.indexOfFirst { category -> category.brushes.any { it.id == currentBrush.id } }
            .takeIf { it >= 0 }
            ?: 0
    }
    var selectedCategoryIndex by remember(categories, currentBrush.id) {
        mutableIntStateOf(categoryIndexForCurrentBrush())
    }
    LaunchedEffect(categories, currentBrush.id) {
        selectedCategoryIndex = categoryIndexForCurrentBrush()
    }
    val selectedCategory = categories.getOrNull(selectedCategoryIndex)

    PanelSurface(
        modifier = modifier
            .width(420.dp)
            .height(560.dp),
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(70.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                    .background(theme.panelInset)
                    .drawBehind {
                        drawLine(
                            color = theme.panelStroke,
                            start = Offset(size.width, 0f),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                categories.forEachIndexed { index, category ->
                    val isSelected = index == selectedCategoryIndex
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) theme.accent.copy(alpha = 0.3f) else Color.Transparent)
                            .border(
                                if (isSelected) 1.dp else 0.dp,
                                if (isSelected) theme.accentSoft else Color.Transparent,
                                CircleShape,
                            )
                            .clickable { selectedCategoryIndex = index },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            category.icon,
                            contentDescription = category.name,
                            tint = if (isSelected) theme.accent else theme.iconInactive,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = title,
                            color = theme.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        selectedCategory?.let { category ->
                            Text(
                                text = category.name,
                                color = theme.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Закрыть",
                        tint = theme.textSecondary,
                        modifier = Modifier.size(24.dp).clickable { onDismiss() }
                    )
                }

                Spacer(Modifier.height(14.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(selectedCategory?.brushes.orEmpty(), key = { it.id }) { brush ->
                        val isSelected = brush.id == currentBrush.id
                        BrushItem(
                            brush = brush,
                            isSelected = isSelected,
                            theme = theme,
                            preview = previews[previewKey(brush)],
                            onRequestPreview = { onRequestPreview(brush) },
                            onStudioOpen = { onBrushStudioOpen(brush) },
                            onDuplicate = { onDuplicate(brush) },
                            onDelete = { onDelete(brush) },
                            onClick = { 
                                if (isSelected) onBrushStudioOpen(brush) 
                                else onBrushSelect(brush) 
                            },
                        )
                    }

                    if (selectedCategory?.brushes.isNullOrEmpty()) {
                        item {
                            Text(
                                text = "Кисти отсутствуют",
                                color = theme.textSecondary,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrushItem(
    brush: BrushPreset,
    isSelected: Boolean,
    theme: AppTheme,
    preview: ImageBitmap?,
    onRequestPreview: () -> Unit,
    onStudioOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val contentBg = if (isSelected) theme.panelBgSolid else theme.panelInset
    LaunchedEffect(brush.id, brush.settings, preview) {
        if (preview == null) {
            onRequestPreview()
        }
    }

    SwipeRevealRow(
        revealWidth = BRUSH_SWIPE_REVEAL_WIDTH,
        onSwipeRight = onStudioOpen,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.panelInset),
        actions = {
            BrushSwipeActions(
                theme = theme,
                onDuplicate = onDuplicate,
                onDelete = onDelete,
                canDelete = true // Simplified for now
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(contentBg)
                .border(1.dp, if (isSelected) theme.accentSoft else theme.panelStroke, RoundedCornerShape(8.dp))
                .pointerInput(brush.id) {
                    detectTapGestures(
                        onTap = { onClick() },
                    )
                }
                .padding(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrushPreviewStrip(
                    preview = preview,
                    brush = brush,
                    theme = theme,
                    modifier = Modifier
                        .width(142.dp)
                        .height(46.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = brush.settings.name,
                        color = if (isSelected) theme.accent else theme.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = "Размер: ${(brush.settings.baseRadiusPx * 2f).toInt()} px",
                        color = theme.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                if (isSelected) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(theme.accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrushSwipeActions(
    theme: AppTheme,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrushSwipeButton(
            icon = Icons.Default.ContentCopy,
            label = "Дубль",
            color = theme.accent,
            onClick = onDuplicate
        )
        BrushSwipeButton(
            icon = Icons.Default.Delete,
            label = "Удал.",
            color = theme.danger,
            enabled = canDelete,
            onClick = onDelete
        )
    }
}

@Composable
private fun BrushSwipeButton(
    icon: ImageVector,
    label: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .background(if (enabled) color else color.copy(alpha = 0.3f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BrushPreviewStrip(
    preview: ImageBitmap?,
    brush: BrushPreset,
    theme: AppTheme,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White)
            .border(1.dp, theme.panelStroke, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        PreviewCheckerboard(Modifier.fillMaxSize(), cellSize = 7f)
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    color = theme.accent.copy(alpha = brush.settings.opacity.coerceIn(0.25f, 1f)),
                    start = Offset(size.width * 0.12f, size.height * 0.5f),
                    end = Offset(size.width * 0.88f, size.height * 0.5f),
                    strokeWidth = (brush.settings.baseRadiusPx * 2f).coerceIn(2f, 12f),
                )
            }
        }
    }
}

@Composable
private fun PreviewCheckerboard(
    modifier: Modifier = Modifier,
    cellSize: Float,
) {
    Canvas(modifier) {
        val light = Color(0xFFE9E9E9)
        val dark = Color(0xFFD0D0D0)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = 0
            while (x < size.width) {
                drawRect(
                    color = if ((row + col) % 2 == 0) light else dark,
                    topLeft = Offset(x, y),
                    size = Size(cellSize, cellSize),
                )
                x += cellSize
                col += 1
            }
            y += cellSize
            row += 1
        }
    }
}
