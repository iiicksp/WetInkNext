package com.wetinknext.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SwipeRevealRow(
    revealWidth: Dp,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    rightSwipeThreshold: Dp = 56.dp,
    onSwipeRight: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var rawOffset by remember { mutableStateOf(0f) }
    var rightDragOffset by remember { mutableStateOf(0f) }
    val revealPx = with(LocalDensity.current) { revealWidth.toPx() }
    val rightSwipeThresholdPx = with(LocalDensity.current) { rightSwipeThreshold.toPx() }
    val animatedOffset by animateFloatAsState(
        targetValue = rawOffset.coerceIn(-revealPx, 0f),
        label = "swipeRevealRow",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            content = actions,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .pointerInput(revealWidth) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dx ->
                            if (dx < 0f || rawOffset < 0f) {
                                change.consume()
                                rawOffset += dx
                                rightDragOffset = 0f
                            } else if (dx > 0f && rawOffset == 0f && onSwipeRight != null) {
                                change.consume()
                                rightDragOffset += dx
                            }
                        },
                        onDragEnd = {
                            if (rightDragOffset > rightSwipeThresholdPx) {
                                onSwipeRight?.invoke()
                            }
                            rawOffset = if (rawOffset < -revealPx * 0.4f) -revealPx else 0f
                            rightDragOffset = 0f
                        },
                        onDragCancel = {
                            rawOffset = 0f
                            rightDragOffset = 0f
                        },
                    )
                },
        ) {
            content()
        }
    }
}
