package com.wetinknext.ui.color

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wetinknext.ui.components.PanelSurface
import com.wetinknext.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.roundToInt

private enum class ColorTab { Wheel, Map }

@Composable
fun ColorPanel(
    state: GlesColorState,
    color: Color,
    theme: AppTheme,
    onColorChange: (Color) -> Unit,
    onAddFromPhoto: () -> Unit,
    title: String = "Цвета",
    recordRecent: Boolean = true,
    allowCollectionEditing: Boolean = true,
    onPinCollection: (ColorCollection) -> Unit = {},
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(ColorTab.Wheel) }
    var hsl by remember { mutableStateOf(rgbToHsl(color)) }
    var harmonyType by remember { mutableStateOf(HarmonyType.Triadic) }
    val latestHsl by rememberUpdatedState(hsl)
    
    val wheelScrollState = rememberScrollState()
    val mapScrollState = rememberScrollState()
    val tabScrollState = when (tab) {
        ColorTab.Wheel -> wheelScrollState
        ColorTab.Map -> mapScrollState
    }

    LaunchedEffect(hsl, recordRecent) {
        val selectedColor = hslToColor(hsl)
        onColorChange(selectedColor)
        if (recordRecent) {
            delay(350)
            state.pushRecentColor(selectedColor)
        }
    }

    val dismissAndSave = {
        if (recordRecent) state.pushRecentColor(hslToColor(hsl))
        onDismiss()
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recordRecent) state.pushRecentColor(hslToColor(latestHsl))
        }
    }

    PanelSurface(
        modifier = modifier.width(320.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            ColorHeader(
                preview = hslToColor(hsl),
                title = title,
                theme = theme,
                onConfirm = dismissAndSave,
            )
            Spacer(Modifier.height(10.dp))
            TabBar(tab, theme) { tab = it }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .height(TabContentHeight)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(tabScrollState),
                ) {
                    when (tab) {
                        ColorTab.Wheel -> WheelTab(
                            hsl = hsl,
                            theme = theme,
                            onHslChange = { hsl = it },
                            harmonyType = harmonyType,
                            onHarmonyTypeChange = { harmonyType = it },
                            recent = state.recentColors.toList(),
                            onPickRecent = { picked ->
                                val p = rgbToHsl(picked)
                                hsl = hsl.copy(h = p.h, s = p.s)
                                if (recordRecent) state.pushRecentColor(hslToColor(hsl))
                            },
                        )
                        ColorTab.Map -> MapTab(
                            userCollections = state.userCollections.toList(),
                            currentColor = hslToColor(hsl),
                            theme = theme,
                            onPick = { picked ->
                                hsl = rgbToHsl(picked)
                                if (recordRecent) state.pushRecentColor(picked)
                            },
                            onDeleteUserCollection = { id ->
                                state.deleteUserCollection(id)
                            },
                            onAddFromPhoto = onAddFromPhoto,
                            allowCollectionEditing = allowCollectionEditing,
                            onCreatePalette = { name ->
                                state.addUserCollection(
                                    ColorCollection(
                                        id = System.currentTimeMillis(),
                                        name = name.ifBlank { "Новая палитра" },
                                        colors = emptyList(),
                                        builtin = false,
                                    ),
                                )
                            },
                            onAddColorToCollection = { id, color ->
                                state.addColorToCollection(id, color)
                            },
                            onReplaceColorInCollection = { id, slot, color ->
                                state.replaceColorInCollection(id, slot, color)
                            },
                            onRemoveColorFromCollection = { id, slot ->
                                state.removeColorFromCollection(id, slot)
                            },
                            onPinCollection = onPinCollection,
                        )
                    }
                }
            }
        }
    }
}

private val TabContentHeight = 440.dp

@Composable
private fun ColorHeader(preview: Color, onConfirm: () -> Unit, title: String, theme: AppTheme) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = theme.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(preview)
                .border(1.dp, theme.panelStroke, CircleShape)
                .clickable(onClick = onConfirm),
        )
    }
}

@Composable
private fun TabBar(current: ColorTab, theme: AppTheme, onChange: (ColorTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(theme.panelInset)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TabChip(label = "Кольцо", selected = current == ColorTab.Wheel, theme = theme) { onChange(ColorTab.Wheel) }
        TabChip(label = "Карта", selected = current == ColorTab.Map, theme = theme) { onChange(ColorTab.Map) }
    }
}

