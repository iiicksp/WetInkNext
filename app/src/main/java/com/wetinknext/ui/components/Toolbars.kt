package com.wetinknext.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wetinknext.R
import com.wetinknext.ui.theme.AppTheme

@Composable
fun TopToolbar(
    theme: AppTheme,
    currentColor: Color,
    canUndo: Boolean,
    canRedo: Boolean,
    isSelectionActive: Boolean,
    isTransformActive: Boolean,
    isAnimationActive: Boolean,
    isAdjustmentsActive: Boolean = false,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    onSelectionClick: () -> Unit,
    onTransformClick: () -> Unit,
    onAnimationClick: () -> Unit,
    onAdjustmentsClick: () -> Unit = {},
    onSettingsClick: () -> Unit,
    onLayersClick: () -> Unit,
    onColorClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(top = 12.dp, end = 12.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(theme.panelBg.copy(alpha = 0.9f))
            .border(1.2.dp, theme.panelStroke, RoundedCornerShape(25.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ToolbarIcon(Icons.AutoMirrored.Filled.Undo, theme, isSelected = false, enabled = canUndo, onClick = onUndoClick)
        ToolbarIcon(Icons.AutoMirrored.Filled.Redo, theme, isSelected = false, enabled = canRedo, onClick = onRedoClick)
        
        // SelectAll has a visually denser glyph than Fullscreen; its 18 dp drawing size
        // matches Transform's perceived 20 dp footprint while both buttons stay 34 dp.
        ToolbarIcon(Icons.Default.SelectAll, theme, isSelected = isSelectionActive, iconSize = 18.dp, onClick = onSelectionClick)
        ToolbarIcon(Icons.Default.Movie, theme, isSelected = isAnimationActive, onClick = onAnimationClick)
        ToolbarIcon(Icons.Default.Fullscreen, theme, isSelected = isTransformActive, onClick = onTransformClick)
        ToolbarIcon(Icons.Default.Tune, theme, isSelected = isAdjustmentsActive, onClick = onAdjustmentsClick)
        ToolbarIcon(Icons.Default.Layers, theme, onClick = onLayersClick)
        ToolbarIcon(Icons.Default.Settings, theme, contentDescription = "Настройки", onClick = onSettingsClick)
        
        ColorPickerDot(
            color = currentColor,
            theme = theme,
            onClick = onColorClick
        )
    }
}

/** Compact actions placed directly under the left brush toolbar. */
@Composable
fun LeftCanvasActions(
    theme: AppTheme,
    onClearLayerClick: () -> Unit,
    onMirrorClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 12.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CanvasActionButton(
            icon = Icons.Default.DeleteSweep,
            description = "Очистить активный слой",
            theme = theme,
            onClick = onClearLayerClick,
        )
        CanvasActionButton(
            icon = Icons.Default.Flip,
            description = "Зеркальный просмотр холста",
            theme = theme,
            onClick = onMirrorClick,
        )
    }
}

@Composable
private fun CanvasActionButton(
    icon: ImageVector,
    description: String,
    theme: AppTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(theme.panelBg.copy(alpha = 0.9f))
            .border(1.2.dp, theme.panelStroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = theme.textPrimary, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ColorPickerDot(
    color: Color,
    theme: AppTheme,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.5.dp, theme.panelStroke, CircleShape)
            .clickable { onClick() }
    )
}

@Composable
fun SideToolbar(
    theme: AppTheme,
    brushSize: Float,
    brushOpacity: Float,
    isEraser: Boolean,
    onBrushSizeChange: (Float) -> Unit,
    onBrushOpacityChange: (Float) -> Unit,
    onBrushClick: () -> Unit,
    onEraserClick: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(start = 12.dp)
            .width(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(theme.panelBg.copy(alpha = 0.9f))
            .border(1.2.dp, theme.panelStroke, RoundedCornerShape(22.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ToolbarIcon(Icons.Default.Brush, theme, isSelected = !isEraser, onClick = {
            onEraserClick(false)
            onBrushClick()
        })
        ToolbarIcon(ImageVector.vectorResource(R.drawable.ic_eraser), theme, isSelected = isEraser, onClick = { onEraserClick(true) })
        
        Spacer(Modifier.height(4.dp))
        
        VerticalSlider(
            value = brushSize,
            onValueChange = onBrushSizeChange,
            valueRange = 2f..400f,
            theme = theme
        )
        
        VerticalSlider(
            value = brushOpacity,
            onValueChange = onBrushOpacityChange,
            valueRange = 0f..1f,
            theme = theme
        )
    }
}

@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    theme: AppTheme,
    modifier: Modifier = Modifier
) {
    val range = valueRange.endInclusive - valueRange.start
    var trackHeightPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    val norm = ((value - valueRange.start) / range).coerceIn(0f, 1f)

    fun updateFromY(y: Float) {
        val n = (1f - (y / trackHeightPx)).coerceIn(0f, 1f)
        onValueChange(valueRange.start + n * range)
    }

    Box(
        modifier = modifier
            .width(28.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(theme.accentMuted.copy(alpha = 0.15f))
            .border(1.dp, theme.panelStroke, RoundedCornerShape(14.dp))
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(valueRange) {
                detectTapGestures { offset -> updateFromY(offset.y) }
            }
            .pointerInput(valueRange) {
                detectDragGestures { change, _ ->
                    change.consume()
                    updateFromY(change.position.y)
                }
            }
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(norm)
                .background(Brush.verticalGradient(listOf(theme.accentSoft, theme.accent)))
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset {
                    val thumbSizePx = with(density) { 22.dp.toPx() }
                    IntOffset(0, ((1f - norm) * (trackHeightPx - thumbSizePx)).toInt())
                }
                .padding(2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(theme.textPrimary)
                .border(1.dp, theme.panelStroke, CircleShape)
        )
    }
}

@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    theme: AppTheme,
    isSelected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (isSelected) theme.accentMuted.copy(alpha = 0.3f) else Color.Transparent)
            .border(if (isSelected) 1.dp else 0.dp, if (isSelected) theme.accentSoft else Color.Transparent, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (!enabled) theme.iconInactive.copy(alpha = 0.5f) else if (isSelected) theme.accent else theme.textPrimary,
            modifier = Modifier.size(iconSize)
        )
    }
}
