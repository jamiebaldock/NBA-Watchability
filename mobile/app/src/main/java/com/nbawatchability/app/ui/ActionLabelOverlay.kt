package com.nbawatchability.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.ui.theme.SurfaceCardElevated
import com.nbawatchability.app.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * A tiny "what just got toggled" confirmation - appears immediately when
 * [label] changes to a non-null value, holds for ~2s, then fades out over
 * ~2s. Used next to a top-bar icon button's tap, so flipping a toggle (sort
 * order, score visibility, etc.) gets a quick visual confirmation of what it
 * just did without needing a permanent on-screen label for every button.
 *
 * Since a 2-state toggle's label always differs from its previous value
 * (e.g. "Sorted by date" <-> "Sorted by rating"), a plain LaunchedEffect
 * keyed on the label string re-triggers correctly on every tap without
 * needing a separate counter key.
 */
@Composable
fun ActionLabelOverlay(label: String?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(label) {
        if (label == null) return@LaunchedEffect
        visible = true
        delay(2000)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(0)),
        exit = fadeOut(tween(2000)),
        modifier = modifier
    ) {
        label?.let {
            Surface(
                color = SurfaceCardElevated,
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = it,
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
