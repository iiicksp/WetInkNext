package com.wetinknext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.engine.brush.BrushPreset
import com.wetinknext.engine.brush.BrushSettings
import com.wetinknext.engine.brush.DabFalloff
import com.wetinknext.ui.theme.AppTheme
import kotlinx.coroutines.delay

/**
 * Brush Studio — Procreate-style parameter editor with a live preview strip.
 *
 * Every control mutates a session-local draft of [BrushSettings]; the parent
 * applies it to the engine and re-renders the preview (debounced in the
 * panel). "Reset" returns the draft to the preset's stored settings.
 */
@Composable
fun BrushStudio(
    preset: BrushPreset,
    theme: AppTheme,
    preview: ImageBitmap?,
    previewBusy: Boolean,
    onSettingsChange: (BrushSettings) -> Unit,
    onPreviewRequest: (BrushSettings) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember(preset.id, preset.settings) { mutableStateOf(preset.settings) }

    // Live preview, debounced so slider drags do not spam the GL thread.
    LaunchedEffect(draft) {
        delay(PREVIEW_DEBOUNCE_MS)
        onPreviewRequest(draft)
    }

    PanelSurface(modifier = Modifier.width(360.dp)) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Header: name + close.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = draft.name.ifBlank { preset.settings.name },
                    color = theme.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Закрыть",
                        tint = theme.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Live stroke preview strip.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.panelInset),
                contentAlignment = Alignment.Center,
            ) {
                PreviewCheckerboard(Modifier.fillMaxSize(), cellSize = 8f)
                when {
                    preview != null -> androidx.compose.foundation.Image(
                        bitmap = preview,
                        contentDescription = "Превью кисти",
                        modifier = Modifier.fillMaxSize(),
                    )
                    previewBusy -> Text("…", color = theme.textSecondary, fontSize = 22.sp)
                    else -> Text(
                        "Проведите кистью, чтобы увидеть превью",
                        color = theme.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            StudioSlider(
                theme = theme,
                title = "Размер",
                value = draft.baseRadiusPx * 2f,
                valueRange = 2f..400f,
                display = { "${it.toInt()} px" },
            ) { draft = draft.copy(baseRadiusPx = it / 2f) }

            StudioSlider(
                theme = theme,
                title = "Прозрачность",
                value = draft.opacity,
                valueRange = 0.05f..1f,
                display = { "${(it * 100).toInt()} %" },
            ) { value -> draft = draft.copy(opacity = value) }

            StudioSlider(
                theme = theme,
                title = "Интервал (spacing)",
                value = draft.spacing,
                valueRange = 0.02f..1f,
                display = { "${(it * 100).toInt()} %" },
            ) { value -> draft = draft.copy(spacing = value) }

            if (draft.renderMode == com.wetinknext.engine.brush.BrushRenderMode.STAMP) {
                StudioSlider(
                    theme = theme,
                    title = "Жёсткость",
                    value = draft.hardness,
                    valueRange = 0f..1f,
                    display = { "${(it * 100).toInt()} %" },
                ) { value -> draft = draft.copy(hardness = value) }
            }

            StudioSlider(
                theme = theme,
                title = "Сглаживание",
                value = draft.smoothing,
                valueRange = 0f..1f,
                display = { "${(it * 100).toInt()} %" },
            ) { value -> draft = draft.copy(smoothing = value) }

            StudioSlider(
                theme = theme,
                title = "Мин. размер при давлении",
                value = draft.minSizeRatio,
                valueRange = 0.05f..1f,
                display = { "${(it * 100).toInt()} %" },
            ) { value -> draft = draft.copy(minSizeRatio = value) }

            Spacer(Modifier.height(8.dp))

            // Falloff profile chips.
            Text("Профиль края", color = theme.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FALLOFF_OPTIONS.forEach { (falloff, label) ->
                    val selected = draft.falloff == falloff
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) theme.accent.copy(alpha = 0.28f) else theme.panelInset)
                            .border(1.dp, if (selected) theme.accentSoft else theme.panelStroke, RoundedCornerShape(8.dp))
                            .clickable { draft = draft.copy(falloff = falloff) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (selected) theme.accent else theme.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Dynamics toggles.
            StudioToggle(
                theme = theme,
                title = "Давление → размер",
                checked = draft.pressureToSize,
            ) { checked -> draft = draft.copy(pressureToSize = checked) }
            StudioToggle(
                theme = theme,
                title = "Давление → прозрачность",
                checked = draft.pressureToOpacity,
            ) { checked -> draft = draft.copy(pressureToOpacity = checked) }
            StudioToggle(
                theme = theme,
                title = "Поворот по траектории",
                checked = draft.followTrajectory > 0f,
            ) { checked -> draft = draft.copy(followTrajectory = if (checked) 1f else 0f) }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioButton(
                    label = "Сбросить",
                    theme = theme,
                    emphasis = false,
                    modifier = Modifier.weight(1f),
                ) { onReset() }
                StudioButton(
                    label = "Готово",
                    theme = theme,
                    emphasis = true,
                    modifier = Modifier.weight(1f),
                ) { onClose() }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StudioSlider(
    theme: AppTheme,
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(title, color = theme.textPrimary, fontSize = 13.sp, modifier = Modifier.width(150.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            display(value),
            color = theme.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(56.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun StudioToggle(
    theme: AppTheme,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(title, color = theme.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StudioButton(
    label: String,
    theme: AppTheme,
    emphasis: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (emphasis) theme.accent else theme.panelInset)
            .border(1.dp, if (emphasis) theme.accent else theme.panelStroke, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (emphasis) Color.White else theme.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val FALLOFF_OPTIONS = listOf(
    DabFalloff.HARD to "Твер.",
    DabFalloff.SOFT to "Мягк.",
    DabFalloff.GAUSSIAN to "Гаусс",
    DabFalloff.AIRBRUSH to "Аэрогр.",
    DabFalloff.FLAT_MARKER to "Марк.",
)

private const val PREVIEW_DEBOUNCE_MS = 260L