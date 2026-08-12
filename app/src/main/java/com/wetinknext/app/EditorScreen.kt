package com.wetinknext.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.engine.brush.BrushPreset
import com.wetinknext.engine.brush.BrushLibrary
import com.wetinknext.engine.core.EditorUiState
import com.wetinknext.engine.core.CanvasBackdropMode
import com.wetinknext.engine.core.PaintSurfaceView
import com.wetinknext.ui.animation.AnimationTimelineToolbar
import com.wetinknext.ui.color.ColorPanel
import com.wetinknext.ui.color.GlesColorState
import com.wetinknext.ui.components.*
import com.wetinknext.ui.state.LayerState
import com.wetinknext.ui.theme.WetInkTheme
import com.wetinknext.ui.theme.AppTheme
import com.wetinknext.ui.theme.AppThemes
import com.wetinknext.ui.theme.CustomThemeEditor
import com.wetinknext.ui.theme.ThemeController
import com.wetinknext.ui.theme.rememberThemeController

private enum class EditorPanel {
    NONE,
    BRUSH,
    LAYERS,
    COLOR,
    SELECTION,
    TRANSFORM,
    ADJUSTMENTS,
    ANIMATION,
    SETTINGS,
}

@Composable
fun EditorScreen(
    projectId: String,
    document: ProjectDocument,
    layerTiles: Map<Long, ByteArray> = emptyMap(),
    layerPreviews: Map<Long, ByteArray> = emptyMap(),
    saveStatus: String = "Сохранено",
    onDocumentChanged: (ProjectDocument) -> Unit = {},
    onDirtyLayerTiles: (ProjectDocument, Map<Long, ByteArray>, Map<Long, Set<com.wetinknext.engine.undo.TileCoord>>, () -> Unit) -> Unit = { _, _, _, _ -> },
    onThumbnailCaptured: (com.wetinknext.engine.core.ThumbnailCapture.Rgba) -> Unit = {},
    onThumbnailBuildSaved: (com.wetinknext.engine.thumbnail.ThumbnailBuildResult) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeController = rememberThemeController()
    val theme = themeController.current
    var uiState by remember { mutableStateOf(EditorUiState.empty) }
    var openPanel by remember { mutableStateOf(EditorPanel.NONE) }
    var isEraser by remember { mutableStateOf(false) }
    var documentLoading by remember(projectId) { mutableStateOf(true) }
    
    val colorState = remember { GlesColorState(context) }
    var brushColor by remember { mutableStateOf(Color.Black) }
    var selectedBrush by remember { mutableStateOf<BrushPreset?>(BrushLibrary.pencil6B) }

    val layerState = remember { LayerState() }

    val surface = remember(projectId, document.id) {
        PaintSurfaceView(
            context = context,
            document = document,
            layerTiles = layerTiles,
            layerPreviews = layerPreviews,
        ).also { view ->
            view.onEditorStateChange = { uiState = it }
            view.onProjectDocumentChange = onDocumentChanged
            view.onDirtyLayerTiles = { changed, payloads, dirty -> onDirtyLayerTiles(changed, payloads, dirty) { view.acknowledgeSavedTiles(dirty) } }
            view.onThumbnailCaptured = onThumbnailCaptured
            view.onThumbnailBuildSaved = onThumbnailBuildSaved
            view.onDocumentSessionLoaded = { documentLoading = false }
        }
    }

    DisposableEffect(surface, onDocumentChanged) {
        surface.onProjectDocumentChange = onDocumentChanged
        surface.onDirtyLayerTiles = { changed, payloads, dirty -> onDirtyLayerTiles(changed, payloads, dirty) { surface.acknowledgeSavedTiles(dirty) } }
        surface.onThumbnailCaptured = onThumbnailCaptured
        surface.onThumbnailBuildSaved = onThumbnailBuildSaved
        surface.onDocumentSessionLoaded = { documentLoading = false }
        onDispose {
            surface.onProjectDocumentChange = null
            surface.onDirtyLayerTiles = null
            surface.onThumbnailCaptured = null
            surface.onThumbnailBuildSaved = null
            surface.onDocumentSessionLoaded = null
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

    LaunchedEffect(theme.canvasBackdrop, theme.canvasGrid, themeController.backdropMode) {
        surface.setCanvasBackdrop(theme.canvasBackdrop.toArgb(), theme.canvasGrid.toArgb(), themeController.backdropMode)
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

            if (documentLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(theme.appBg.copy(alpha = .92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Text("Загрузка документа…", color = theme.textPrimary)
                }
            }

            // Save status remains in state for diagnostics; it must not overlap the canvas.

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            ) {
                CanvasActionButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = "На главную",
                    theme = theme,
                    onClick = onBack,
                )
            }

            // Top Toolbar
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
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
                    onSettingsClick = {
                        openPanel = if (openPanel == EditorPanel.SETTINGS) EditorPanel.NONE else EditorPanel.SETTINGS
                    },
                    onLayersClick = {
                        openPanel = if (openPanel == EditorPanel.LAYERS) EditorPanel.NONE else EditorPanel.LAYERS
                    },
                    onColorClick = {
                        openPanel = if (openPanel == EditorPanel.COLOR) EditorPanel.NONE else EditorPanel.COLOR
                    },
                )
            }

            // Left controls keep the same compact stack as the reference editor.
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 100.dp),
                horizontalAlignment = Alignment.Start,
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
                    onEraserClick = {
                        isEraser = it
                        surface.setEraserEnabled(it)
                    }
                )
                LeftCanvasActions(
                    theme = theme,
                    onClearLayerClick = { surface.clearActiveLayer() },
                    onMirrorClick = { surface.toggleCanvasMirror() },
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
                themeController = themeController,
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
    themeController: ThemeController,
    onBrushSelected: (BrushPreset) -> Unit,
    onColorChange: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (panel) {
            EditorPanel.NONE -> Unit

            EditorPanel.SETTINGS -> {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 16.dp)) {
                    ThemeSettingsPanel(
                        theme = theme,
                        themeController = themeController,
                        onDismiss = onDismiss,
                    )
                }
            }
            
            EditorPanel.BRUSH -> {
                Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 72.dp)) {
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
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 16.dp)) {
                    LayersPanel(
                        state = layerState,
                        theme = theme,
                        onDismiss = onDismiss,
                        onAddLayer = { surface?.addLayer() },
                        onSelectLayer = { surface?.setActiveLayer(it) },
                        onVisibleChange = { id, visible -> surface?.setLayerVisible(id, visible) },
                        onOpacityChange = { id, opacity -> surface?.setLayerOpacity(id, opacity) },
                        onDuplicateLayer = { id -> surface?.duplicateLayer(id) },
                        onRemoveLayer = { id -> surface?.removeLayer(id) }
                    )
                }
            }

            EditorPanel.COLOR -> {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 16.dp)) {
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
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
                    SelectionToolbar(
                        theme = theme,
                        currentShape = SelectionShapeUi.FREEHAND,
                        onShapeChange = { /* TODO */ },
                        onDone = onDismiss
                    )
                }
            }

            EditorPanel.TRANSFORM -> {
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
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
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 16.dp)) {
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

@Composable
private fun ThemeSettingsPanel(
    theme: AppTheme,
    themeController: ThemeController,
    onDismiss: () -> Unit,
) {
    var preferencesOpen by remember { mutableStateOf(false) }
    var customThemeOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(320.dp)
            .heightIn(max = 620.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(theme.panelBg)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Text(
                text = if (preferencesOpen) "Предпочтения" else "Меню",
                color = theme.textPrimary,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Text(
                text = "×",
                color = theme.textSecondary,
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                modifier = Modifier.size(28.dp).clickable(onClick = onDismiss),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(theme.panelBgVariant.copy(alpha = .5f)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf("Меню", "Холст", "Экспорт", "Сведения").forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (index == 0) theme.panelBgVariant else Color.Transparent)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Text(
                        text = label,
                        color = if (index == 0) theme.textPrimary else theme.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        if (customThemeOpen) {
            CustomThemeEditor(
                colors = themeController.customColors,
                theme = theme,
                backdropMode = themeController.backdropMode,
                onChange = themeController::updateCustomColors,
                onBackdropModeChange = themeController::selectBackdropMode,
                onDismiss = { customThemeOpen = false },
            )
        } else if (!preferencesOpen) {
            androidx.compose.material3.Text(
                text = "Предпочтения",
                color = theme.textPrimary,
                modifier = Modifier.fillMaxWidth().clickable { preferencesOpen = true }.padding(vertical = 14.dp, horizontal = 4.dp),
            )
        } else {
            androidx.compose.material3.Text(
                text = "Тема",
                color = theme.textSecondary,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
            val choices = AppThemes.all + themeController.customTheme.copy(id = "custom", displayName = "Своя")
            choices.chunked(5).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { candidate ->
                        val selected = candidate.id == themeController.current.id
                        Box(
                            modifier = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (selected) Color.White else Color.Transparent)
                                .clickable {
                                    if (candidate.id == "custom") {
                                        themeController.selectCustom()
                                        customThemeOpen = true
                                    } else themeController.select(candidate)
                                }
                                .padding(2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(if (candidate.id == "custom") theme.panelBgVariant else candidate.appBg),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (candidate.id == "custom") {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = "Настроить свою тему",
                                        tint = theme.textPrimary,
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                                else Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).background(candidate.accent))
                            }
                        }
                    }
                    repeat(5 - row.size) { Spacer(Modifier.size(48.dp)) }
                }
            }
        }
    }
}

private fun PaintSurfaceView.applyBrushPreset(
    preset: BrushPreset
) {
    val settings = preset.settings

    applyBrush(settings)

    val grainPath = settings.grainAssetPath
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    if (grainPath == null) {
        clearGrainTexture()
    } else {
        loadGrainTexture(
            path = grainPath,
            scale = settings.grainScale,
            canvasLocked = settings.grainCanvasLocked,
            depth = settings.textureDepth,
            contrast = settings.textureContrast,
        )
    }

    val shapePath = settings.shapeAssetPath?.trim()?.takeIf { it.isNotEmpty() }
    if (shapePath == null) {
        clearShapeTexture()
    } else {
        loadShapeTexture(path = shapePath, rgbToAlpha = settings.rgbToAlpha)
    }
}