@Composable
private fun RowScope.TabChip(
    label: String,
    selected: Boolean,
    theme: AppTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) theme.accent.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) theme.textPrimary else theme.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun WheelTab(
    hsl: Hsl,
    theme: AppTheme,
    onHslChange: (Hsl) -> Unit,
    harmonyType: HarmonyType,
    onHarmonyTypeChange: (HarmonyType) -> Unit,
    recent: List<Color>,
    onPickRecent: (Color) -> Unit,
) {
    HueSatWheel(
        hsl = hsl,
        harmonyType = harmonyType,
        onHsChange = { newH, newS -> onHslChange(hsl.copy(h = newH, s = newS)) },
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
    )
    Spacer(Modifier.height(8.dp))
    LightnessSlider(
        hsl = hsl,
        theme = theme,
        onChange = { newL -> onHslChange(hsl.copy(l = newL)) },
    )
    Spacer(Modifier.height(8.dp))
    HexRow(color = hslToColor(hsl), theme = theme)
    Spacer(Modifier.height(10.dp))
    HarmonySection(
        hsl = hsl,
        theme = theme,
        harmonyType = harmonyType,
        onHarmonyTypeChange = onHarmonyTypeChange,
        onPick = onHslChange,
    )
    Spacer(Modifier.height(10.dp))
    SectionTitle("Недавние", theme)
    Spacer(Modifier.height(6.dp))
    RecentRow(colors = recent.take(GRID_COLUMNS), theme = theme, onPick = onPickRecent)
}

@Composable
private fun HueSatWheel(
    hsl: Hsl,
    harmonyType: HarmonyType,
    onHsChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnHsChange by rememberUpdatedState(onHsChange)
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val (nh, ns) = pickHueSat(change.position, size.width.toFloat(), size.height.toFloat())
                    latestOnHsChange(nh, ns)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    val (nh, ns) = pickHueSat(offset, size.width.toFloat(), size.height.toFloat())
                    latestOnHsChange(nh, ns)
                })
            },
    ) {
        val w = size.width
        val hgt = size.height
        val cx = w / 2f
        val cy = hgt / 2f
        val outer = minOf(w, hgt) / 2f - 4f
        if (outer <= 0f) return@Canvas

        val steps = 120
        for (i in 0 until steps) {
            val a0 = i * 360f / steps
            val a1 = (i + 1) * 360f / steps
            val midA = (a0 + a1) / 2f
            val hueColor = hslToColor(midA, 1f, 0.5f)
            drawArc(
                color = hueColor,
                startAngle = a0 - 90f,
                sweepAngle = (a1 - a0) + 0.6f,
                useCenter = true,
                topLeft = Offset(cx - outer, cy - outer),
                size = Size(outer * 2, outer * 2),
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                center = Offset(cx, cy),
                radius = outer,
            ),
            radius = outer,
            center = Offset(cx, cy),
        )

        val derived = harmonyType.derive(hsl)
        val verts = derived.map { d -> hslToWheelPoint(d, cx, cy, outer) }
        drawHarmonyShape(harmonyType, verts)

        val current = hslToWheelPoint(hsl, cx, cy, outer)
        drawCircle(color = Color.White, radius = 7f, center = current, style = Stroke(width = 2f))
        drawCircle(color = Color.Black, radius = 7f, center = current, style = Stroke(width = 1f))
    }
}

private fun hslToWheelPoint(h: Hsl, cx: Float, cy: Float, outer: Float): Offset {
    val a = Math.toRadians((h.h - 90.0))
    val r = h.s.coerceIn(0f, 1f) * outer
    return Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
}

private fun DrawScope.drawHarmonyShape(type: HarmonyType, verts: List<Offset>) {
    if (verts.size < 2) return
    val edges: List<Pair<Int, Int>> = when (type) {
        HarmonyType.Complementary -> listOf(0 to 1)
        HarmonyType.Analogous -> listOf(0 to 1, 1 to 2)
        HarmonyType.Triadic -> listOf(0 to 1, 1 to 2, 2 to 0)
        HarmonyType.SplitComplementary -> listOf(0 to 1, 0 to 2, 1 to 2)
        HarmonyType.Tetradic,
        HarmonyType.Square -> listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
    }
    edges.forEach { (a, b) ->
        drawLine(color = Color.White.copy(alpha = 0.85f), start = verts[a], end = verts[b], strokeWidth = 2.5f)
        drawLine(color = Color.Black.copy(alpha = 0.55f), start = verts[a], end = verts[b], strokeWidth = 1f)
    }
    verts.forEach { v ->
        drawCircle(color = Color.White, radius = 4f, center = v)
        drawCircle(color = Color.Black, radius = 4f, center = v, style = Stroke(width = 1f))
    }
}

