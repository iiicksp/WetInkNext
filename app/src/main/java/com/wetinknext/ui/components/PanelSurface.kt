package com.wetinknext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.wetinknext.ui.theme.LocalAppTheme

@Composable
fun PanelSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalAppTheme.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .shadow(12.dp, shape, ambientColor = theme.panelInset.copy(alpha = 0.5f), spotColor = theme.panelInset.copy(alpha = 0.5f))
            .clip(shape)
            .background(theme.panelBg)
            .border(1.2.dp, theme.panelStroke, shape)
    ) {
        Box(modifier = Modifier.matchParentSize().swallowChrome())
        content()
    }
}

private fun Modifier.swallowChrome(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) {
                if (change.pressed && !change.previousPressed) {
                    change.consume()
                }
            }
        }
    }
}
