package com.wetinknext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.ui.theme.AppTheme
import kotlin.math.roundToInt

@Composable
fun ThresholdPanel(
    theme: AppTheme,
    threshold: Float, // 0..1
    preserveTransparency: Boolean,
    onThresholdChange: (Float) -> Unit,
    onPreserveTransparencyChange: (Boolean) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    isApplyEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(360.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(theme.panelBg.copy(alpha = 0.95f))
            .border(1.2.dp, theme.panelStroke, RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Бинаризация",
                color = theme.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(theme.panelInset)
                        .clickable { onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, null, tint = theme.textPrimary, modifier = Modifier.size(20.dp))
                }
                
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isApplyEnabled) theme.accent else theme.panelInset)
                        .clickable(enabled = isApplyEnabled) { onApply() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }

        Text(
            text = "Пиксели светлее порога станут белыми, остальные — чёрными",
            color = theme.textSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )

        // Threshold
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Порог", color = theme.textSecondary, fontSize = 13.sp)
                Text("${(threshold * 255).roundToInt()}", color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = threshold,
                onValueChange = onThresholdChange,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = theme.accent,
                    activeTrackColor = theme.accent,
                    inactiveTrackColor = theme.panelInset
                )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Сохранять прозрачность",
                color = theme.textPrimary,
                fontSize = 14.sp
            )
            Switch(
                checked = preserveTransparency,
                onCheckedChange = onPreserveTransparencyChange
            )
        }
    }
}
