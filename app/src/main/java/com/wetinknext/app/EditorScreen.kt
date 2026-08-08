package com.wetinknext.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wetinknext.engine.core.EditorUiState
import com.wetinknext.engine.core.LayerUiModel
import com.wetinknext.engine.core.PaintSurfaceView
import com.wetinknext.ui.theme.WetInkNextPalette

/** P7 shell: Compose reads immutable engine snapshots and posts commands to PaintSurfaceView. */
@Composable
fun EditorScreen() {
    val palette = WetInkNextPalette.default
    var uiState by remember { mutableStateOf(EditorUiState.empty) }
    var surface by remember { mutableStateOf<PaintSurfaceView?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.appBg),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PaintSurfaceView(context).also { view ->
                    view.onEditorStateChange = { uiState = it }
                    surface = view
                    view.requestState()
                }
            },
        )

        EditorTopBar(
            state = uiState,
            palette = palette,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            onUndo = { surface?.undo() },
            onRedo = { surface?.redo() },
        )

        BrushControls(
            state = uiState,
            palette = palette,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            onBrushSizeCommit = { surface?.setBrushSize(it) },
            onBrushOpacityCommit = { surface?.setBrushOpacity(it) },
        )

        LayersPanel(
            state = uiState,
            palette = palette,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .width(300.dp),
            onSelectLayer = { surface?.setActiveLayer(it) },
            onAddLayer = { surface?.addLayer() },
            onDeleteLayer = { surface?.removeLayer(it) },
            onLayerVisible = { id, visible -> surface?.setLayerVisible(id, visible) },
            onLayerOpacity = { id, opacity -> surface?.setLayerOpacity(id, opacity) },
        )
    }
}

@Composable
private fun EditorTopBar(
    state: EditorUiState,
    palette: WetInkNextPalette,
    modifier: Modifier = Modifier,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(palette.panelBg.copy(alpha = 0.94f), RoundedCornerShape(24.dp))
            .border(1.dp, palette.panelStroke, RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onUndo, enabled = state.canUndo) { Text("Undo") }
        TextButton(onClick = onRedo, enabled = state.canRedo) { Text("Redo") }
    }
}

@Composable
private fun BrushControls(
    state: EditorUiState,
    palette: WetInkNextPalette,
    modifier: Modifier = Modifier,
    onBrushSizeCommit: (Float) -> Unit,
    onBrushOpacityCommit: (Float) -> Unit,
) {
    Column(
        modifier = modifier.panel(palette).width(260.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "Кисть", color = palette.textPrimary)
        CommitSlider(
            label = "Размер",
            value = state.brushSizePx,
            range = 2f..400f,
            palette = palette,
            format = { "${it.toInt()} px" },
            onCommit = onBrushSizeCommit,
        )
        CommitSlider(
            label = "Непрозрачность",
            value = state.brushOpacity,
            range = 0f..1f,
            palette = palette,
            format = { "${(it * 100f).toInt()}%" },
            onCommit = onBrushOpacityCommit,
        )
    }
}

@Composable
private fun LayersPanel(
    state: EditorUiState,
    palette: WetInkNextPalette,
    modifier: Modifier = Modifier,
    onSelectLayer: (Long) -> Unit,
    onAddLayer: () -> Unit,
    onDeleteLayer: (Long) -> Unit,
    onLayerVisible: (Long, Boolean) -> Unit,
    onLayerOpacity: (Long, Float) -> Unit,
) {
    Column(
        modifier = modifier.panel(palette).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Слои", color = palette.textPrimary, modifier = Modifier.weight(1f))
            TextButton(onClick = onAddLayer, enabled = state.ready) { Text("Добавить") }
        }

        LazyColumn(
            modifier = Modifier.heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.layers.asReversed(), key = LayerUiModel::id) { layer ->
                LayerRow(
                    layer = layer,
                    palette = palette,
                    onSelect = { onSelectLayer(layer.id) },
                    onDelete = { onDeleteLayer(layer.id) },
                    onVisible = { visible -> onLayerVisible(layer.id, visible) },
                    onOpacity = { opacity -> onLayerOpacity(layer.id, opacity) },
                )
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: LayerUiModel,
    palette: WetInkNextPalette,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onVisible: (Boolean) -> Unit,
    onOpacity: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (layer.active) palette.accent.copy(alpha = 0.18f) else palette.panelBg.copy(alpha = 0.72f))
            .border(1.dp, if (layer.active) palette.accent else palette.panelStroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = layer.isVisible, onCheckedChange = onVisible)
            Text(text = layer.name, color = palette.textPrimary, modifier = Modifier.weight(1f))
            if (layer.canDelete) TextButton(onClick = onDelete) { Text("Удалить") }
        }
        if (layer.active) {
            CommitSlider(
                label = "Непрозрачность",
                value = layer.opacity,
                range = 0f..1f,
                palette = palette,
                format = { "${(it * 100f).toInt()}%" },
                onCommit = onOpacity,
            )
        }
    }
}

@Composable
private fun CommitSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    palette: WetInkNextPalette,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var localValue by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = palette.textSecondary, modifier = Modifier.weight(1f))
            Text(text = format(localValue), color = palette.textPrimary)
        }
        Slider(
            value = localValue,
            onValueChange = { localValue = it },
            onValueChangeFinished = { onCommit(localValue) },
            valueRange = range,
        )
    }
}

private fun Modifier.panel(palette: WetInkNextPalette): Modifier =
    background(palette.panelBg.copy(alpha = 0.94f), RoundedCornerShape(24.dp))
        .border(1.dp, palette.panelStroke, RoundedCornerShape(24.dp))
