package com.wetinknext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.ui.theme.AppTheme

enum class AdjustmentAction(val title: String) {
    BRIGHTNESS_CONTRAST("Яркость и контраст"),
    THRESHOLD("Бинаризация"),
    BRIGHTNESS_TO_OPACITY("Преобразовать яркость в непрозрачность"),
}

@Composable
fun AdjustmentsPanel(
    theme: AppTheme,
    onClose: () -> Unit,
    onAction: (AdjustmentAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(280.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(theme.panelBg.copy(alpha = 0.98f))
            .border(1.dp, theme.panelStroke, RoundedCornerShape(16.dp))
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "Коррекция",
            color = theme.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            AdjustmentAction.entries.forEachIndexed { index, action ->
                AdjustmentItem(
                    title = action.title,
                    theme = theme,
                    onClick = { 
                        onClose()
                        onAction(action)
                    }
                )
                if (index < AdjustmentAction.entries.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = theme.panelStroke.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AdjustmentItem(
    title: String,
    theme: AppTheme,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = theme.textPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
