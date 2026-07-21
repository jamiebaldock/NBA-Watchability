package com.nbawatchability.app.ui

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

/** The 4 ways a game list can be ordered - one option active at a time. */
enum class SortOption(val label: String) {
    DATE_OLDEST_FIRST("Oldest first"),
    DATE_NEWEST_FIRST("Newest first"),
    RATING_HIGHEST_FIRST("Highest rated first"),
    RATING_LOWEST_FIRST("Lowest rated first")
}

/**
 * Two independent toggle buttons - date order and rating order - replacing
 * the old single dropdown-behind-one-icon control. Each button carries its
 * own small up/down arrow badge showing which direction it's currently
 * sorting: up = ascending (oldest first / lowest rated first), down =
 * descending (newest first / highest rated first) - the same convention on
 * both buttons, just applied to a different axis.
 *
 * Tapping the button for the axis that's already active flips its direction.
 * Tapping the other axis's button switches the active sort over to it,
 * defaulting to oldest-first for date and highest-rated-first for rating
 * (whichever is the more commonly useful first look at that axis) rather
 * than preserving whatever direction that axis last had.
 */
@Composable
fun SortMenuButton(selected: SortOption, onSelected: (SortOption) -> Unit) {
    Row {
        SortToggleButton(
            icon = Icons.Filled.CalendarMonth,
            isActive = selected == SortOption.DATE_OLDEST_FIRST || selected == SortOption.DATE_NEWEST_FIRST,
            pointsUp = selected != SortOption.DATE_NEWEST_FIRST,
            activeLabel = if (selected == SortOption.DATE_NEWEST_FIRST) "Newest first" else "Oldest first",
            onClick = {
                onSelected(
                    if (selected == SortOption.DATE_OLDEST_FIRST) SortOption.DATE_NEWEST_FIRST else SortOption.DATE_OLDEST_FIRST
                )
            }
        )
        SortToggleButton(
            icon = Icons.Filled.Star,
            isActive = selected == SortOption.RATING_HIGHEST_FIRST || selected == SortOption.RATING_LOWEST_FIRST,
            pointsUp = selected == SortOption.RATING_LOWEST_FIRST,
            activeLabel = if (selected == SortOption.RATING_LOWEST_FIRST) "Lowest rated first" else "Highest rated first",
            onClick = {
                onSelected(
                    if (selected == SortOption.RATING_HIGHEST_FIRST) SortOption.RATING_LOWEST_FIRST else SortOption.RATING_HIGHEST_FIRST
                )
            }
        )
    }
}

@Composable
private fun SortToggleButton(
    icon: ImageVector,
    isActive: Boolean,
    pointsUp: Boolean,
    activeLabel: String,
    onClick: () -> Unit
) {
    val tint = if (isActive) TierWorthYourTime else TextSecondary
    IconButton(onClick = onClick) {
        Box(modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = if (isActive) "Sort: $activeLabel" else null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Icon(
                imageVector = if (pointsUp) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}

// Quadratic ease-in (slow start, accelerating finish) - used for long
// scroll-backs so covering a lot of tiles doesn't just feel like a long
// constant crawl.
private val EaseInAccelerating = Easing { fraction -> fraction * fraction }

/**
 * Scrolls back to the top after a re-sort, animated rather than snapped -
 * without this, LazyColumn's key-based item tracking keeps whatever was at
 * the top of the viewport in view across a reorder (since that item is
 * usually still somewhere in the new list, just at a different index),
 * which both looks like a jarring non-scroll AND, worse, can make an
 * otherwise-correct sort look wrong (e.g. "oldest first" appearing to skip
 * over dozens of genuinely-older tiles that are simply off-screen above).
 *
 * The animation's pace scales with how far there is to travel: a short hop
 * (a handful of tiles) gets a smooth, constant-speed roll the whole way -
 * no need to build up speed for a quick trip. A long way back (e.g. "All
 * time" with dozens of tiles between) starts slow and accelerates, so it
 * reads as covering real distance efficiently rather than a fixed-speed
 * crawl that'd otherwise take forever. Driven by index (via scrollToItem
 * every frame) rather than pixel offsets, since list tiles vary in height
 * (expanded breakdowns, spoiler blur, etc.) - index-based stepping lands
 * exactly on the target regardless of that variance.
 *
 * Durations deliberately sit well above what a snappy UI transition would
 * normally use - an early pass tuned this against a much faster floor/cap
 * (300ms-1100ms) and it read as a flicker rather than a scroll, even on the
 * "slow" short-hop end. The whole curve is shifted down in speed here
 * (600ms-2200ms) so even the fastest case still reads as a deliberate roll.
 */
suspend fun LazyListState.animateScrollToTopAdaptively() {
    val startIndex = firstVisibleItemIndex
    if (startIndex <= 0) return

    val shortHop = startIndex <= 8
    val durationMillis = (600 + startIndex * 25).coerceIn(600, 2200)
    val easing: Easing = if (shortHop) LinearEasing else EaseInAccelerating

    val startTimeNanos = withFrameNanos { it }
    while (true) {
        val elapsedMs = (withFrameNanos { it } - startTimeNanos) / 1_000_000
        val rawFraction = (elapsedMs.toFloat() / durationMillis).coerceIn(0f, 1f)
        val eased = easing.transform(rawFraction)
        val index = (startIndex * (1f - eased)).toInt().coerceIn(0, startIndex)
        scrollToItem(index)
        if (rawFraction >= 1f) break
    }
    scrollToItem(0)
}
