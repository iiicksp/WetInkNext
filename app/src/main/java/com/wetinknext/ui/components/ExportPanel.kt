package com.wetinknext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.ui.theme.AppTheme

/** Export targets offered by [ExportPanel]. */
enum class ExportFormat { PNG, JPEG, PSD }

/**
 * Procreate-style export sheet: PNG / JPEG / PSD rows with a live status line.
 * JPEG is flattened onto the canvas backdrop colour; PNG keeps transparency;
 * PSD keeps layers, opacity and visibility.
 */
@Composable
fun ExportPanel(
    theme: AppTheme,
    busy: Boolean,
    status: String?,
    onExport: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    PanelSurface(modifier = Modifier.width(280.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Экспорт работы",
                    color = theme.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
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

            ExportRow(
                theme = theme,
                icon = Icons.Default.Image,
                label = "PNG",
                subtitle = "С прозрачностью",
                enabled = !busy,
                onClick = { onExport(ExportFormat.PNG) },
            )
            ExportRow(
                theme = theme,
                icon = Icons.Default.Photo,
                label = "JPEG",
                subtitle = "На фоне холста",
                enabled = !busy,
                onClick = { onExport(ExportFormat.JPEG) },
            )
            ExportRow(
                theme = theme,
                icon = Icons.Default.Description,
                label = "PSD",
                subtitle = "Со слоями",
                enabled = !busy,
                onClick = { onExport(ExportFormat.PSD) },
            )

            if (status != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = status,
                    color = theme.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ExportRow(
    theme: AppTheme,
    icon: ImageVector,
    label: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(theme.accentMuted.copy(alpha = 0.18f))
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) theme.accent else theme.iconInactive,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) theme.textPrimary else theme.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = theme.textSecondary,
                fontSize = 11.sp,
            )
        }
        if (!enabled) {
            Text(
                text = "…",
                color = theme.textSecondary,
                fontSize = 14.sp,
            )
        }
    }
}