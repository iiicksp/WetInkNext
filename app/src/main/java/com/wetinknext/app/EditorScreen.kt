package com.wetinknext.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wetinknext.engine.core.PaintSurfaceView
import com.wetinknext.ui.theme.WetInkNextPalette

@Composable
fun EditorScreen() {
    val palette = WetInkNextPalette.default

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.appBg),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> PaintSurfaceView(context) },
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                .background(palette.panelBg.copy(alpha = 0.94f), RoundedCornerShape(24.dp))
                .border(1.dp, palette.panelStroke, RoundedCornerShape(24.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(7) {
                Box(Modifier.size(18.dp).background(palette.textPrimary.copy(alpha = 0.9f), CircleShape))
            }
            Box(Modifier.size(26.dp).background(palette.accent, CircleShape))
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .background(palette.panelBg.copy(alpha = 0.94f), RoundedCornerShape(24.dp))
                .border(1.dp, palette.panelStroke, RoundedCornerShape(24.dp))
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(5) {
                Box(Modifier.size(22.dp).background(palette.textPrimary.copy(alpha = 0.9f), CircleShape))
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
                .background(palette.panelBg.copy(alpha = 0.94f), RoundedCornerShape(28.dp))
                .border(1.dp, palette.panelStroke, RoundedCornerShape(28.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("WetInk Next", color = palette.textPrimary, fontSize = 16.sp)
            Text("P6: layers, compositor, undo/redo", color = palette.textSecondary, fontSize = 12.sp)
        }
    }
}
