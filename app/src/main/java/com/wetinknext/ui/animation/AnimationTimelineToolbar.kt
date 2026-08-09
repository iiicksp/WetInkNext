package com.wetinknext.ui.animation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wetinknext.domain.animation.AnimationDocument
import com.wetinknext.domain.animation.AnimationFrame
import com.wetinknext.domain.animation.AnimationFrameRole
import com.wetinknext.ui.theme.AppTheme
import kotlin.math.roundToInt

data class FrameThumbnailLayer(
    val image: ImageBitmap,
    val opacity: Float,
)

@Composable
fun AnimationTimelineToolbar(
    theme: AppTheme,
    document: AnimationDocument,
    currentFrameId: Long,
    frameThumbnails: Map<Long, List<FrameThumbnailLayer>> = emptyMap(),
    onFrameSelect: (Long) -> Unit,
    onFrameMove: (frameId: Long, direction: Int) -> Unit,
    onAddFrame: () -> Unit,
    onDuplicateFrame: (Long) -> Unit,
    onDeleteFrame: (Long) -> Unit,
    onHoldChange: (Long, Int) -> Unit,
    onRoleToggle: (Long, AnimationFrameRole) -> Unit,
    onAssembleLayers: () -> Unit,
    onLayersClick: () -> Unit,
    onClose: () -> Unit,
    onSettingsClick: () -> Unit = {},
    isPlaying: Boolean = false,
    onPlayToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val frames = document.frames
    var showContextMenuForId by remember { mutableStateOf<Long?>(null) }

    // Auto-scroll to current frame
    LaunchedEffect(currentFrameId) {
        val index = frames.indexOfFirst { it.id == currentFrameId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 820.dp)
                .height(86.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(theme.panelBg.copy(alpha = 0.94f))
                .border(1.2.dp, theme.panelStroke, RoundedCornerShape(28.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play toggle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) theme.accent.copy(alpha = 0.4f) else theme.accent)
                        .clickable { onPlayToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 
                        null, 
                        tint = Color.White
                    )
                }
                Text(if (isPlaying) "Пауза" else "Пуск", color = theme.textSecondary, fontSize = 9.sp)
            }

            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(1.dp).fillMaxHeight(0.6f).background(theme.panelStroke))
            Spacer(Modifier.width(10.dp))

            // Frames timeline
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(frames, key = { _, f -> f.id }) { _, frame ->
                    TimelineFrameItem(
                        frame = frame,
                        isSelected = frame.id == currentFrameId,
                        thumbnailLayers = frameThumbnails[frame.id].orEmpty(),
                        theme = theme,
                        canMoveLeft = frames.indexOf(frame) > 0,
                        canMoveRight = frames.indexOf(frame) < frames.lastIndex,
                        onMove = { direction -> onFrameMove(frame.id, direction) },
                        onClick = {
                            if (currentFrameId == frame.id) {
                                showContextMenuForId = frame.id
                            } else {
                                onFrameSelect(frame.id)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(1.dp).fillMaxHeight(0.6f).background(theme.panelStroke))
            Spacer(Modifier.width(10.dp))

            // Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimelineActionIcon(Icons.Default.Add, "Кадр", theme, onClick = onAddFrame)
                TimelineActionIcon(Icons.Default.Layers, "Собрать", theme, onClick = onAssembleLayers)
                TimelineActionIcon(Icons.Default.ContentCopy, "Копия", theme, onClick = { onDuplicateFrame(currentFrameId) })
                TimelineActionIcon(Icons.Default.Delete, "Удалить", theme, enabled = frames.size > 1, onClick = { onDeleteFrame(currentFrameId) })
                TimelineActionIcon(Icons.Default.Settings, "Настр.", theme, onClick = onSettingsClick)
                TimelineActionIcon(Icons.Default.Layers, "Слои", theme, onClick = onLayersClick)
                TimelineActionIcon(Icons.Default.Close, "Закрыть", theme, onClick = onClose)
            }
        }

        // Context Menu
        showContextMenuForId?.let { frameId ->
            val frame = frames.find { it.id == frameId }
            if (frame != null) {
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(0, -220),
                    onDismissRequest = { showContextMenuForId = null }
                ) {
                    FrameContextMenu(
                        frame = frame,
                        theme = theme,
                        onDuplicate = { onDuplicateFrame(frameId); showContextMenuForId = null },
                        onDelete = { onDeleteFrame(frameId); showContextMenuForId = null },
                        onHoldChange = { onHoldChange(frameId, it) },
                        onRoleToggle = { onRoleToggle(frameId, it); showContextMenuForId = null },
                        canDelete = frames.size > 1
                    )
                }
            }
        }
    }
}

