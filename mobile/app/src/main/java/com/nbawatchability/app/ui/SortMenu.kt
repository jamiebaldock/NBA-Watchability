package com.nbawatchability.app.ui

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
