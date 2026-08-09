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
import com.wetinknext.engine.core.EditorUiState
import com.wetinknext.engine.core.PaintSurfaceView
import com.wetinknext.ui.color.ColorPanel
import com.wetinknext.ui.color.GlesColorState
import com.wetinknext.ui.components.LayersPanel
import com.wetinknext.ui.components.SideToolbar
import com.wetinknext.ui.components.TopToolbar
import com.wetinknext.ui.state.LayerState
import com.wetinknext.ui.theme.AppThemes
import com.wetinknext.ui.theme.WetInkTheme
import com.wetinknext.ui.theme.rememberThemeController

enum class OpenPanel {
    NONE,
    LAYERS,
    SELECTION,
    TRANSFORM,
    ANIMATION,
    COLOR,
}

@Composable
fun EditorScreen() {
    val context = LocalContext.current
    val themeController = rememberThemeController()
    val theme = themeController.current
    var uiState by remember { mutableStateOf(EditorUiState.empty) }
    var surface by remember { mutableStateOf<PaintSurfaceView?>(null) }
    var openPanel by remember { mutableStateOf(OpenPanel.NONE) }
    var isEraser by remember { mutableStateOf(false) }
    
    val colorState = remember { GlesColorState(context) }
    var brushColor by remember { mutableStateOf(Color.Black) }

    val layerState = remember { LayerState() }

    LaunchedEffect(uiState) {
        layerState.syncFromEditor(uiState)
    }

    WetInkTheme(theme = theme, fontMode = themeController.font) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.appBg),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PaintSurfaceView(context).also { view ->
                        view.onEditorStateChange = { uiState = it }
                        surface = view
                        view.requestState()

                        // Load initial brush preset (Pencil 6B)
                        val pencil = com.wetinknext.engine.brush.BrushLibrary.pencil6B.settings
                        view.loadGrainTexture(
                            path = pencil.grainAssetPath ?: "",
                            scale = pencil.grainScale,
                            canvasLocked = pencil.grainCanvasLocked,
                            depth = pencil.textureDepth,
                            contrast = pencil.textureContrast,
                        )
                    }
                },
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
                    isSelectionActive = openPanel == OpenPanel.SELECTION,
                    isTransformActive = openPanel == OpenPanel.TRANSFORM,
                    isAnimationActive = openPanel == OpenPanel.ANIMATION,
                    onUndoClick = { surface?.undo() },
                    onRedoClick = { surface?.redo() },
                    onSelectionClick = {
                        openPanel = if (openPanel == OpenPanel.SELECTION) OpenPanel.NONE else OpenPanel.SELECTION
                    },
                    onTransformClick = {
                        openPanel = if (openPanel == OpenPanel.TRANSFORM) OpenPanel.NONE else OpenPanel.TRANSFORM
                    },
                    onAnimationClick = {
                        openPanel = if (openPanel == OpenPanel.ANIMATION) OpenPanel.NONE else OpenPanel.ANIMATION
                    },
                    onLayersClick = {
                        openPanel = if (openPanel == OpenPanel.LAYERS) OpenPanel.NONE else OpenPanel.LAYERS
                    },
                    onColorClick = {
                        openPanel = if (openPanel == OpenPanel.COLOR) OpenPanel.NONE else OpenPanel.COLOR
                    }
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
                    onBrushSizeChange = { surface?.setBrushSize(it) },
                    onBrushOpacityChange = { surface?.setBrushOpacity(it) },
                    onBrushClick = { isEraser = false },
                    onEraserClick = { isEraser = it }
                )
            }

            // Panels
            if (openPanel == OpenPanel.LAYERS) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                ) {
                    LayersPanel(
                        state = layerState,
                        theme = theme,
                        onDismiss = { openPanel = OpenPanel.NONE },
                        onAddLayer = { surface?.addLayer() },
                        onSelectLayer = { surface?.setActiveLayer(it) },
                        onVisibleChange = { id, visible -> surface?.setLayerVisible(id, visible) },
                        onOpacityChange = { id, opacity -> surface?.setLayerOpacity(id, opacity) },
                        onRemoveLayer = { id -> surface?.removeLayer(id) }
                    )
                }
            }

            if (openPanel == OpenPanel.COLOR) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                ) {
                    ColorPanel(
                        state = colorState,
                        color = brushColor,
                        theme = theme,
                        onColorChange = { 
                            brushColor = it
                            surface?.setBrushColor(it)
                        },
                        onAddFromPhoto = { /* TODO */ },
                        onDismiss = { openPanel = OpenPanel.NONE }
                    )
                }
            }
        }
    }
}
