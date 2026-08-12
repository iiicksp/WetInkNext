package com.wetinknext.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.ui.state.LayerItem
import com.wetinknext.ui.state.LayerState
import com.wetinknext.ui.theme.AppTheme
import com.wetinknext.ui.thumbnail.LayerThumbnail
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val PANEL_WIDTH = 320.dp

@Composable
fun LayersPanel(
    state: LayerState,
    theme: AppTheme,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onAddLayer: () -> Unit,
    onSelectLayer: (Long) -> Unit,
    onVisibleChange: (Long, Boolean) -> Unit,
    onOpacityChange: (Long, Float) -> Unit,
    onDuplicateLayer: (Long) -> Unit,
    onRemoveLayer: (Long) -> Unit,
) {
    PanelSurface(
        modifier = modifier.width(PANEL_WIDTH),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Слои",
                    color = theme.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.weight(1f))
                HeaderIconButton(
                    theme = theme,
                    icon = Icons.Default.Add,
                    description = "Добавить слой",
                    onClick = onAddLayer,
                )
                HeaderIconButton(
                    theme = theme,
                    icon = Icons.Default.Close,
                    description = "Закрыть",
                    onClick = onDismiss,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    items = state.layers.asReversed(),
                    key = { it.id },
                ) { layerItem ->
                    LayerRow(
                        layer = layerItem,
                        theme = theme,
                        active = layerItem.id == state.activeLayerId,
                        onSelect = { onSelectLayer(layerItem.id) },
                        onVisibleChange = { onVisibleChange(layerItem.id, it) },
                        onOpacityChange = { onOpacityChange(layerItem.id, it) },
                        onDuplicate = { onDuplicateLayer(layerItem.id) },
                        onDelete = { onRemoveLayer(layerItem.id) },
                        canDelete = state.layers.size > 1 && !layerItem.locked
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: LayerItem,
    theme: AppTheme,
    active: Boolean,
    onSelect: () -> Unit,
    onVisibleChange: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
) {
    val cardBg = if (active) theme.panelBgSolid else theme.panelBg
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(
                width = if (active) 1.2.dp else 0.dp,
                color = if (active) theme.accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onSelect() }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(
                theme = theme,
                icon = if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                description = "Видимость",
                onClick = { onVisibleChange(!layer.visible) },
                tint = if (layer.visible) (if (active) theme.accent else theme.textPrimary) else theme.iconInactive,
            )
            
            Spacer(Modifier.width(8.dp))

            LayerThumbnail(
                layerId = layer.id,
                path = layer.thumbnailPath,
                thumbnailVersion = layer.thumbnailVersion,
                modifier = Modifier.height(36.dp),
            )

            Spacer(Modifier.width(8.dp))

            Text(
                layer.name,
                modifier = Modifier.weight(1f),
                color = theme.textPrimary,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (canDelete) {
                HeaderIconButton(
                    theme = theme,
                    icon = Icons.Default.ContentCopy,
                    description = "Дублировать слой",
                    onClick = onDuplicate,
                )
                Spacer(Modifier.width(4.dp))
                HeaderIconButton(
                    theme = theme,
                    icon = Icons.Default.Delete,
                    description = "Удалить",
                    onClick = onDelete,
                    tint = theme.danger
                )
            }
        }

        if (active) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Непрозрачность", color = theme.textSecondary, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(layer.opacity * 100f).roundToInt()}%",
                        color = theme.textSecondary,
                        fontSize = 11.sp,
                    )
                }
                Slider(
                    value = layer.opacity.coerceIn(0f, 1f),
                    onValueChange = onOpacityChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = theme.accent,
                        activeTrackColor = theme.accent,
                        inactiveTrackColor = theme.accentMuted,
                    ),
                )
            }
        }
    }
}

/** Small, non-blocking thumbnail loader for the persisted layer WebP preview. */
@Composable
private fun LegacyLayerThumbnail(
    layer: LayerItem,
    theme: AppTheme,
    size: Dp = 38.dp,
) {
    var preview by remember(layer.thumbnailPath, layer.thumbnailVersion) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(layer.thumbnailPath, layer.thumbnailVersion) {
        preview = withContext(Dispatchers.IO) {
            layer.thumbnailPath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?.asImageBitmap()
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(7.dp))
            .background(theme.panelInset)
            .border(1.dp, theme.panelStroke, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            androidx.compose.foundation.Image(
                bitmap = checkNotNull(preview),
                contentDescription = "Превью слоя ${layer.name}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = layer.name.take(1).uppercase(),
                color = theme.iconInactive,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HeaderIconButton(
    theme: AppTheme,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = theme.textPrimary,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled) theme.panelInsetSoft else theme.panelInset)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else theme.iconInactive,
            modifier = Modifier.size(18.dp),
        )
    }
}