private fun pickHueSat(p: Offset, w: Float, h: Float): Pair<Float, Float> {
    val cx = w / 2f
    val cy = h / 2f
    val outer = minOf(w, h) / 2f - 4f
    val dx = p.x - cx
    val dy = p.y - cy
    val r = hypot(dx, dy)
    val ang = Math.toDegrees(atan2(dy, dx).toDouble()) + 90.0
    val nh = ((ang + 360.0) % 360.0).toFloat()
    val ns = (r / outer).coerceIn(0f, 1f)
    return nh to ns
}

@Composable
private fun LightnessSlider(hsl: Hsl, theme: AppTheme, onChange: (Float) -> Unit) {
    val hue = hsl.h
    val sat = hsl.s
    val gradient = remember(hue, sat) {
        Brush.horizontalGradient(
            colors = listOf(
                hslToColor(hue, sat, 0f),
                hslToColor(hue, sat, 0.5f),
                hslToColor(hue, sat, 1f),
            ),
        )
    }
    val latestOnChange by rememberUpdatedState(onChange)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("L", color = theme.textSecondary, fontSize = 12.sp, modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .height(20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(gradient)
                .border(1.5.dp, theme.panelStroke, RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        latestOnChange(f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        latestOnChange(f)
                    })
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val knobR = (size.height / 2f - 2f).coerceAtMost(size.width / 2f).coerceAtLeast(0f)
                if (knobR <= 0f) return@Canvas
                val knobX = (hsl.l.coerceIn(0f, 1f) * size.width).coerceIn(knobR + 2f, size.width - knobR - 2f)
                
                drawCircle(color = Color.White, radius = knobR, center = Offset(knobX, size.height / 2f), style = Stroke(width = 2.5f))
                drawCircle(color = theme.panelStroke, radius = knobR, center = Offset(knobX, size.height / 2f), style = Stroke(width = 1f))
            }
        }
    }
}

