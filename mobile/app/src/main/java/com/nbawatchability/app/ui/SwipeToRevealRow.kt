package com.nbawatchability.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.LiveRed
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val REVEAL_WIDTH = 72.dp
private const val SNAP_ANIMATION_MS = 200

/**
 * Replaces the old persistent "X" remove icon on Favorites' Teams/Players
 * management lists (FavoritesScreen.kt) with an iOS-style swipe-left reveal:
 * dragging [content] left uncovers a fixed-width red delete button behind
 * it, snapping fully open past the halfway point (or back closed otherwise)
 * on release. Tapping the revealed delete button fires [onDelete]; tapping
 * the still-visible content while open closes it again instead of doing
 * nothing. No material3 SwipeToDismissBox equivalent fits here - that API
 * commits the dismiss on a completed swipe (item just disappears), not
 * "reveal a button, wait for a separate confirming tap" - so this is a
 * small hand-rolled drag+Animatable implementation instead.
 */
@Composable
fun SwipeToRevealRow(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val revealWidthPx = remember(density) { with(density) { REVEAL_WIDTH.toPx() } }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(LiveRed, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = Color.White,
                modifier = Modifier
                    .padding(end = 20.dp)
                    .size(26.dp)
                    .clickable {
                        onDelete()
                        scope.launch { offsetX.snapTo(0f) }
                    }
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(revealWidthPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = if (offsetX.value < -revealWidthPx / 2f) -revealWidthPx else 0f
                            scope.launch { offsetX.animateTo(target, animationSpec = tween(SNAP_ANIMATION_MS)) }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val newValue = (offsetX.value + dragAmount).coerceIn(-revealWidthPx, 0f)
                            scope.launch { offsetX.snapTo(newValue) }
                        }
                    )
                }
                .clickable(
                    enabled = offsetX.value != 0f,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    scope.launch { offsetX.animateTo(0f, animationSpec = tween(SNAP_ANIMATION_MS)) }
                }
                // Opaque - masks the red delete background behind it (the
                // gaps between a row's own children, e.g. around an avatar
                // or in padding, would otherwise let red bleed through).
                .background(BackgroundBase)
        ) {
            content()
        }
    }
}
