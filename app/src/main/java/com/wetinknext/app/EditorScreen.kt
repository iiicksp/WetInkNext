package com.wetinknext.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import com.wetinknext.engine.brush.BrushPreset
import com.wetinknext.engine.brush.BrushLibrary
import com.wetinknext.engine.core.EditorUiState
import com.wetinknext.engine.core.PaintSurfaceView
import com.wetinknext.ui.animation.AnimationTimelineToolbar
import com.wetinknext.ui.color.ColorPanel
import com.wetinknext.ui.color.GlesColorState
import com.wetinknext.ui.components.*
import com.wetinknext.ui.state.LayerState
import com.wetinknext.ui.theme.WetInkTheme
import com.wetinknext.ui.theme.rememberThemeController

private enum class EditorPanel {
    NONE,
    BRUSH,
    LAYERS,
    COLOR,
    SELECTION,
    TRANSFORM,
    ADJUSTMENTS,
    ANIMATION
}

@Composable
fun EditorScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeController = rememberThemeController()
    val theme = themeController.current
    var uiState by remember { mutableStateOf(EditorUiState.empty) }
    var openPanel by remember { mutableStateOf(EditorPanel.NONE) }
    var isEraser by remember { mutableStateOf(false) }
    
    val colorState = remember { GlesColorState(context) }
    var brushColor by remember { mutableStateOf(Color.Black) }
    var selectedBrush by remember { mutableStateOf<BrushPreset?>(BrushLibrary.pencil6B) }

    val layerState = remember { LayerState() }

    val surface = remember {
        PaintSurfaceView(context).also { view ->
            view.onEditorStateChange = { uiState = it }
        }
    }

    LaunchedEffect(uiState) {
        layerState.syncFromEditor(uiState)
    }

    // Initialize state
    LaunchedEffect(surface) {
        surface.requestState()
        surface.applyBrushPreset(BrushLibrary.pencil6B)
        selectedBrush = BrushLibrary.pencil6B
    }

    WetInkTheme(theme = theme, fontMode = themeController.font) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.appBg),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { surface },
            )

            // Top Toolbar
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                TopToolbar(
                    theme = theme,
                    currentColor = brushColor,
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    isSelectionActive = openPanel == EditorPanel.SELECTION,
                    isTransformActive = openPanel == EditorPanel.TRANSFORM,
                    isAnimationActive = openPanel == EditorPanel.ANIMATION,
                    isAdjustmentsActive = openPanel == EditorPanel.ADJUSTMENTS,
                    onUndoClick = { surface.undo() },
                    onRedoClick = { surface.redo() },
                    onSelectionClick = {
                        openPanel = if (openPanel == EditorPanel.SELECTION) EditorPanel.NONE else EditorPanel.SELECTION
                    },
                    onTransformClick = {
                        openPanel = if (openPanel == EditorPanel.TRANSFORM) EditorPanel.NONE else EditorPanel.TRANSFORM
                    },
                    onAnimationClick = {
                        openPanel = if (openPanel == EditorPanel.ANIMATION) EditorPanel.NONE else EditorPanel.ANIMATION
                    },
                    onAdjustmentsClick = {
                        openPanel = if (openPanel == EditorPanel.ADJUSTMENTS) EditorPanel.NONE else EditorPanel.ADJUSTMENTS
                    },
                    onLayersClick = {
                        openPanel = if (openPanel == EditorPanel.LAYERS) EditorPanel.NONE else EditorPanel.LAYERS
                    },
                    onColorClick = {
                        openPanel = if (openPanel == EditorPanel.COLOR) EditorPanel.NONE else EditorPanel.COLOR
                    },
                )
            }

            // Side Toolbar
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
            ) {
                SideToolbar(
                    theme = theme,
                    brushSize = uiState.brushSizePx,
                    brushOpacity = uiState.brushOpacity,
                    isEraser = isEraser,
                    onBrushSizeChange = { surface.setBrushSize(it) },
                    onBrushOpacityChange = { surface.setBrushOpacity(it) },
                    onBrushClick = { 
                        openPanel = if (openPanel == EditorPanel.BRUSH) EditorPanel.NONE else EditorPanel.BRUSH
                    },
                    onEraserClick = { isEraser = it }
                )
            }

            // Panel Host
            EditorPanelHost(
                panel = openPanel,
                theme = theme,
                surface = surface,
                layerState = layerState,
                colorState = colorState,
                brushColor = brushColor,
                selectedBrush = selectedBrush,
                onBrushSelected = { preset ->
                    selectedBrush = preset
                    surface.applyBrushPreset(preset)
                },
                onColorChange = {
                    brushColor = it
                    surface.setBrushColor(it)
                },
                onDismiss = { openPanel = EditorPanel.NONE }
            )
        }
    }
}

