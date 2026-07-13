package com.nbawatchability.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val earliestDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * "Which old games are actually worth going back to watch" - surfaces the
 * NBA watchability backfill (2024-25 + 2025-26 seasons), Worth Your Time and
 * Instant Classic tiers only, most-watchable-first by default. Unlike every
 * other tab, these games are ones the viewer is intentionally browsing
 * rather than following live, so the breakdown is never spoiler-blurred
 * (GameCard's spoilerFree = true) - the tier/score/final result are the
 * point, not something to hide. [showScore] is a separate, purely local
 * "browse blind" preference (default on) - unlike spoilerFree/scoreVisible,
 * turning it off hides the rating itself (tier badge + breakdown) while
 * still showing the final score/teams, in case the user wants to pick a
 * game without the rating nudging them first.
 *
 * No hook/pitch preview text is generated or shown here (unlike the
 * pregame preview on other tabs) - these are already-finished games, so
 * there's nothing to preview, and historyService.ts never calls the LLM for
 * them at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    selectedPreset: HistoryRangePreset,
    earliestDate: java.time.LocalDate?,
    onPresetSelected: (HistoryRangePreset) -> Unit,
    onRetry: () -> Unit,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    weights: RubricWeights,
    starredIds: Set<String>,
    onToggleStar: (Game) -> Unit,
    onWatchHighlights: (String) -> Unit
) {
    var showScore by rememberSaveable { mutableStateOf(true) }
    // False (default) = server's own most-watchable-first order, left
    // untouched. True = newest game first - a local re-sort of the already-
    // fetched list, no re-fetch needed.
    var sortByDate by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("History", color = TextPrimary) },
                actions = {
                    IconToggleButton(checked = sortByDate, onCheckedChange = { sortByDate = it }) {
                        Icon(
                            imageVector = if (sortByDate) Icons.Default.CalendarToday else Icons.AutoMirrored.Filled.Sort,
                            contentDescription = if (sortByDate) "Sorted by date - tap for most watchable first" else "Sorted by watchability - tap for date order",
                            tint = if (sortByDate) TierWorthYourTime else TextSecondary
                        )
                    }
                    IconToggleButton(checked = showScore, onCheckedChange = { showScore = it }) {
                        Icon(
                            imageVector = if (showScore) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showScore) "Hide watchability rating" else "Show watchability rating",
                            tint = if (showScore) TierWorthYourTime else TextSecondary
                        )
                    }
                    IconToggleButton(checked = showNumericScore, onCheckedChange = { onToggleNumericScore() }) {
                        Icon(
                            imageVector = Icons.Default.Tag,
                            contentDescription = "Show numeric score",
                            tint = if (showNumericScore) TierWorthYourTime else TextSecondary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryRangePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = preset == selectedPreset,
                        onClick = { onPresetSelected(preset) },
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TierWorthYourTime,
                            selectedLabelColor = BackgroundBase
                        )
                    )
                }
            }

            when (uiState) {
                is HistoryUiState.Loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }

                is HistoryUiState.Error -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Couldn't load history",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = uiState.message,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                    Button(onClick = onRetry) { Text("Retry") }
                }

                is HistoryUiState.Loaded -> {
                    if (uiState.games.isEmpty()) {
                        val earliestText = earliestDate?.format(earliestDateFormatter)
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "No barn burners in this range" +
                                    (if (earliestText != null) " - data goes back to $earliestText, try a wider one." else "."),
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    } else {
                        // Score-sort order comes straight from the server (already
                        // most-watchable-first) - only re-sort locally when the
                        // user's asked for date order instead.
                        val ordered = if (sortByDate) {
                            uiState.games.sortedByDescending { OffsetDateTime.parse(it.tipoffUtc) }
                        } else {
                            uiState.games
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(ordered, key = { it.id }) { game ->
                                GameCard(
                                    game = game,
                                    showNumericScore = showNumericScore,
                                    weights = weights,
                                    isStarred = starredIds.contains(game.id),
                                    onToggleStar = { onToggleStar(game) },
                                    onWatchHighlights = onWatchHighlights,
                                    showDate = true,
                                    spoilerFree = true,
                                    showScore = showScore
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
