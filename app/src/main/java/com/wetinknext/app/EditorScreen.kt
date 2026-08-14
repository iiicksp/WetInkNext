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
import com.wetinknext.data.export.ProjectExporter
import com.wetinknext.ui.animation.AnimationSettingsMenu
import com.wetinknext.ui.animation.AnimationTimelineToolbar
import com.wetinknext.ui.color.ColorPanel
import com.wetinknext.ui.color.GlesColorState
import com.wetinknext.ui.components.*
import com.wetinknext.ui.state.LayerState
import com.wetinknext.ui.theme.WetInkTheme
import com.wetinknext.ui.theme.AppTheme
import com.wetinknext.ui.theme.AppThemes
import com.wetinknext.ui.theme.CustomThemeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import java.nio.ByteBuffer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wetinknext.engine.brush.BrushSettings
import com.wetinknext.ui.theme.ThemeController
import com.wetinknext.ui.theme.rememberThemeController

private enum class EditorPanel {
    NONE,
    BRUSH,
    BRUSH_STUDIO,
    LAYERS,
    COLOR,
    SELECTION,
    TRANSFORM,
    ADJUSTMENTS,
    ANIMATION,
    ANIMATION_SETTINGS,
    SETTINGS,
    EXPORT,
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
    onBack: () -> Unit = {},
    onEditorPause: () -> Unit = {}
) {
    val context = LocalContext.current

    // Force-save on pause: the process may be killed without onDestroy.
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, onEditorPause) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) onEditorPause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val themeController = rememberThemeController()
    val theme = themeController.current
    var uiState by remember { mutableStateOf(EditorUiState.empty) }
    var openPanel by remember { mutableStateOf(EditorPanel.NONE) }
    var isEraser by remember { mutableStateOf(false) }
    var selectionShapeUi by remember { mutableStateOf(SelectionShapeUi.FREEHAND) }
    var transformMode by remember { mutableStateOf(TransformModeUi.FREEFORM) }
    var documentLoading by remember(projectId) { mutableStateOf(true) }

    val exportScope = rememberCoroutineScope()
    var exportBusy by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val brushPreviews = remember { mutableStateMapOf<String, ImageBitmap>() }
    val previewsInFlight = remember { mutableStateOf<Set<String>>(emptySet()) }
    var studioBrush by remember { mutableStateOf<BrushPreset?>(null) }
    var studioOriginal by remember { mutableStateOf<BrushSettings?>(null) }
    
    val colorState = remember { GlesColorState(context) }
    var brushColor by remember { mutableStateOf(Color.Black) }
    var selectedBrush by remember { mutableStateOf<BrushPreset?>(BrushLibrary.hb_pencil) }

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
        surface.applyBrushPreset(BrushLibrary.hb_pencil)
        selectedBrush = BrushLibrary.hb_pencil
    }

    LaunchedEffect(theme.canvasBackdrop, theme.canvasGrid, themeController.backdropMode) {
        surface.setCanvasBackdrop(theme.canvasBackdrop.toArgb(), theme.canvasGrid.toArgb(), themeController.backdropMode)
    }

    val startExport: (com.wetinknext.ui.components.ExportFormat) -> Unit = { format ->
        if (!exportBusy && !documentLoading) {
            exportBusy = true
            exportStatus = when (format) {
                com.wetinknext.ui.components.ExportFormat.PNG -> "Экспорт PNG…"
                com.wetinknext.ui.components.ExportFormat.JPEG -> "Экспорт JPEG…"
                com.wetinknext.ui.components.ExportFormat.PSD -> "Экспорт PSD…"
            }
            surface.requestExportSnapshot { snapshot ->
                if (snapshot == null) {
                    exportBusy = false
                    exportStatus = "Подождите окончания штриха"
                    return@requestExportSnapshot
                }
                exportScope.launch(Dispatchers.IO) {
                    val location = runCatching {
                        when (format) {
                            com.wetinknext.ui.components.ExportFormat.PNG ->
                                ProjectExporter.exportPng(context, snapshot)
                            com.wetinknext.ui.components.ExportFormat.JPEG ->
                                ProjectExporter.exportJpeg(context, snapshot, theme.canvasBackdrop.toArgb())
                            com.wetinknext.ui.components.ExportFormat.PSD ->
                                ProjectExporter.exportPsd(context, snapshot)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        exportBusy = false
                        exportStatus = location.fold(
                            onSuccess = { "Сохранено: $it" },
                            onFailure = { "Ошибка: ${it.message}" },
                        )
                    }
                }
            }
        }
    }

    fun requestBrushPreview(key: String, settings: BrushSettings, libraryOnce: Boolean) {
        if (libraryOnce && (brushPreviews.containsKey(key) || previewsInFlight.value.contains(key))) return
        previewsInFlight.value = previewsInFlight.value + key
        surface.requestBrushPreview(settings) { result ->
            previewsInFlight.value = previewsInFlight.value - key
            if (result != null) {
                val bitmap = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(result.rgba))
                brushPreviews[key] = bitmap.asImageBitmap()
            }
        }
    }

    fun openBrushStudio(brush: BrushPreset) {
        studioOriginal = brush.settings
        studioBrush = brush.copy(settings = brush.settings)
        openPanel = EditorPanel.BRUSH_STUDIO
    }

    fun uiShapeToEngine(shape: SelectionShapeUi): com.wetinknext.engine.selection.SelectionShape = when (shape) {
        SelectionShapeUi.FREEHAND -> com.wetinknext.engine.selection.SelectionShape.FREEHAND
        SelectionShapeUi.RECTANGLE -> com.wetinknext.engine.selection.SelectionShape.RECTANGLE
        SelectionShapeUi.ELLIPSE -> com.wetinknext.engine.selection.SelectionShape.ELLIPSE
    }

    val transformActions = object : TransformActions {
        override fun reset() { surface.transformResetTransforms() }
        override fun cancel() {
            surface.cancelTransform()
            openPanel = EditorPanel.NONE
        }
        override fun apply() {
            surface.applyTransform()
            openPanel = EditorPanel.NONE
        }
        override fun flipHorizontal() { surface.transformFlipH() }
        override fun flipVertical() { surface.transformFlipV() }
        override fun setMode(mode: TransformModeUi) {
            transformMode = mode
            surface.setTransformMode(mode == TransformModeUi.UNIFORM)
        }
        override fun rotate45() { surface.transformRotate45() }
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
                        val opening = openPanel != EditorPanel.SELECTION
                        if (opening) surface.beginSelection(uiShapeToEngine(selectionShapeUi))
                        openPanel = if (opening) EditorPanel.SELECTION else EditorPanel.NONE
                    },
                    onTransformClick = {
                        openPanel = if (openPanel == EditorPanel.TRANSFORM) EditorPanel.NONE else EditorPanel.TRANSFORM
                    },
                    onAnimationClick = {
                        if (openPanel != EditorPanel.ANIMATION) {
                            surface.toggleAnimationActive()
                        }
                        openPanel = if (openPanel == EditorPanel.ANIMATION) EditorPanel.NONE else EditorPanel.ANIMATION
                    },
                    onAdjustmentsClick = {
                        openPanel = if (openPanel == EditorPanel.ADJUSTMENTS) EditorPanel.NONE else EditorPanel.ADJUSTMENTS
                    },
                    onSettingsClick = {
                        openPanel = if (openPanel == EditorPanel.SETTINGS) EditorPanel.NONE else EditorPanel.SETTINGS
                    },
                    onExportClick = {
                        openPanel = if (openPanel == EditorPanel.EXPORT) EditorPanel.NONE else EditorPanel.EXPORT
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
                onExport = startExport,
                exportBusy = exportBusy,
                exportStatus = exportStatus,
                onDismiss = { openPanel = EditorPanel.NONE },
                previews = brushPreviews,
                onBrushPreviewRequest = { brush ->
                    requestBrushPreview(brush.id, brush.settings, libraryOnce = true)
                },
                onStudioOpen = { brush -> openBrushStudio(brush) },
                studioBrush = studioBrush,
                studioPreviewBusy = previewsInFlight.value.contains("studio:" + (studioBrush?.id ?: "")),
                onStudioSettingsChange = { settings ->
                    studioBrush = studioBrush?.copy(settings = settings)
                    surface.setBrushSettings(settings)
                },
                onStudioPreviewRequest = { settings ->
                    val key = "studio:" + (studioBrush?.id ?: "")
                    requestBrushPreview(key, settings, libraryOnce = false)
                },
                onStudioReset = {
                    studioOriginal?.let { original ->
                        studioBrush = studioBrush?.copy(settings = original)
                        surface.setBrushSettings(original)
                    }
                },
                onTransformBegin = { surface.beginTransform() },
                onSetSelectionShape = { shape -> surface.setSelectionShape(shape) },
                onClearSelection = { surface.clearSelection() },
                onDeleteSelection = { surface.deleteSelection() },
                onTransformActions = transformActions,
                transformMode = transformMode,
                uiState = uiState,
                selectionShapeUi = selectionShapeUi,
                onPanelChange = { openPanel = it }
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
    onExport: (com.wetinknext.ui.components.ExportFormat) -> Unit,
    exportBusy: Boolean,
    exportStatus: String?,
    previews: Map<String, ImageBitmap>,
    onBrushPreviewRequest: (BrushPreset) -> Unit,
    onStudioOpen: (BrushPreset) -> Unit,
    studioBrush: BrushPreset?,
    studioPreviewBusy: Boolean,
    onStudioSettingsChange: (BrushSettings) -> Unit,
    onStudioPreviewRequest: (BrushSettings) -> Unit,
    onStudioReset: () -> Unit,
    onTransformBegin: () -> Unit,
    onSetSelectionShape: (com.wetinknext.engine.selection.SelectionShape) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onTransformActions: TransformActions,
    transformMode: TransformModeUi,
    uiState: EditorUiState,
    selectionShapeUi: SelectionShapeUi,
    onPanelChange: (EditorPanel) -> Unit,
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

            EditorPanel.EXPORT -> {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 16.dp)) {
                    ExportPanel(
                        theme = theme,
                        busy = exportBusy,
                        status = exportStatus,
                        onExport = onExport,
                        onDismiss = onDismiss,
                    )
                }
            }
            
            EditorPanel.BRUSH_STUDIO -> {
                val brush = studioBrush
                if (brush != null) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                        BrushStudio(
                            preset = brush,
                            theme = theme,
                            preview = previews["studio:${brush.id}"],
                            previewBusy = studioPreviewBusy,
                            onSettingsChange = onStudioSettingsChange,
                            onPreviewRequest = onStudioPreviewRequest,
                            onReset = onStudioReset,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }

            EditorPanel.BRUSH -> {
                Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 72.dp)) {
                    BrushPanel(
                        currentBrush = selectedBrush ?: BrushLibrary.hb_pencil,
                        onBrushSelect = onBrushSelected,
                        onBrushStudioOpen = { onStudioOpen(it) },
                        theme = theme,
                        onDismiss = onDismiss,
                        onDuplicate = { /* TODO */ },
                        onDelete = { /* TODO */ },
                        previews = previews,
                        previewKey = { it.id },
                        onRequestPreview = onBrushPreviewRequest
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
                        currentShape = selectionShapeUi,
                        onShapeChange = { shape ->
                            selectionShapeUi = shape
                            onSetSelectionShape(
                                when (shape) {
                                    SelectionShapeUi.FREEHAND -> com.wetinknext.engine.selection.SelectionShape.FREEHAND
                                    SelectionShapeUi.RECTANGLE -> com.wetinknext.engine.selection.SelectionShape.RECTANGLE
                                    SelectionShapeUi.ELLIPSE -> com.wetinknext.engine.selection.SelectionShape.ELLIPSE
                                }
                            )
                        },
                        onDone = {
                            onTransformBegin()
                            onPanelChange(EditorPanel.TRANSFORM)
                        },
                        onResetSelection = onClearSelection,
                        onDeleteSelection = onDeleteSelection,
                    )
                }
            }

            EditorPanel.TRANSFORM -> {
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
                    TransformMenuView(
                        theme = theme,
                        currentMode = transformMode,
                        actions = onTransformActions
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

            EditorPanel.ANIMATION_SETTINGS -> {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 16.dp)) {
                    AnimationSettingsMenu(
                        theme = theme,
                        document = uiState.animationDocument
                            ?: com.wetinknext.domain.animation.AnimationDocument(enabled = true),
                        onDocumentChange = { surface.animationSetDocument(it) },
                        onDismiss = { onPanelChange(EditorPanel.ANIMATION) },
                    )
                }
            }

            EditorPanel.ANIMATION -> {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    AnimationTimelineToolbar(
                        theme = theme,
                        document = uiState.animationDocument
                            ?: com.wetinknext.domain.animation.AnimationDocument(enabled = true),
                        currentFrameId = uiState.animationFrameId,
                        isPlaying = uiState.animationPlaying,
                        onFrameSelect = { surface.animationSelectFrame(it) },
                        onFrameMove = { id, dir -> surface.animationMoveFrame(id, dir) },
                        onAddFrame = { surface.animationAddFrame() },
                        onDuplicateFrame = { surface.animationDuplicateFrame(it) },
                        onDeleteFrame = { surface.animationDeleteFrame(it) },
                        onHoldChange = { id, hold -> surface.animationSetHold(id, hold) },
                        onRoleToggle = { _, _ -> },
                        onAssembleLayers = {},
                        onLayersClick = { onPanelChange(EditorPanel.LAYERS) },
                        onSettingsClick = { onPanelChange(EditorPanel.ANIMATION_SETTINGS) },
                        onPlayToggle = { surface.animationTogglePlay() },
                        onClose = {
                            surface.toggleAnimationActive()
                            onPanelChange(EditorPanel.NONE)
                        }
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
            screenSpace = settings.grainScreenSpace,
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

    val secondaryShapePath = settings.secondaryShapeAssetPath?.trim()?.takeIf { it.isNotEmpty() }
    if (secondaryShapePath == null) {
        clearSecondaryShapeTexture()
    } else {
        loadSecondaryShapeTexture(path = secondaryShapePath, scale = settings.secondaryShapeScale)
    }
}