@Composable
private fun EditorPanelHost(
    panel: EditorPanel,
    theme: com.wetinknext.ui.theme.AppTheme,
    surface: PaintSurfaceView?,
    layerState: LayerState,
    colorState: GlesColorState,
    brushColor: Color,
    selectedBrush: BrushPreset?,
    onBrushSelected: (BrushPreset) -> Unit,
    onColorChange: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (panel) {
            EditorPanel.NONE -> Unit
            
            EditorPanel.BRUSH -> {
                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                    BrushPanel(
                        currentBrush = selectedBrush ?: BrushLibrary.pencil6B,
                        onBrushSelect = onBrushSelected,
                        onBrushStudioOpen = { /* TODO */ },
                        theme = theme,
                        onDismiss = onDismiss,
                        onDuplicate = { /* TODO */ },
                        onDelete = { /* TODO */ }
                    )
                }
            }

            EditorPanel.LAYERS -> {
                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                    LayersPanel(
                        state = layerState,
                        theme = theme,
                        onDismiss = onDismiss,
                        onAddLayer = { surface?.addLayer() },
                        onSelectLayer = { surface?.setActiveLayer(it) },
                        onVisibleChange = { id, visible -> surface?.setLayerVisible(id, visible) },
                        onOpacityChange = { id, opacity -> surface?.setLayerOpacity(id, opacity) },
                        onRemoveLayer = { id -> surface?.removeLayer(id) }
                    )
                }
            }

            EditorPanel.COLOR -> {
                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                    ColorPanel(
                        state = colorState,
                        color = brushColor,
                        theme = theme,
                        onColorChange = onColorChange,
                        onAddFromPhoto = { /* TODO */ },
                        onDismiss = onDismiss
                    )
                }
            }

            EditorPanel.SELECTION -> {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    SelectionToolbar(
                        theme = theme,
                        currentShape = SelectionShapeUi.FREEHAND,
                        onShapeChange = { /* TODO */ },
                        onDone = onDismiss
                    )
                }
            }

            EditorPanel.TRANSFORM -> {
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) {
                    TransformMenuView(
                        theme = theme,
                        currentMode = TransformModeUi.UNIFORM,
                        actions = object : TransformActions {
                            override fun reset() { /* TODO */ }
                            override fun cancel() { onDismiss() }
                            override fun apply() { onDismiss() }
                            override fun flipHorizontal() { /* TODO */ }
                            override fun flipVertical() { /* TODO */ }
                            override fun setMode(mode: TransformModeUi) { /* TODO */ }
                        }
                    )
                }
            }

            EditorPanel.ADJUSTMENTS -> {
                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                    AdjustmentsPanel(
                        theme = theme,
                        onClose = onDismiss,
                        onAction = { /* TODO */ }
                    )
                }
            }

            EditorPanel.ANIMATION -> {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    AnimationTimelineToolbar(
                        theme = theme,
                        document = com.wetinknext.domain.animation.AnimationDocument(enabled = true),
                        currentFrameId = 0L,
                        onFrameSelect = {},
                        onFrameMove = { _, _ -> },
                        onAddFrame = {},
                        onDuplicateFrame = {},
                        onDeleteFrame = {},
                        onHoldChange = { _, _ -> },
                        onRoleToggle = { _, _ -> },
                        onAssembleLayers = {},
                        onLayersClick = {},
                        onClose = onDismiss
                    )
                }
            }
        }
    }
}

private fun PaintSurfaceView.applyBrushPreset(
    preset: BrushPreset
) {
    val settings = preset.settings

    setBrushSettings(settings)

    val grainPath = settings.grainAssetPath
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    if (grainPath == null) {
        clearGrainTexture()
        return
    }

    loadGrainTexture(
        path = grainPath,
        scale = settings.grainScale,
        canvasLocked = settings.grainCanvasLocked,
        depth = settings.textureDepth,
        contrast = settings.textureContrast,
    )
}