@Composable
private fun HexRow(color: Color, theme: AppTheme) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hex", color = theme.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(theme.panelInset)
                .border(1.dp, theme.panelStroke, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(color.toHex6(), color = theme.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun HarmonySection(
    hsl: Hsl,
    theme: AppTheme,
    harmonyType: HarmonyType,
    onHarmonyTypeChange: (HarmonyType) -> Unit,
    onPick: (Hsl) -> Unit,
) {
    SectionTitle("Тип гармонии", theme)
    Spacer(Modifier.height(6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        HarmonyType.entries.forEach { type ->
            HarmonyIcon(
                type = type,
                theme = theme,
                selected = type == harmonyType,
                modifier = Modifier.weight(1f),
            ) { onHarmonyTypeChange(type) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(harmonyType.displayName, color = theme.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    val derived = remember(harmonyType, hsl) { harmonyType.derive(hsl) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        derived.forEach { d ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(hslToColor(d))
                    .border(1.dp, theme.panelInset, RoundedCornerShape(8.dp))
                    .clickable { onPick(d) },
            )
        }
        repeat(4 - derived.size) { Box(modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun HarmonyIcon(
    type: HarmonyType,
    theme: AppTheme,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) theme.accent.copy(alpha = 0.25f) else theme.panelInset)
            .border(1.dp, if (selected) theme.accent else theme.panelInset, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = minOf(size.width, size.height) / 2f - 1f
            val ringColor = (if (selected) Color.White else theme.textSecondary).copy(alpha = 0.6f)
            val shapeColor = if (selected) Color.White else theme.textSecondary
            drawCircle(color = ringColor, radius = r, center = Offset(cx, cy), style = Stroke(width = 1.2f))
            val verts = type.offsets.map { off ->
                val a = Math.toRadians((off - 90.0).toDouble())
                Offset(cx + (r * 0.78f * cos(a)).toFloat(), cy + (r * 0.78f * sin(a)).toFloat())
            }
            val edges: List<Pair<Int, Int>> = when (type) {
                HarmonyType.Complementary -> listOf(0 to 1)
                HarmonyType.Analogous -> listOf(0 to 1, 1 to 2)
                HarmonyType.Triadic -> listOf(0 to 1, 1 to 2, 2 to 0)
                HarmonyType.SplitComplementary -> listOf(0 to 1, 0 to 2, 1 to 2)
                HarmonyType.Tetradic,
                HarmonyType.Square -> listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
            }
            edges.forEach { (a, b) -> drawLine(color = shapeColor, start = verts[a], end = verts[b], strokeWidth = 1.8f) }
            verts.forEach { v -> drawCircle(color = shapeColor, radius = 2.2f, center = v) }
        }
    }
}

private const val GRID_COLUMNS = 10

@Composable
private fun MapTab(
    userCollections: List<ColorCollection>,
    currentColor: Color,
    theme: AppTheme,
    onPick: (Color) -> Unit,
    onDeleteUserCollection: (Long) -> Unit,
    onAddFromPhoto: () -> Unit,
    allowCollectionEditing: Boolean,
    onCreatePalette: (String) -> Unit,
    onAddColorToCollection: (Long, Color) -> Unit,
    onReplaceColorInCollection: (Long, Int, Color) -> Unit,
    onRemoveColorFromCollection: (Long, Int) -> Unit,
    onPinCollection: (ColorCollection) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (allowCollectionEditing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapActionButton(label = "+ Создать", theme = theme, modifier = Modifier.weight(1f), onClick = { showCreate = true })
                MapActionButton(label = "+ Из фото", theme = theme, modifier = Modifier.weight(1f), onClick = onAddFromPhoto)
            }
        }
        userCollections.forEach { collection ->
            CollectionCard(collection = collection, theme = theme, onPick = onPick, onDelete = { onDeleteUserCollection(collection.id) },
                onAddCurrentColor = { onAddColorToCollection(collection.id, currentColor) },
                onReplaceAt = { slot -> onReplaceColorInCollection(collection.id, slot, currentColor) },
                onRemoveAt = { slot -> onRemoveColorFromCollection(collection.id, slot) },
                onPin = { onPinCollection(collection) },
            )
        }
        BuiltinCollections.all.forEach { collection ->
            CollectionCard(collection = collection, theme = theme, onPick = onPick, onDelete = null, onAddCurrentColor = null, onReplaceAt = null, onRemoveAt = null, onPin = { onPinCollection(collection) })
        }
    }
    if (showCreate && allowCollectionEditing) {
        CreatePaletteDialog(theme = theme, onConfirm = { name -> onCreatePalette(name); showCreate = false }, onDismiss = { showCreate = false })
    }
}

@Composable
private fun MapActionButton(label: String, theme: AppTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.accent.copy(alpha = 0.15f)).border(1.2.dp, theme.accentSoft, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CollectionCard(collection: ColorCollection, theme: AppTheme, onPick: (Color) -> Unit, onDelete: (() -> Unit)?, onAddCurrentColor: (() -> Unit)?, onReplaceAt: ((Int) -> Unit)?, onRemoveAt: ((Int) -> Unit)?, onPin: () -> Unit) {
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() }
    var rawOffset by remember(collection.id) { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(rawOffset, animationSpec = tween(140), label = "paletteSwipe")
    val cardShape = RoundedCornerShape(10.dp)
    Box(modifier = Modifier.fillMaxWidth().clip(cardShape)) {
        Box(
            modifier = Modifier.matchParentSize().background(theme.accent.copy(alpha = 0.9f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                "Закрепить",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(96.dp).fillMaxHeight().clickable { onPin(); rawOffset = 0f }.wrapContentHeight(Alignment.CenterVertically),
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .background(theme.panelInset)
                .border(1.dp, theme.panelStroke, cardShape)
                .pointerInput(collection.id, revealPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dx ->
                            if (dx < 0f || rawOffset < 0f) {
                                change.consume()
                                rawOffset = (rawOffset + dx).coerceIn(-revealPx, 0f)
                            }
                        },
                        onDragEnd = { rawOffset = if (rawOffset < -revealPx * 0.4f) -revealPx else 0f },
                        onDragCancel = { rawOffset = 0f },
                    )
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(collection.name, color = theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (onDelete != null) {
                Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(theme.panelBg.copy(alpha = 0.7f)).border(1.dp, theme.panelStroke, CircleShape).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                    Text("×", color = theme.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        SwatchGrid(colors = collection.composeColors(), theme = theme, onPick = onPick, trailingPlusOnClick = onAddCurrentColor, onLongPressIndex = if (onReplaceAt != null && onRemoveAt != null) { { idx -> editingIdx = idx } } else null)
        }
    }
    val activeIdx = editingIdx
    if (activeIdx != null && onReplaceAt != null && onRemoveAt != null) {
        val swatchColor = collection.composeColors().getOrNull(activeIdx)
        if (swatchColor != null) {
            EditSwatchSheet(swatchColor = swatchColor, theme = theme, onReplace = { onReplaceAt(activeIdx); editingIdx = null }, onRemove = { onRemoveAt(activeIdx); editingIdx = null }, onDismiss = { editingIdx = null })
        } else editingIdx = null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwatchGrid(colors: List<Color>, theme: AppTheme, onPick: (Color) -> Unit, trailingPlusOnClick: (() -> Unit)? = null, onLongPressIndex: ((Int) -> Unit)? = null) {
    val total = colors.size + if (trailingPlusOnClick != null) 1 else 0
    if (total == 0) return
    val itemRows = (0 until total).toList().chunked(GRID_COLUMNS)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { i ->
                    if (i < colors.size) {
                        val c = colors[i]
                        val cellModifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(c).border(1.dp, theme.panelInset, RoundedCornerShape(5.dp))
                        if (onLongPressIndex != null) Box(modifier = cellModifier.combinedClickable(onClick = { onPick(c) }, onLongClick = { onLongPressIndex(i) }))
                        else Box(modifier = cellModifier.clickable { onPick(c) })
                    } else PlusTile(theme = theme, modifier = Modifier.weight(1f).aspectRatio(1f), onClick = trailingPlusOnClick!!)
                }
                repeat(GRID_COLUMNS - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun EditSwatchSheet(swatchColor: Color, theme: AppTheme, onReplace: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.width(280.dp).clip(RoundedCornerShape(16.dp)).background(theme.panelBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(swatchColor).border(1.dp, theme.panelInset, RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(10.dp)); Text("Цвет", color = theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            DialogButton(label = "Заменить текущим", theme = theme, primary = true, modifier = Modifier.fillMaxWidth(), onClick = onReplace)
            DialogButton(label = "Удалить", theme = theme, primary = false, modifier = Modifier.fillMaxWidth(), onClick = onRemove)
            DialogButton(label = "Отмена", theme = theme, primary = false, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

@Composable
private fun PlusTile(theme: AppTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.clip(RoundedCornerShape(5.dp)).background(theme.panelBg.copy(alpha = 0.5f)).border(1.dp, theme.accent.copy(alpha = 0.7f), RoundedCornerShape(5.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text("+", color = theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CreatePaletteDialog(theme: AppTheme, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.width(280.dp).clip(RoundedCornerShape(16.dp)).background(theme.panelBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Новая палитра", color = theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.panelInset).border(1.dp, theme.panelInset, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, textStyle = TextStyle(color = theme.textPrimary, fontSize = 14.sp), cursorBrush = SolidColor(theme.accent), decorationBox = { inner -> if (name.isEmpty()) Text("Название", color = theme.textSecondary, fontSize = 14.sp); inner() }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DialogButton(label = "Отмена", theme = theme, primary = false, modifier = Modifier.weight(1f), onClick = onDismiss)
                DialogButton(label = "Создать", theme = theme, primary = true, modifier = Modifier.weight(1f), onClick = { onConfirm(name) })
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, theme: AppTheme, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(if (primary) theme.accent.copy(alpha = 0.35f) else theme.panelInset).border(1.dp, if (primary) theme.accent else theme.panelInset, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(text: String, theme: AppTheme) {
    Text(text, color = theme.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun RecentRow(colors: List<Color>, theme: AppTheme, onPick: (Color) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        repeat(GRID_COLUMNS) { i ->
            val c = colors.getOrNull(i)
            if (c != null) Box(modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(c).border(1.dp, theme.panelInset, RoundedCornerShape(5.dp)).clickable { onPick(c) })
            else Box(modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(theme.panelInset).border(1.dp, theme.panelInset, RoundedCornerShape(5.dp)))
        }
    }
}