@Composable
private fun FrameContextMenu(
    frame: AnimationFrame,
    theme: AppTheme,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onHoldChange: (Int) -> Unit,
    onRoleToggle: (AnimationFrameRole) -> Unit,
    canDelete: Boolean
) {
    Surface(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, theme.panelStroke, RoundedCornerShape(16.dp)),
        color = theme.panelBg,
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            ContextMenuItem("Дублировать", Icons.Default.ContentCopy, theme, onClick = onDuplicate)
            ContextMenuItem("Удалить", Icons.Default.Delete, theme, enabled = canDelete, onClick = onDelete)
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = theme.panelStroke)
            
            Text("Задержка: ${frame.holdFrames}", color = theme.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Slider(
                value = frame.holdFrames.toFloat(),
                onValueChange = { onHoldChange(it.toInt()) },
                valueRange = 1f..120f,
                colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = theme.panelStroke)

            val isBg = frame.role == AnimationFrameRole.BACKGROUND
            val isFg = frame.role == AnimationFrameRole.FOREGROUND
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onRoleToggle(if (isBg) AnimationFrameRole.NORMAL else AnimationFrameRole.BACKGROUND) }.padding(8.dp)) {
                Checkbox(checked = isBg, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = theme.accent))
                Spacer(Modifier.width(8.dp))
                Text("Фоновый слой", color = theme.textPrimary, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onRoleToggle(if (isFg) AnimationFrameRole.NORMAL else AnimationFrameRole.FOREGROUND) }.padding(8.dp)) {
                Checkbox(checked = isFg, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = theme.accent))
                Spacer(Modifier.width(8.dp))
                Text("Передний план", color = theme.textPrimary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    label: String,
    icon: ImageVector,
    theme: AppTheme,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(8.dp)
            .alphaIfDisabled(!enabled),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) theme.textPrimary else theme.textSecondary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (enabled) theme.textPrimary else theme.textSecondary.copy(alpha = 0.5f), fontSize = 14.sp)
    }
}

@Composable
private fun TimelineFrameItem(
    frame: AnimationFrame,
    isSelected: Boolean,
    thumbnailLayers: List<FrameThumbnailLayer>,
    theme: AppTheme,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onMove: (Int) -> Unit,
    onClick: () -> Unit
) {
    val moveThresholdPx = with(LocalDensity.current) { 44.dp.toPx() }
    var accumulatedDragX by remember(frame.id) { mutableFloatStateOf(0f) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pointerInput(frame.id, canMoveLeft, canMoveRight) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { accumulatedDragX = 0f },
                    onDrag = { change, dragAmount ->
                        accumulatedDragX += dragAmount.x
                        val direction = when {
                            accumulatedDragX <= -moveThresholdPx && canMoveLeft -> -1
                            accumulatedDragX >= moveThresholdPx && canMoveRight -> 1
                            else -> 0
                        }
                        if (direction != 0) {
                            change.consume()
                            onMove(direction)
                            accumulatedDragX = 0f
                        }
                    }
                )
            }
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(54.dp, 40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isSelected) theme.accent.copy(alpha = 0.2f) else theme.panelInsetSoft)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) theme.accent else theme.panelStroke,
                    shape = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailLayers.isNotEmpty()) {
                thumbnailLayers.forEach { layer ->
                    Image(
                        bitmap = layer.image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .graphicsLayer(alpha = layer.opacity.coerceIn(0f, 1f)),
                    )
                }
            } else {
                Icon(Icons.Default.Image, null, tint = theme.textSecondary.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
            }
        }
        if (frame.holdFrames > 1) {
            Text("+${frame.holdFrames - 1}", color = theme.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        } else {
            Spacer(Modifier.height(11.dp))
        }
    }
}

@Composable
private fun TimelineActionIcon(
    icon: ImageVector,
    label: String,
    theme: AppTheme,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .alphaIfDisabled(!enabled)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Icon(icon, null, tint = if (enabled) theme.textPrimary else theme.textSecondary.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
        Text(label, color = theme.textSecondary, fontSize = 9.sp)
    }
}

private fun Modifier.alphaIfDisabled(disabled: Boolean): Modifier = if (disabled) this.then(Modifier.graphicsLayer { alpha = 0.5f }) else this
