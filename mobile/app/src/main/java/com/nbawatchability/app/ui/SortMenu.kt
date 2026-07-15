package com.nbawatchability.app.ui

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

/** The 4 ways a game list can be ordered - one option picked at a time, unlike the old pair of independent date/rating toggles. */
enum class SortOption(val label: String) {
    DATE_OLDEST_FIRST("Oldest first"),
    DATE_NEWEST_FIRST("Newest first"),
    RATING_HIGHEST_FIRST("Highest rated first"),
    RATING_LOWEST_FIRST("Lowest rated first")
}

/**
 * A single sort icon that opens a 4-option dropdown (date asc/desc, rating
 * high/low first) - replaces what used to be two separate icon toggles
 * (a date-order toggle and a best-first-rating toggle) on Starred and
 * History, since those two controls together only ever expressed one of
 * these same 4 combinations at a time anyway.
 */
@Composable
fun SortMenuButton(selected: SortOption, onSelected: (SortOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Sort: ${selected.label}",
                tint = TextSecondary
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            color = if (option == selected) TierWorthYourTime else TextPrimary
                        )
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
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
